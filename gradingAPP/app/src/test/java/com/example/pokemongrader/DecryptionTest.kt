package com.example.pokemongrader

import org.junit.Test

class DecryptionTest {
    @Test
    fun testDecrypt() {
        val encrypted = "ESYRBBQLI0kiMGQLMwYRAwMUMwBAUAcePFwjdiIvLEhBMlYtJBE8"
        val KEY = "PokeGraderSecureKey2026"
        val decodedBytes = java.util.Base64.getDecoder().decode(encrypted)
        val decodedStr = String(decodedBytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        for (i in decodedStr.indices) {
            sb.append((decodedStr[i].code xor KEY[i % KEY.length].code).toChar())
        }
        val result = sb.toString()
        println("DECRYPTED RESULT: $result")
    }
}
