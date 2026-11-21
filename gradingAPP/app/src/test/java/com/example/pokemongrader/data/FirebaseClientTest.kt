package com.example.pokemongrader.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class FirebaseClientTest {
    private val originalFactory = FirebaseClient.connectionFactory

    @After
    fun restoreConnectionFactory() {
        FirebaseClient.connectionFactory = originalFactory
    }

    @Test
    fun loginReadsAuthTokensThroughMockedConnection() = runTest {
        val connection = StubConnection(
            URL("https://identitytoolkit.googleapis.com"),
            """{"idToken":"unit-id-token","localId":"unit-user","refreshToken":"unit-refresh-token"}"""
        )
        var requestedUrl: URL? = null
        FirebaseClient.connectionFactory = { requestedUrl = it; connection }

        val result = FirebaseClient.signIn("unit@example.test", "test-password")

        assertEquals(AuthResult("unit-id-token", "unit-user", "unit-refresh-token"), result)
        assertEquals("identitytoolkit.googleapis.com", requestedUrl?.host)
        assertTrue(connection.requestBody.toString().contains("unit@example.test"))
    }

    @Test
    fun sessionRefreshReturnsTheNewIdTokenThroughMockedConnection() = runTest {
        val connection = StubConnection(
            URL("https://securetoken.googleapis.com"),
            """{"id_token":"refreshed-unit-token"}"""
        )
        FirebaseClient.connectionFactory = { connection }

        val token = FirebaseClient.refreshIdToken("unit-refresh-token")

        assertEquals("refreshed-unit-token", token)
        assertTrue(connection.requestBody.toString().contains("refresh_token=unit-refresh-token"))
    }

    @Test
    fun legacyCardsGetTheSameStableCopyIdAsTheWindowsClient() {
        val card = Card.parseCollection(
            """[{"Page":1,"Slot":2,"Dex Number":25,"Name":"pikachu","Type":"Normal","Condition":"NM","Notes":"","Date Added":"unit-date"}]"""
        ).single()

        assertEquals("legacy-aec391c70be7b34ec552adaecb2a8ad0", card.copyId)
    }

    @Test
    fun collectionWritesUseConditionalPerCopyPaths() = runTest {
        val requests = mutableListOf<RecordingConnection>()
        FirebaseClient.databaseUrl = "https://firebase.test"
        FirebaseClient.connectionFactory = { RecordingConnection(it).also(requests::add) }
        val cards = listOf(
            Card(1, 1, 25, "pikachu", "Normal", "NM", "", "today", copyId = "copy-a"),
            Card(1, 2, 25, "pikachu", "Normal", "NM", "", "today", copyId = "copy-b")
        )

        FirebaseClient.saveCollection("unit-user", "unit-token", cards)

        val writes = requests.filter { it.requestMethod == "PUT" }
        assertEquals(2, writes.size)
        assertEquals(
            setOf("/users/unit-user/collection_v2/copy-a.json", "/users/unit-user/collection_v2/copy-b.json"),
            writes.map { it.url.path }.toSet()
        )
        assertTrue(writes.all { it.requestProperty("If-Match") == "null_etag" })
        assertTrue(requests.none { it.requestMethod == "PUT" && it.url.path.endsWith("/collection.json") })
    }

    @Test
    fun firebaseWritePreservesLocalizedPrintingAndImageFields() = runTest {
        val requests = mutableListOf<RecordingConnection>()
        FirebaseClient.databaseUrl = "https://firebase.test"
        FirebaseClient.connectionFactory = { RecordingConnection(it).also(requests::add) }
        val card = Card(
            page = 64, slot = 3, dexNumber = 570, name = "ヒスイゾロア", type = "Rare", condition = "NM",
            notes = "fixture", dateAdded = "today", copyId = "copy-ja-1", speciesName = "Zorua",
            form = "Hisuian", cardLanguage = "ja", catalogProvider = "TCGdex", catalogLocale = "ja",
            catalogCardId = "sv10-1", setId = "sv10", setName = "Black Bolt", cardNumber = "1",
            catalogVariant = "normal", catalogRarity = "Common",
            imageUrl = "https://assets.tcgdex.net/ja/sv/sv10/1/low.webp", imageSource = "TCGdex"
        )

        FirebaseClient.saveCollection("unit-user", "unit-token", listOf(card))

        val payload = JSONObject(requests.single { it.requestMethod == "PUT" }.body())
        assertEquals("ヒスイゾロア", payload.optString("Name"))
        assertEquals("Zorua", payload.optString("Species Name"))
        assertEquals("Hisuian", payload.optString("Form"))
        assertEquals("ja", payload.optString("Card Language"))
        assertEquals("TCGdex", payload.optString("Catalog Provider"))
        assertEquals("ja", payload.optString("Catalog Locale"))
        assertEquals("sv10-1", payload.optString("Catalog Card ID"))
        assertEquals("sv10", payload.optString("Set ID"))
        assertEquals("Black Bolt", payload.optString("Set Name"))
        assertEquals("1", payload.optString("Card Number"))
        assertEquals("normal", payload.optString("Catalog Variant"))
        assertEquals("Common", payload.optString("Catalog Rarity"))
        assertEquals("https://assets.tcgdex.net/ja/sv/sv10/1/low.webp", payload.optString("Image URL"))
        assertEquals("TCGdex", payload.optString("Image Source"))
    }

    @Test
    fun collectionFetchChecksTheVersionedPathBeforeLegacyData() = runTest {
        val requests = mutableListOf<RecordingConnection>()
        FirebaseClient.databaseUrl = "https://firebase.test"
        FirebaseClient.connectionFactory = { RecordingConnection(it).also(requests::add) }

        FirebaseClient.fetchCollection("unit-user", "unit-token")

        assertTrue(requests.first().url.path.endsWith("/collection_v2.json"))
    }

    private class StubConnection(
        url: URL,
        private val body: String,
        private val status: Int = 200,
        private val error: String = ""
    ) : HttpURLConnection(url) {
        val requestBody = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(error.toByteArray())
        override fun getOutputStream() = requestBody
    }

    private class RecordingConnection(url: URL) : HttpURLConnection(url) {
        private val headers = mutableMapOf<String, String>()
        private val requestBody = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream("null".toByteArray())
        override fun getOutputStream() = requestBody
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getRequestProperty(key: String) = headers[key]
        override fun getHeaderField(name: String) = if (name.equals("ETag", true)) "null_etag" else null
        fun requestProperty(key: String) = headers[key]
        fun body() = requestBody.toString("UTF-8")
    }
}
