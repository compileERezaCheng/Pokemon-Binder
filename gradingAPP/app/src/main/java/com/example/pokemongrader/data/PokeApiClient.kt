package com.example.pokemongrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object PokeApiClient {
    suspend fun fetchDexNumber(name: String): Int = withContext(Dispatchers.IO) {
        try {
            val cleanName = name.lowercase().trim().replace(" ", "-")
            val url = URL("https://pokeapi.co/api/v2/pokemon/$cleanName")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(res)
                json.getInt("id")
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
}
