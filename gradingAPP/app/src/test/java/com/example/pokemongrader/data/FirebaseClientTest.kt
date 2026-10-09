package com.example.pokemongrader.data

import kotlinx.coroutines.test.runTest
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
}
