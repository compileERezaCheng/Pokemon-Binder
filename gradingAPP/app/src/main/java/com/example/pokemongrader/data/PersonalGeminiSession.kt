package com.example.pokemongrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface GeminiKeyStore {
    fun load(uid: String): String?
    fun save(uid: String, key: String)
    fun remove(uid: String)
}

class PersonalGeminiSession internal constructor(private val keys: GeminiKeyStore) {
    private class Account(val uid: String)
    private val operations = Mutex()
    @Volatile private var account: Account? = null
    private val current = MutableStateFlow<GeminiApiClient?>(null)
    internal val client = current.asStateFlow()

    @Synchronized
    internal fun selectAccount(uid: String?) {
        account = uid?.takeIf { it.isNotBlank() }?.let(::Account)
        current.value = null
    }

    internal suspend fun restore() {
        update { uid -> runCatching { keys.load(uid) }.getOrNull()?.takeIf(::validKey) }
    }

    internal suspend fun activate(value: String): Boolean {
        val key = value.trim()
        if (!validKey(key)) return false
        return update { uid -> keys.save(uid, key); key }
    }

    internal suspend fun remove(): Boolean = update { uid -> keys.remove(uid); null }

    private suspend fun update(change: (String) -> String?): Boolean {
        val selected = account ?: return false
        return withContext(Dispatchers.IO) {
            operations.withLock {
                if (account !== selected) return@withLock false
                val key = change(selected.uid)
                synchronized(this@PersonalGeminiSession) {
                    if (account !== selected) false else {
                        current.value = key?.let { GeminiApiClient(it) }
                        true
                    }
                }
            }
        }
    }

    private fun validKey(key: String): Boolean = key.matches(Regex("[A-Za-z0-9_-]{20,256}"))
}
