package com.example.pokemongrader.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class DataRepositoryTest {
    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private val originalFactory = FirebaseClient.connectionFactory
    private val originalDatabaseUrl = FirebaseClient.databaseUrl
    private val connections = mutableListOf<StubConnection>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        prefs = context.getSharedPreferences("PokeGraderPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        FirebaseClient.databaseUrl = "https://firebase.test"
        FirebaseClient.connectionFactory = { url -> StubConnection(url).also(connections::add) }
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
    fun loginLoadsCollectionAndCardWritesStayInTheMock() = runBlocking {
        val repository = DefaultDataRepository(context)

        assertTrue(repository.login("unit@example.test", "test-password"))
        assertEquals("unit-user", prefs.getString("uid", null))
        assertEquals("unit-refresh-token", prefs.getString("refresh_token", null))
        assertEquals("pikachu", repository.cards.value.single().name)

        val added = Card(1, 2, 0, "mew", "Normal", "NM", "", "unit-date")
        assertTrue(repository.addCard(added))
        assertEquals(2, repository.cards.value.size)
        assertTrue(connections.any { it.requestMethod == "PUT" && it.requestBody.toString().contains("\"Name\":\"mew\"") })

        assertTrue(repository.removeCard(added))
        assertEquals(1, repository.cards.value.size)
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
            StubConnection(url).also(connections::add)
        }

        assertTrue(repository.refreshAndFetch())
        assertTrue(loadingDuringTokenRefresh.get())
    }

    private class StubConnection(url: URL) : HttpURLConnection(url) {
        val requestBody = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getOutputStream() = requestBody
        override fun getInputStream(): ByteArrayInputStream {
            val body = when {
                url.host == "identitytoolkit.googleapis.com" ->
                    """{"idToken":"unit-id-token","localId":"unit-user","refreshToken":"unit-refresh-token"}"""
                url.host == "securetoken.googleapis.com" ->
                    """{"id_token":"fresh-unit-token"}"""
                url.path.endsWith("/collection.json") ->
                    """[{"Page":1,"Slot":1,"Dex Number":25,"Name":"pikachu","Condition":"NM","Notes":""}]"""
                url.path.endsWith("/profile.json") -> "null"
                url.path.endsWith("/username.json") -> "\"Test Trainer\""
                else -> "null"
            }
            return ByteArrayInputStream(body.toByteArray())
        }
    }
}
