package com.example.pokemongrader.data

import android.util.Base64
import com.example.pokemongrader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AuthResult(val idToken: String, val uid: String, val refreshToken: String = "")

internal class CardSyncConflictException(
    val copyId: String,
    val base: Card?,
    val local: Card?,
    val remote: Card?,
    message: String
) : IOException(message)

object FirebaseClient {
    private const val KEY = "PokeGraderSecureKey2026"

    private fun decrypt(encrypted: String): String {
        return try {
            val decodedBytes = Base64.decode(encrypted, Base64.DEFAULT)
            val decodedStr = String(decodedBytes, Charsets.ISO_8859_1)
            val sb = StringBuilder()
            for (i in decodedStr.indices) {
                sb.append((decodedStr[i].code xor KEY[i % KEY.length].code).toChar())
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private val API_KEY = BuildConfig.ENC_FIREBASE_API_KEY
        .takeUnless { it.startsWith("PLACEHOLDER_") }
        ?.let(::decrypt)
        .orEmpty()
    internal val firebaseApiKey: String get() = API_KEY
    internal var databaseUrl = decrypt(BuildConfig.ENC_FIREBASE_DB_URL)
    internal var connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }

    /** Silently exchange a refreshToken for a new idToken. Returns null on failure. */
    suspend fun refreshIdToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://securetoken.googleapis.com/v1/token?key=$API_KEY")
            val conn = connectionFactory(url)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = "grant_type=refresh_token&refresh_token=$refreshToken"
            conn.outputStream.write(body.toByteArray())
            if (conn.responseCode != 200) return@withContext null
            val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            val json = JSONObject(res)
            json.optString("id_token").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun handleHttpError(conn: HttpURLConnection): Nothing {
        val errorRes = try {
            BufferedReader(InputStreamReader(conn.errorStream)).readText()
        } catch (e: Exception) {
            ""
        }
        val cleanMsg = try {
            val json = JSONObject(errorRes)
            val errObj = json.getJSONObject("error")
            val rawMsg = errObj.getString("message")
            when (rawMsg) {
                "INVALID_PASSWORD", "EMAIL_NOT_FOUND" -> "Invalid email or password"
                "USER_DISABLED" -> "This user account has been disabled"
                "EMAIL_EXISTS" -> "This email address is already registered"
                "TOO_MANY_ATTEMPTS_TRY_LATER" -> "Too many failed attempts. Try again later."
                else -> rawMsg.replace("_", " ").lowercase()
            }
        } catch (e: Exception) {
            "Authentication failed (HTTP ${conn.responseCode})"
        }
        throw Exception(cleanMsg)
    }

    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val url = URL("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$API_KEY")
        val conn = connectionFactory(url)
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val body = JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString()
        conn.outputStream.write(body.toByteArray())
        
        if (conn.responseCode != 200) {
            handleHttpError(conn)
        }
        
        val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        val json = JSONObject(res)
        AuthResult(json.getString("idToken"), json.getString("localId"), json.optString("refreshToken", ""))
    }

    suspend fun signUp(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val url = URL("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY")
        val conn = connectionFactory(url)
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val body = JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString()
        conn.outputStream.write(body.toByteArray())
        
        if (conn.responseCode != 200) {
            handleHttpError(conn)
        }
        
        val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        val json = JSONObject(res)
        AuthResult(json.getString("idToken"), json.getString("localId"), json.optString("refreshToken", ""))
    }

    suspend fun fetchCollection(uid: String, token: String): List<Card> = withContext(Dispatchers.IO) {
        val versionedUrl = collectionUrl(uid, token)
        val versioned = getJson(versionedUrl)
        if (versioned.trim() != "null") return@withContext Card.parseCollection(versioned)

        val legacy = Card.parseCollection(getJson(legacyCollectionUrl(uid, token)))
        legacy.forEach { writeCopy(uid, token, base = null, local = it) }
        if (legacy.isEmpty()) emptyList() else Card.parseCollection(getJson(versionedUrl))
    }

    suspend fun saveCollection(uid: String, token: String, collection: List<Card>): Unit = withContext(Dispatchers.IO) {
        collection.forEach { writeCopy(uid, token, base = null, local = it) }
    }

    suspend fun writeCopy(uid: String, token: String, base: Card?, local: Card?, delete: Boolean = false): Card? = withContext(Dispatchers.IO) {
        val copyId = (local ?: base)?.copyId?.takeIf { it.isNotBlank() }
            ?: throw IOException("A copy ID is required to sync a card.")
        var latestRemote: Card? = null

        for (attempt in 0 until 3) {
            val snapshot = readCopy(uid, token, copyId)
            val currentJson = snapshot.value
            val currentDeleted = currentJson?.optBoolean("_deleted") == true
            val current = currentJson?.takeUnless { currentDeleted }?.let { Card.fromJsonObject(it, copyId) }
            latestRemote = current

            if (delete) {
                if (current == null) return@withContext null
                if (base != null && changedFields(base, current).isNotEmpty()) {
                    throw conflict(copyId, base, null, current, "This card changed on another device. The deletion was not applied.")
                }
                val tombstone = JSONObject(currentJson.toString()).put("_deleted", true)
                if (putCopy(uid, token, copyId, snapshot.etag, tombstone)) return@withContext null
                continue
            }

            val requested = local ?: throw IOException("A card is required to sync a change.")
            if (base == null) {
                if (currentJson == null) {
                    if (putCopy(uid, token, copyId, snapshot.etag, requested.toJsonObject().put("Copy ID", copyId))) {
                        return@withContext requested
                    }
                    continue
                }
                if (currentDeleted) throw conflict(copyId, null, requested, null, "This copy was removed on another device and cannot be restored.")
                if (current != null && sameAdd(current, requested)) return@withContext current
                throw conflict(copyId, null, requested, current, "A different card already uses this copy ID.")
            }

            if (current == null) {
                throw conflict(copyId, base, requested, null, "This card was removed on another device. Your edit was kept for recovery.")
            }
            if (changedFields(base, requested).isEmpty()) return@withContext current
            val merged = mergeCard(base, requested, current, currentJson)
            if (merged == null) {
                throw conflict(copyId, base, requested, current, "This card changed on another device. Your edit was kept for recovery.")
            }
            if (syncFields.all { sameValue(merged, currentJson, it) }) return@withContext current
            if (putCopy(uid, token, copyId, snapshot.etag, merged)) return@withContext Card.fromJsonObject(merged, copyId)
        }

        throw conflict(copyId, base, local, latestRemote, "This card kept changing on another device. Your edit was kept for recovery.")
    }

    private data class CopySnapshot(val value: JSONObject?, val etag: String)

    private fun collectionUrl(uid: String, token: String) =
        "${databaseUrl.trimEnd('/')}/users/${encodePath(uid)}/collection_v2.json?auth=${encodeQuery(token)}"

    private fun legacyCollectionUrl(uid: String, token: String) =
        "${databaseUrl.trimEnd('/')}/users/${encodePath(uid)}/collection.json?auth=${encodeQuery(token)}"

    private fun copyUrl(uid: String, token: String, copyId: String) =
        "${databaseUrl.trimEnd('/')}/users/${encodePath(uid)}/collection_v2/${encodePath(copyId)}.json?auth=${encodeQuery(token)}"

    private fun encodePath(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun encodeQuery(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun getJson(address: String): String {
        val conn = connectionFactory(URL(address))
        try {
            val status = conn.responseCode
            if (status !in 200..299) throw IOException("Firebase read failed (HTTP $status).")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun readCopy(uid: String, token: String, copyId: String): CopySnapshot {
        val conn = connectionFactory(URL(copyUrl(uid, token, copyId)))
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Firebase-ETag", "true")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val status = conn.responseCode
            if (status !in 200..299) throw IOException("Firebase card read failed (HTTP $status).")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val etag = conn.getHeaderField("ETag") ?: throw IOException("Firebase did not return a card version.")
            return CopySnapshot(body.takeIf { it.trim() != "null" }?.let(::JSONObject), etag)
        } finally {
            conn.disconnect()
        }
    }

    private fun putCopy(uid: String, token: String, copyId: String, etag: String, value: JSONObject): Boolean {
        val conn = connectionFactory(URL(copyUrl(uid, token, copyId)))
        try {
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("If-Match", etag)
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.outputStream.use { it.write(value.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status == 412) return false
            if (status !in 200..299) throw IOException("Firebase card save failed (HTTP $status).")
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun conflict(copyId: String, base: Card?, local: Card?, remote: Card?, message: String) =
        CardSyncConflictException(copyId, base, local, remote, message)

    private val printingFields = setOf("Catalog Card ID", "Set ID", "Set Name", "Card Number", "Catalog Rarity", "Image URL", "Image Source", "Image Match ID")
    private val syncFields = listOf("Page", "Slot", "Dex Number", "Name", "Type", "Condition", "Notes", "Date Added", "Image URL", "Catalog Card ID", "Set ID", "Set Name", "Card Number", "Catalog Rarity", "Grade", "Grade Details", "Image Source", "Image Match ID")

    private fun changedFields(base: Card, changed: Card): List<String> {
        val before = base.toJsonObject()
        val after = changed.toJsonObject()
        return syncFields.filter { !sameValue(before, after, it) }
    }

    private fun mergeCard(base: Card, local: Card, remote: Card, remoteJson: JSONObject): JSONObject? {
        val before = base.toJsonObject()
        val desired = local.toJsonObject()
        val current = remote.toJsonObject()
        val localChanges = changedFields(base, local)
        val remoteChanges = changedFields(base, remote)
        val printingChangedLocally = localChanges.any { it in printingFields }
        val printingChangedRemotely = remoteChanges.any { it in printingFields }

        val fieldConflict = localChanges.any { field ->
            field !in printingFields && field in remoteChanges && !sameValue(desired, current, field)
        }
        val printingConflict = printingChangedLocally && printingChangedRemotely &&
            printingFields.any { !sameValue(desired, current, it) }
        if (fieldConflict || printingConflict) return null

        val fieldsToApply = localChanges.filterNot { it in printingFields } +
            if (printingChangedLocally) printingFields else emptySet()
        return JSONObject(remoteJson.toString()).apply {
            fieldsToApply.forEach { field ->
                if (desired.has(field) && !desired.isNull(field)) put(field, desired.get(field)) else remove(field)
            }
            put("Copy ID", local.copyId)
            remove("_deleted")
        }
    }

    private fun sameAdd(existing: Card, requested: Card): Boolean =
        listOf("Page", "Slot", "Dex Number", "Name", "Type", "Condition", "Catalog Card ID", "Set ID", "Card Number")
            .all { sameValue(existing.toJsonObject(), requested.toJsonObject(), it) }

    private fun sameValue(left: JSONObject, right: JSONObject, field: String): Boolean =
        canonical(left.opt(field).takeUnless { !left.has(field) || left.isNull(field) }) ==
            canonical(right.opt(field).takeUnless { !right.has(field) || right.isNull(field) })

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is Number -> value.toDouble().toString()
        else -> value.toString()
    }

    suspend fun fetchUsername(uid: String, token: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$databaseUrl/users/$uid/username.json?auth=$token")
            val conn = connectionFactory(url)
            val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            if (res.trim() == "null" || res.trim().isEmpty()) null else res.replace("\"", "")
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchProfile(uid: String, token: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$databaseUrl/users/$uid/profile.json?auth=$token")
            val conn = connectionFactory(url)
            val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            if (res.trim() == "null" || res.trim().isEmpty()) null else JSONObject(res)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveProfile(
        uid: String,
        token: String,
        username: String,
        source: String,
        dex: Int,
        urlImg: String,
        base64Img: String
    ): Unit = withContext(Dispatchers.IO) {
        val url = URL("$databaseUrl/users/$uid/profile.json?auth=$token")
        val conn = connectionFactory(url)
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        
        val profile = JSONObject()
            .put("username", username)
            .put("profile_picture_source", source)
            .put("profile_featured_dex", dex)
            .put("profile_image_url", urlImg)
            .put("profile_image_base64", base64Img)
            
        conn.outputStream.write(profile.toString().toByteArray())
        
        // Also update the top-level username for compatibility
        val uUrl = URL("$databaseUrl/users/$uid/username.json?auth=$token")
        val uConn = connectionFactory(uUrl)
        uConn.requestMethod = "PUT"
        uConn.doOutput = true
        uConn.outputStream.write("\"$username\"".toByteArray())
        
        conn.responseCode
        uConn.responseCode
    }

}

