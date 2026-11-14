package com.example.pokemongrader.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DataRepositoryTest {
    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private val originalFactory = FirebaseClient.connectionFactory
    private val originalDatabaseUrl = FirebaseClient.databaseUrl
    private val connections = mutableListOf<StubConnection>()
    private val remoteRecords = mutableMapOf<String, String>()
    private val remoteVersions = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        prefs = context.getSharedPreferences("PokeGraderPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        remoteRecords.clear()
        remoteVersions.clear()
        FirebaseClient.databaseUrl = "https://firebase.test"
        FirebaseClient.connectionFactory = { url -> StubConnection(url, remoteRecords, remoteVersions).also(connections::add) }
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        FirebaseClient.connectionFactory = originalFactory
        FirebaseClient.databaseUrl = originalDatabaseUrl
    }

    @Test
    fun freshProfileUsesGenericTrainerName() {
        val repository = DefaultDataRepository(context)

        assertEquals("Trainer", repository.username.value)
    }

    @Test
    fun cachedProfileIsLoadedFromTheSavedUidNamespace() {
        prefs.edit()
            .putString("uid", "uid-A")
            .putString("refresh_token", "refresh-A")
            .putString("username", "Trainer B")
            .putString("profile:uid-A:username", "Trainer A")
            .putString("profile:uid-B:username", "Trainer B")
            .commit()

        val repository = DefaultDataRepository(context)

        assertEquals("Trainer A", repository.username.value)
    }

    @Test
    fun loginLoadsCollectionAndCardWritesStayInTheMock() = runBlocking {
        val repository = DefaultDataRepository(context)

        assertTrue(repository.login("unit@example.test", "test-password"))
        assertEquals("unit-user", prefs.getString("uid", null))
        assertEquals("unit-refresh-token", prefs.getString("refresh_token", null))
        assertEquals("pikachu", repository.cards.value.single().name)

        val added = Card(1, 2, 0, "mew", "Normal", "NM", "", "unit-date")
        assertTrue(repository.addCard(added))
        assertEquals(2, repository.cards.value.size)
        val addWrite = connections.single { it.requestMethod == "PUT" && it.url.path.endsWith("/collection_v2/${added.copyId}.json") }
        assertEquals("null_etag", addWrite.requestProperty("If-Match"))
        assertTrue(addWrite.requestBody.toString().contains("\"Name\":\"mew\""))
        assertTrue(connections.none { it.requestMethod == "PUT" && (it.url.path.endsWith("/collection.json") || it.url.path.endsWith("/collection_v2.json")) })

        assertTrue(repository.removeCard(added))
        assertEquals(1, repository.cards.value.size)
        assertEquals(true, JSONObject(remoteRecords.getValue(added.copyId)).optBoolean("_deleted"))
    }

    @Test
    fun savedRefreshTokenRestoresAndRefreshesTheCollection() = runBlocking {
        prefs.edit()
            .putString("uid", "unit-user")
            .putString("token", "expired-unit-token")
            .putString("refresh_token", "unit-refresh-token")
            .commit()

        val repository = DefaultDataRepository(context)
        assertTrue(repository.refreshAndFetch())

        assertTrue(repository.isLoggedIn.value)
        assertEquals("fresh-unit-token", prefs.getString("token", null))
        assertEquals("pikachu", repository.cards.value.single().name)
        assertTrue(connections.any { it.url.host == "securetoken.googleapis.com" })
    }

    @Test
    fun refreshKeepsLoadingActiveWhileRefreshingTheAuthToken() = runBlocking {
        val repository = DefaultDataRepository(context)
        prefs.edit()
            .putString("uid", "unit-user")
            .putString("token", "expired-unit-token")
            .putString("refresh_token", "unit-refresh-token")
            .commit()
        val loadingDuringTokenRefresh = AtomicBoolean(false)
        FirebaseClient.connectionFactory = { url ->
            if (url.host == "securetoken.googleapis.com") {
                loadingDuringTokenRefresh.set(repository.isLoading.value)
            }
            StubConnection(url, remoteRecords, remoteVersions).also(connections::add)
        }

        assertTrue(repository.refreshAndFetch())
        assertTrue(loadingDuringTokenRefresh.get())
    }

    @Test
    fun failedCollectionRefreshSurfacesTheError() = runBlocking {
        prefs.edit().putString("uid", "unit-user").putString("token", "unit-token").commit()
        FirebaseClient.connectionFactory = { throw IllegalStateException("Network unavailable") }
        val repository = DefaultDataRepository(context)

        assertEquals(false, repository.refreshAndFetch())
        assertEquals("Network unavailable", repository.errorMessage.value)
    }

    @Test
    fun lateResponseFromPreviousLoginCannotReplaceCurrentAccount() = runBlocking {
        val repository = DefaultDataRepository(context)
        val oldAccountFetched = CountDownLatch(1)
        val releaseOldAccount = CountDownLatch(1)
        FirebaseClient.connectionFactory = { url -> SessionConnection(url, oldAccountFetched, releaseOldAccount) }

        try {
            val loginA = async(Dispatchers.IO) { repository.login("a@example.test", "test-password") }
            assertTrue("Account A did not reach its delayed fetch", oldAccountFetched.await(5, TimeUnit.SECONDS))

            val loginB = async(Dispatchers.IO) { repository.login("b@example.test", "test-password") }
            assertTrue(loginB.await())
            releaseOldAccount.countDown()
            assertEquals(false, loginA.await())

            assertEquals("card-B", repository.cards.value.single().name)
            assertEquals("Trainer B", repository.username.value)
            assertEquals("uid-B", prefs.getString("uid", null))
            assertEquals("token-B", prefs.getString("token", null))
        } finally {
            releaseOldAccount.countDown()
        }
    }

    @Test
    fun lateResponseAfterLogoutCannotRestorePreviousSession() = runBlocking {
        val repository = DefaultDataRepository(context)
        val oldAccountFetched = CountDownLatch(1)
        val releaseOldAccount = CountDownLatch(1)
        FirebaseClient.connectionFactory = { url -> SessionConnection(url, oldAccountFetched, releaseOldAccount) }

        try {
            val loginA = async(Dispatchers.IO) { repository.login("a@example.test", "test-password") }
            assertTrue("Account A did not reach its delayed fetch", oldAccountFetched.await(5, TimeUnit.SECONDS))

            repository.logout()
            releaseOldAccount.countDown()

            assertEquals(false, loginA.await())
            assertTrue(repository.cards.value.isEmpty())
            assertEquals(false, repository.isLoggedIn.value)
            assertEquals("Trainer", repository.username.value)
            assertEquals(null, prefs.getString("uid", null))
        } finally {
            releaseOldAccount.countDown()
        }
    }

    @Test
    fun concurrentAddsFromTheSameSnapshotPreserveBothCopies() = runBlocking {
        val database = MemoryDatabase(rootPutBarrier = CountDownLatch(2))
        FirebaseClient.connectionFactory = database::connection
        val repository = DefaultDataRepository(context)
        assertTrue(repository.login("unit@example.test", "test-password"))

        val first = Card(1, 1, 25, "pikachu", "Normal", "NM", "", "today", copyId = "copy-a")
        val second = Card(1, 2, 25, "pikachu", "Normal", "NM", "", "today", copyId = "copy-b")
        val writes = listOf(
            async(Dispatchers.IO) { repository.addCard(first) },
            async(Dispatchers.IO) { repository.addCard(second) }
        )

        assertTrue(writes.all { it.await() })
        assertEquals(setOf("copy-a", "copy-b"), database.allCopyIds())
    }

    @Test
    fun AndroidNoteEditMergesRemoteGradeAndPreservesSameFieldConflict() = runBlocking {
        val database = MemoryDatabase()
        val original = Card(1, 1, 25, "pikachu", "Normal", "NM", "Original", "today", grade = 8.0, copyId = "copy-one")
        database.seed(original)
        FirebaseClient.connectionFactory = database::connection
        val repository = DefaultDataRepository(context)
        assertTrue(repository.login("unit@example.test", "test-password"))

        database.mutateAfterNextRead = "copy-one" to ("Grade" to 9.0)
        val noteEdit = repository.cards.value.single().copy(notes = "Android note")
        assertTrue(repository.updateCard(noteEdit))
        assertEquals(9.0, repository.cards.value.single().grade, 0.0)
        assertEquals("Android note", repository.cards.value.single().notes)

        database.mutate("copy-one", "Notes" to "Windows note")
        val conflictEdit = repository.cards.value.single().copy(notes = "Android revision")
        assertEquals(false, repository.updateCard(conflictEdit))
        assertEquals("Windows note", database.record("copy-one")?.optString("Notes"))
        assertTrue(repository.errorMessage.value.orEmpty().contains("changed", ignoreCase = true))
        val recovery = JSONObject(prefs.getString("sync_conflict:uid-unit:copy-one", "{}")!!)
        assertEquals("Android revision", recovery.getJSONObject("local").optString("Notes"))
    }

    @Test
    fun removingACopyWritesATombstoneAndLaterEditCannotRestoreIt() = runBlocking {
        val database = MemoryDatabase()
        val original = Card(1, 1, 25, "pikachu", "Normal", "NM", "", "today", copyId = "copy-one")
        database.seed(original)
        FirebaseClient.connectionFactory = database::connection
        val repository = DefaultDataRepository(context)
        assertTrue(repository.login("unit@example.test", "test-password"))

        assertTrue(repository.removeCard(original))
        assertEquals(true, database.record("copy-one")?.optBoolean("_deleted"))
        assertEquals(false, repository.updateCard(original.copy(notes = "late edit")))
        assertEquals(true, database.record("copy-one")?.optBoolean("_deleted"))
    }

    @Test
    fun retryAfterAmbiguousAddKeepsTheOriginalCopyId() = runBlocking {
        val database = MemoryDatabase().apply { failNextWriteAfterCommit = true }
        FirebaseClient.connectionFactory = database::connection
        val repository = DefaultDataRepository(context)
        assertTrue(repository.login("unit@example.test", "test-password"))
        val firstTry = Card(1, 1, 25, "pikachu", "Normal", "NM", "", "today", copyId = "copy-original")

        assertEquals(false, repository.addCard(firstTry))
        val retry = firstTry.copy(copyId = "copy-regenerated")
        assertTrue(repository.addCard(retry))

        assertEquals(setOf("copy-original"), database.allCopyIds())
        assertEquals("copy-original", repository.cards.value.single().copyId)
    }

    private class StubConnection(
        url: URL,
        private val records: MutableMap<String, String>,
        private val versions: MutableMap<String, Int>
    ) : HttpURLConnection(url) {
        val requestBody = ByteArrayOutputStream()
        private val headers = mutableMapOf<String, String>()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            val child = Regex("/collection_v2/([^/]+)\\.json$").find(url.path)
            if (requestMethod == "PUT" && child != null) {
                val copyId = URLDecoder.decode(child.groupValues[1], "UTF-8")
                val currentTag = if (records.containsKey(copyId)) "\"${versions[copyId]}\"" else "null_etag"
                if (headers["If-Match"] != currentTag) return 412
                records[copyId] = requestBody.toString()
                versions[copyId] = (versions[copyId] ?: 0) + 1
            }
            return 200
        }
        override fun getOutputStream() = requestBody
        override fun getInputStream(): ByteArrayInputStream {
            val body = when {
                url.host == "identitytoolkit.googleapis.com" ->
                    """{"idToken":"unit-id-token","localId":"unit-user","refreshToken":"unit-refresh-token"}"""
                url.host == "securetoken.googleapis.com" ->
                    """{"id_token":"fresh-unit-token"}"""
                url.path.endsWith("/collection_v2.json") -> if (records.isEmpty()) "null" else JSONObject().apply {
                    records.forEach { (key, value) -> put(key, JSONObject(value)) }
                }.toString()
                Regex("/collection_v2/([^/]+)\\.json$").containsMatchIn(url.path) -> {
                    val id = URLDecoder.decode(Regex("/collection_v2/([^/]+)\\.json$").find(url.path)!!.groupValues[1], "UTF-8")
                    records[id] ?: "null"
                }
                url.path.endsWith("/collection.json") ->
                    """[{"Page":1,"Slot":1,"Dex Number":25,"Name":"pikachu","Condition":"NM","Notes":""}]"""
                url.path.endsWith("/profile.json") -> "null"
                url.path.endsWith("/username.json") -> "\"Test Trainer\""
                else -> "null"
            }
            return ByteArrayInputStream(body.toByteArray())
        }
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getRequestProperty(key: String) = headers[key]
        fun requestProperty(key: String) = headers[key]
        override fun getHeaderField(name: String): String? {
            if (!name.equals("ETag", true)) return null
            val child = Regex("/collection_v2/([^/]+)\\.json$").find(url.path) ?: return null
            val id = URLDecoder.decode(child.groupValues[1], "UTF-8")
            return if (records.containsKey(id)) "\"${versions[id]}\"" else "null_etag"
        }
    }

    private class SessionConnection(
        url: URL,
        private val oldAccountFetched: CountDownLatch,
        private val releaseOldAccount: CountDownLatch
    ) : HttpURLConnection(url) {
        private val requestBody = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getOutputStream() = requestBody
        override fun getInputStream(): ByteArrayInputStream {
            val body = when {
                url.host == "identitytoolkit.googleapis.com" ->
                    if (requestBody.toString().contains("a@example.test"))
                        """{"idToken":"token-A","localId":"uid-A","refreshToken":"refresh-A"}"""
                    else
                        """{"idToken":"token-B","localId":"uid-B","refreshToken":"refresh-B"}"""
                url.path.endsWith("/collection.json") || url.path.endsWith("/collection_v2.json") -> {
                    when {
                        url.query?.contains("auth=token-A") == true -> {
                            oldAccountFetched.countDown()
                            check(releaseOldAccount.await(5, TimeUnit.SECONDS))
                            """[{"Page":1,"Slot":1,"Name":"card-A","Copy ID":"A"}]"""
                        }
                        url.query?.contains("auth=token-B") == true ->
                            """[{"Page":1,"Slot":1,"Name":"card-B","Copy ID":"B"}]"""
                        else -> "null"
                    }
                }
                url.path.endsWith("/profile.json") -> when {
                    url.query?.contains("auth=token-A") == true -> """{"username":"Trainer A"}"""
                    url.query?.contains("auth=token-B") == true -> """{"username":"Trainer B"}"""
                    else -> "null"
                }
                url.path.endsWith("/username.json") -> when {
                    url.query?.contains("auth=token-A") == true -> "\"Trainer A\""
                    url.query?.contains("auth=token-B") == true -> "\"Trainer B\""
                    else -> "null"
                }
                else -> "null"
            }
            return ByteArrayInputStream(body.toByteArray())
        }
    }

    private class MemoryDatabase(val rootPutBarrier: CountDownLatch? = null) {
        private val records = mutableMapOf<String, String>()
        private val versions = mutableMapOf<String, Int>()
        @Volatile private var legacyCollection = "null"
        @Volatile var failNextWriteAfterCommit = false
        @Volatile var mutateAfterNextRead: Pair<String, Pair<String, Any>>? = null

        fun connection(url: URL): HttpURLConnection = MemoryConnection(url, this)

        fun seed(card: Card) {
            val json = card.toJsonObject().toString()
            synchronized(records) {
                records[card.copyId] = json
                versions[card.copyId] = 1
                legacyCollection = JSONArray().put(card.toJsonObject()).toString()
            }
        }

        fun mutate(copyId: String, vararg fields: Pair<String, Any>) {
            synchronized(records) {
                val record = JSONObject(records.getValue(copyId))
                fields.forEach { (key, value) -> record.put(key, value) }
                records[copyId] = record.toString()
                versions[copyId] = versions.getValue(copyId) + 1
                val legacy = JSONArray(legacyCollection)
                for (index in 0 until legacy.length()) {
                    val card = legacy.optJSONObject(index) ?: continue
                    if (card.optString("Copy ID") == copyId) {
                        fields.forEach { (key, value) -> card.put(key, value) }
                    }
                }
                legacyCollection = legacy.toString()
            }
        }

        fun record(copyId: String): JSONObject? = synchronized(records) { records[copyId]?.let(::JSONObject) }

        fun allCopyIds(): Set<String> {
            val ids = synchronized(records) { records.keys.toMutableSet() }
            val root = legacyCollection
            if (root != "null") ids += Card.parseCollection(root).map { it.copyId }
            return ids
        }

        fun respond(url: URL, method: String, headers: Map<String, String>, body: String): MemoryResponse {
            if (url.host == "identitytoolkit.googleapis.com") {
                val email = JSONObject(body).optString("email")
                val suffix = when {
                    email.startsWith("a@") -> "A"
                    email.startsWith("b@") -> "B"
                    else -> "unit"
                }
                return MemoryResponse(200, """{"idToken":"token-$suffix","localId":"uid-$suffix","refreshToken":"refresh-$suffix"}""")
            }
            if (url.host == "securetoken.googleapis.com") return MemoryResponse(200, """{"id_token":"fresh-unit-token"}""")
            val child = Regex("/collection_v2/([^/]+)\\.json$").find(url.path)
            if (child != null) {
                val copyId = URLDecoder.decode(child.groupValues[1], "UTF-8")
                if (method == "GET") {
                    synchronized(records) {
                        val value = records[copyId]
                        val etag = if (value == null) "null_etag" else "\"${versions.getValue(copyId)}\""
                        if (mutateAfterNextRead?.first == copyId && value != null) {
                            val (field, changedValue) = mutateAfterNextRead!!.second
                            val changed = JSONObject(value).put(field, changedValue)
                            records[copyId] = changed.toString()
                            versions[copyId] = versions.getValue(copyId) + 1
                            mutateAfterNextRead = null
                        }
                        return MemoryResponse(200, value ?: "null", mapOf("ETag" to etag))
                    }
                }
                if (method == "PUT") {
                    synchronized(records) {
                        val value = records[copyId]
                        val etag = if (value == null) "null_etag" else "\"${versions.getValue(copyId)}\""
                        if (headers["If-Match"] != etag) return MemoryResponse(412, "ETAG_MISMATCH")
                        records[copyId] = body
                        versions[copyId] = (versions[copyId] ?: 0) + 1
                        if (failNextWriteAfterCommit) {
                            failNextWriteAfterCommit = false
                            return MemoryResponse(500, "offline")
                        }
                        return MemoryResponse(200, "null")
                    }
                }
            }
            if (url.path.endsWith("/collection_v2.json")) {
                val snapshot = synchronized(records) {
                    if (records.isEmpty()) "null" else JSONObject().apply {
                        records.forEach { (key, value) -> put(key, JSONObject(value)) }
                    }.toString()
                }
                return MemoryResponse(200, snapshot)
            }
            if (url.path.endsWith("/collection.json")) {
                if (method == "GET") return MemoryResponse(200, legacyCollection)
                if (method == "PUT") {
                    rootPutBarrier?.let {
                        it.countDown()
                        if (!it.await(5, TimeUnit.SECONDS)) return MemoryResponse(500, "parallel write timed out")
                    }
                    legacyCollection = body
                    if (failNextWriteAfterCommit) {
                        failNextWriteAfterCommit = false
                        return MemoryResponse(500, "offline")
                    }
                    return MemoryResponse(200, "null")
                }
            }
            if (url.path.endsWith("/profile.json") || url.path.endsWith("/username.json")) return MemoryResponse(200, "null")
            return MemoryResponse(404, "not found")
        }
    }

    private data class MemoryResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private class MemoryConnection(url: URL, private val database: MemoryDatabase) : HttpURLConnection(url) {
        private val headers = mutableMapOf<String, String>()
        private val requestBody = ByteArrayOutputStream()
        private var response: MemoryResponse? = null

        private fun response() = response ?: database.respond(url, requestMethod, headers, requestBody.toString()).also { response = it }
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = response().status
        override fun getInputStream() = ByteArrayInputStream(response().body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(response().body.toByteArray())
        override fun getOutputStream() = requestBody
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getRequestProperty(key: String) = headers[key]
        override fun getHeaderField(name: String) = response().headers[name]
    }
}
