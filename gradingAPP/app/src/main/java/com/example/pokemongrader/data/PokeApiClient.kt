package com.example.pokemongrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

object PokeApiClient {
    /** Cached list of all Pokémon names (fetched once, stored in-memory). */
    private var cachedNames: List<String>? = null

    fun binderPosition(dexNumber: Int): Pair<Int, Int> {
        require(dexNumber > 0)
        return ((dexNumber - 1) / 9 + 1) to ((dexNumber - 1) % 9 + 1)
    }

    internal fun nationalDexFromPokemon(json: JSONObject): Int {
        val speciesUrl = json.optJSONObject("species")?.optString("url").orEmpty()
        return Regex("/pokemon-species/(\\d+)/?$").find(speciesUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun normalizePokemonName(name: String): String {
        val normalized = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        return buildString {
            for (character in normalized) {
                when {
                    Character.getType(character) == Character.NON_SPACING_MARK.toInt() -> Unit
                    character.isLetterOrDigit() || character == '♀' || character == '♂' -> append(character)
                    else -> append(' ')
                }
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    suspend fun fetchDexNumber(name: String): Int = withContext(Dispatchers.IO) {
        val slug = pokemonLookupSlug(name)
        if (slug.isEmpty()) return@withContext 0
        queryPokeApi(slug)
    }

    internal fun pokemonLookupSlug(name: String): String {
        val parts = normalizePokemonName(name).split(' ').filter(String::isNotBlank).toMutableList()
        while (parts.lastOrNull() in setOf("ex", "gx", "v", "vmax", "vstar", "break")) parts.removeAt(parts.lastIndex)
        if (parts.firstOrNull() in setOf("m", "mega")) parts.removeAt(0)
        if (parts.lastOrNull() in setOf("x", "y") && parts.getOrNull(parts.lastIndex - 1) == "mega") parts.removeAt(parts.lastIndex)
        if (parts.size == 1 && parts[0] == "nidoran♀") return "nidoran-f"
        if (parts.size == 1 && parts[0] == "nidoran♂") return "nidoran-m"
        if (parts.size == 2 && parts[0] == "farfetch" && parts[1] == "d") return "farfetchd"
        val regionalForms = mapOf("alolan" to "alola", "galarian" to "galar", "hisuian" to "hisui", "paldean" to "paldea")
        val region = regionalForms[parts.firstOrNull()]
        if (region != null && parts.size > 1) return (parts.drop(1) + region).joinToString("-")
        return parts.joinToString("-")
    }

    private fun queryPokeApi(slug: String): Int {
        try {
            val encodedSlug = java.net.URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
            val url = URL("https://pokeapi.co/api/v2/pokemon/$encodedSlug")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val res = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(res)
                return nationalDexFromPokemon(json)
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
            val url = URL("https://pokeapi.co/api/v2/pokemon?limit=20000&offset=0")
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
                        val region = n.substringAfterLast('-')
                        val regionName = mapOf("alola" to "Alolan", "galar" to "Galarian", "hisui" to "Hisuian", "paldea" to "Paldean")[region]
                        val display = if (regionName != null && '-' in n) {
                            "$regionName " + n.substringBeforeLast('-').split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                        } else n.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                        names.add(display)
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
        val q = normalizePokemonName(query)
        if (q.length < 2) return emptyList()
        return allNames.filter { normalizePokemonName(it).contains(q) }.take(limit)
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
