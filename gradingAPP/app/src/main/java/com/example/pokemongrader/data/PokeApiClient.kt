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

    fun normalizePokemonName(name: String): String {
        var n = name.lowercase().trim()
        // 1. Remove text inside parentheses (e.g. "Pikachu (Japanese version)" -> "Pikachu")
        n = n.replace(Regex("\\([^)]*\\)"), "")
        // 2. Remove language suffixes (e.g. "Pikachu Japanese", "Pikachu JP", "Pikachu Japanese version")
        val langRegex = Regex("\\b(japanese|french|spanish|german|italian|korean|chinese|english|jp|en|es|fr|de|kr|cn)(\\s+version)?\\b")
        n = n.replace(langRegex, "")
        // 3. Clean up extra spaces
        n = n.replace(Regex("\\s+"), " ").trim()
        return n
    }

    suspend fun fetchDexNumber(name: String): Int = withContext(Dispatchers.IO) {
        val normalized = normalizePokemonName(name)
        if (normalized.isEmpty()) return@withContext 0
        
        // Try full normalized name
        var dex = queryPokeApi(normalized)
        if (dex > 0) return@withContext dex
        
        // Fallback: if it has multiple words (e.g. "charizard ex"), try the first word
        val words = normalized.split(" ", "-")
        if (words.size > 1) {
            dex = queryPokeApi(words[0])
            if (dex > 0) return@withContext dex
        }
        
        0
    }

    private fun queryPokeApi(name: String): Int {
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
                return json.getInt("id")
            }
        } catch (e: Exception) {
            // Ignore
        }
        return 0
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

    /**
     * Fetches card candidates from the Pokémon TCG API matching the Pokémon name.
     */
    suspend fun fetchCardsByName(name: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val list = mutableListOf<JSONObject>()
        val normalized = normalizePokemonName(name)
        if (normalized.isEmpty()) return@withContext list
        try {
            val encodedName = java.net.URLEncoder.encode("\"$normalized\"", "UTF-8")
            val url = URL("https://api.pokemontcg.io/v2/cards?q=name:$encodedName")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(res)
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        list.add(data.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    private var dynamicSetMap: Map<String, String>? = null

    /**
     * Dynamically fetches all set definitions from the Pokémon TCG API.
     * Caches them in memory after the first load.
     */
    suspend fun fetchAllSets(): Map<String, String> = withContext(Dispatchers.IO) {
        dynamicSetMap?.let { return@withContext it }
        val map = mutableMapOf<String, String>()
        try {
            val url = URL("https://api.pokemontcg.io/v2/sets")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(res)
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val setObj = data.getJSONObject(i)
                        val code = setObj.optString("ptcgoCode", "").uppercase().trim()
                        val name = setObj.optString("name", "").trim()
                        if (code.isNotEmpty() && name.isNotEmpty()) {
                            map[code] = name
                        }
                        // Also map by set ID just in case
                        val id = setObj.optString("id", "").uppercase().trim()
                        if (id.isNotEmpty() && name.isNotEmpty()) {
                            map[id] = name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        dynamicSetMap = map
        map
    }
}
