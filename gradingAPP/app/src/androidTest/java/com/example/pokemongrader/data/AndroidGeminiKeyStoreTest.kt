package com.example.pokemongrader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AndroidGeminiKeyStoreTest {
    @Test
    fun storesEncryptedDataOutsideBackupsAndRetrievesItFromANewInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = UUID.randomUUID().toString()
        val key = "fake-test-key-1234567890123456789012"
        val store = AndroidGeminiKeyStore(context)
        val before = context.noBackupFilesDir.listFiles().orEmpty().toSet()
        try {
            store.save(owner, key)
            val files = context.noBackupFilesDir.listFiles().orEmpty().toSet() - before
            assertEquals(1, files.size)
            assertFalse(files.single().readBytes().decodeToString().contains(key))
            assertEquals(key, AndroidGeminiKeyStore(context).load(owner))
            assertNull(store.load(UUID.randomUUID().toString()))
            store.remove(owner)
            assertNull(AndroidGeminiKeyStore(context).load(owner))
        } finally {
            store.remove(owner)
        }
    }
}
