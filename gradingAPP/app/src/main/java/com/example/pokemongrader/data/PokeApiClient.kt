package com.example.pokemongrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object PokeApiClient {
    /** Cached list of all Pokémon names (fetched once, stored in-memory). */
    private var cachedNames: List<String>? = null

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

    /**
     * Fetches and caches all Pokémon names (≈1025) from PokeAPI.
     * Returns the cached list on subsequent calls.
     */
    suspend fun fetchAllNames(): List<String> = withContext(Dispatchers.IO) {
        cachedNames?.let { return@withContext it }
        try {
            val url = URL("https://pokeapi.co/api/v2/pokemon?limit=1025&offset=0")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode == 200) {
                val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(res)
                val results = json.getJSONArray("results")
                val names = mutableListOf<String>()
                for (i in 0 until results.length()) {
                    val n = results.getJSONObject(i).optString("name", "")
                    if (n.isNotEmpty()) {
                        // Convert api-name to display name: "mr-mime" -> "Mr Mime"
                        names.add(n.split("-").joinToString(" ") { w ->
                            w.replaceFirstChar { it.uppercase() }
                        })
                    }
                }
                cachedNames = names
                names
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Filters the cached name list by the given query (case-insensitive prefix match).
     * Returns up to [limit] results.
     */
    fun searchPokemon(query: String, allNames: List<String>, limit: Int = 6): List<String> {
        if (query.length < 2) return emptyList()
        val q = query.trim().lowercase()
        return allNames.filter { it.lowercase().contains(q) }.take(limit)
    }
}
