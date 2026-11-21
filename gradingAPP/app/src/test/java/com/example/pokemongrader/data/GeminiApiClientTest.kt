package com.example.pokemongrader.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiApiClientTest {
    private val key = "fake-test-key-0000000000000000000000"

    @Test
    fun sendsCardPhotosAndPromptOnlyToGoogleAndKeepsKeyInHeader() {
        lateinit var request: RecordingConnection
        val client = GeminiApiClient(key) { url ->
            RecordingConnection(url, 200, """{"candidates":[{"content":{"parts":[
                {"text":"hidden reasoning","thought":true},{"text":"{\"name\":\"Pikachu\"}"}
            ]}}]}""").also { request = it }
        }

        assertEquals("Pikachu", JSONObject(client.generateContent("Identify", listOf("YQ==", "Yg=="))).getString("name"))
        assertEquals("https", request.url.protocol)
        assertEquals("generativelanguage.googleapis.com", request.url.host)
        assertTrue(request.url.path.endsWith(":generateContent"))
        assertNull(request.url.query)
        assertEquals("POST", request.requestMethod)
        assertEquals(key, request.headers["x-goog-api-key"])
        assertFalse(request.instanceFollowRedirects)
        assertTrue(request.closed)
        val body = request.payload.toString(Charsets.UTF_8.name())
        assertFalse(body.contains(key))
        val parts = JSONObject(body).getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("image/jpeg", parts.getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("Yg==", parts.getJSONObject(1).getJSONObject("inlineData").getString("data"))
        assertEquals("Identify", parts.getJSONObject(2).getString("text"))
    }

    @Test
    fun deniedAndRateLimitedRequestsDoNotExposeTheKeyOrRetry() {
        for (status in listOf(403, 429, 302)) {
            val response = JSONObject().put("error", JSONObject().put("message", "sensitive detail $key"))
            var opens = 0
            val client = GeminiApiClient(key) { url ->
                opens++
                RecordingConnection(url, status, response.toString())
            }
            val error = assertThrows(IOException::class.java) {
                client.generateContent("Identify", listOf("YQ=="))
            }
            assertFalse(error.message.orEmpty().contains(key))
            assertTrue(error.message.orEmpty().contains("manual", ignoreCase = true))
            assertEquals(1, opens)
        }
    }

    @Test
    fun malformedSuccessIsReportedWithoutRawProviderContent() {
        val client = GeminiApiClient(key) { RecordingConnection(it, 200, "bad body: $key") }
        val error = assertThrows(IOException::class.java) {
            client.generateContent("Identify", listOf("YQ=="))
        }
        assertFalse(error.message.orEmpty().contains(key))
    }

    private class RecordingConnection(url: URL, private val status: Int, private val body: String) :
        HttpURLConnection(url) {
        val payload = ByteArrayOutputStream()
        val headers = mutableMapOf<String, String>()
        var closed = false
            private set
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = payload
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
    }
}
