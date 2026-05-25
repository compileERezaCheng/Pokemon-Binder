package com.example.pokemongrader.data

import android.util.Base64
import com.example.pokemongrader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AuthResult(val idToken: String, val uid: String)

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

    private val API_KEY = decrypt(BuildConfig.ENC_FIREBASE_API_KEY)
    private val DB_URL = decrypt(BuildConfig.ENC_FIREBASE_DB_URL)

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
        val conn = url.openConnection() as HttpURLConnection
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
        AuthResult(json.getString("idToken"), json.getString("localId"))
    }

    suspend fun signUp(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val url = URL("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY")
        val conn = url.openConnection() as HttpURLConnection
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
        AuthResult(json.getString("idToken"), json.getString("localId"))
    }

    suspend fun fetchCollection(uid: String, token: String): List<Card> = withContext(Dispatchers.IO) {
        val url = URL("$DB_URL/users/$uid/collection.json?auth=$token")
        val conn = url.openConnection() as HttpURLConnection
        val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        Card.parseCollection(res)
    }

    suspend fun saveCollection(uid: String, token: String, collection: List<Card>): Unit = withContext(Dispatchers.IO) {
        val url = URL("$DB_URL/users/$uid/collection.json?auth=$token")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(Card.serializeCollection(collection).toByteArray())
        conn.responseCode
    }

    suspend fun fetchUsername(uid: String, token: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$DB_URL/users/$uid/username.json?auth=$token")
            val conn = url.openConnection() as HttpURLConnection
            val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            if (res.trim() == "null" || res.trim().isEmpty()) null else res.replace("\"", "")
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchProfile(uid: String, token: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$DB_URL/users/$uid/profile.json?auth=$token")
            val conn = url.openConnection() as HttpURLConnection
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
        val url = URL("$DB_URL/users/$uid/profile.json?auth=$token")
        val conn = url.openConnection() as HttpURLConnection
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
        val uUrl = URL("$DB_URL/users/$uid/username.json?auth=$token")
        val uConn = uUrl.openConnection() as HttpURLConnection
        uConn.requestMethod = "PUT"
        uConn.doOutput = true
        uConn.outputStream.write("\"$username\"".toByteArray())
        
        conn.responseCode
        uConn.responseCode
    }
}

