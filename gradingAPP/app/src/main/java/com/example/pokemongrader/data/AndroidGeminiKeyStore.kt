package com.example.pokemongrader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidGeminiKeyStore(context: Context) : GeminiKeyStore {
    private val directory = context.applicationContext.noBackupFilesDir

    override fun load(uid: String): String? = synchronized(storageLock) {
        val encrypted = try {
            keyFile(uid).readFully()
        } catch (_: FileNotFoundException) {
            return@synchronized null
        }
        if (encrypted.size !in 29..512) throw IOException("Invalid encrypted key file.")
        val key = androidKeyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return@synchronized null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 0, IV_SIZE))
        cipher.updateAAD(uid.toByteArray(Charsets.UTF_8))
        cipher.doFinal(encrypted, IV_SIZE, encrypted.size - IV_SIZE).toString(Charsets.UTF_8)
    }

    override fun save(uid: String, key: String) = synchronized(storageLock) {
        val secret = (androidKeyStore().getKey(KEY_ALIAS, null) as? SecretKey) ?: generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        check(cipher.iv.size == IV_SIZE)
        cipher.updateAAD(uid.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val target = keyFile(uid)
        val stream = target.startWrite()
        try {
            stream.write(encrypted)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    override fun remove(uid: String) = synchronized(storageLock) {
        val target = keyFile(uid)
        target.delete()
        if (target.baseFile.exists()) throw IOException("Couldn't remove the saved Gemini key.")
    }

    private fun keyFile(uid: String): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return AtomicFile(File(directory, "gemini-key-$hash.bin"))
    }

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        generateKey()
    }

    private companion object {
        val storageLock = Any()
        const val KEY_ALIAS = "pokebinder.personalGemini.v1"
        const val IV_SIZE = 12
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
