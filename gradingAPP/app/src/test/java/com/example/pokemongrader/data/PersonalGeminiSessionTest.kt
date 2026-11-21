package com.example.pokemongrader.data

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class PersonalGeminiSessionTest {
    private val key = "fake-test-key-0000000000000000000000"

    @Test
    fun restoresAfterRestartAndSignInButNeverSharesAnotherAccountsKey() = runTest {
        val store = MemoryKeyStore()
        val initial = PersonalGeminiSession(store)
        initial.selectAccount("account-a")
        assertFalse(initial.activate("bad\r\nInjected: value"))
        assertTrue(initial.activate(" $key "))
        assertEquals(key, store.load("account-a"))

        val restarted = PersonalGeminiSession(store)
        restarted.selectAccount("account-a")
        restarted.restore()
        assertNotNull(restarted.client.value)
        restarted.selectAccount(null)
        assertNull(restarted.client.value)
        assertEquals(key, store.load("account-a"))
        restarted.selectAccount("account-b")
        restarted.restore()
        assertNull(restarted.client.value)
        assertTrue(restarted.activate("fake-test-key-1111111111111111111111"))
        restarted.selectAccount("account-a")
        restarted.restore()
        assertNotNull(restarted.client.value)
        restarted.remove()
        assertNull(restarted.client.value)
        assertNull(store.load("account-a"))
        assertNotNull(store.load("account-b"))
    }

    @Test
    fun failedSaveDoesNotActivateOrReplaceTheKey() = runTest {
        val storage = MemoryKeyStore()
        val session = PersonalGeminiSession(storage)
        session.selectAccount("account-a")
        assertTrue(session.activate(key))
        val active = session.client.value
        storage.failSave = true
        val failure = runCatching { session.activate("fake-test-key-1111111111111111111111") }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertSame(active, session.client.value)
        assertEquals(key, storage.load("account-a"))
    }

    @Test
    fun slowRestoreCannotActivateThePreviousAccountAfterSwitching() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = object : GeminiKeyStore {
            override fun load(uid: String): String? {
                if (uid != "account-a") return null
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return key
            }
            override fun save(uid: String, key: String) = Unit
            override fun remove(uid: String) = Unit
        }
        val session = PersonalGeminiSession(store)
        session.selectAccount("account-a")
        val restoring = async { session.restore() }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            session.selectAccount("account-b")
        } finally {
            release.countDown()
        }
        restoring.await()
        assertNull(session.client.value)
    }
}

internal class MemoryKeyStore : GeminiKeyStore {
    private val keys = mutableMapOf<String, String>()
    var failSave = false
    override fun load(uid: String): String? = keys[uid]
    override fun save(uid: String, key: String) {
        if (failSave) throw IOException("test write failure")
        keys[uid] = key
    }
    override fun remove(uid: String) {
        keys.remove(uid)
    }
}
