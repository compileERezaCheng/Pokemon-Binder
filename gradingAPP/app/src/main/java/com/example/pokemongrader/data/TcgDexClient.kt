package com.example.pokemongrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CatalogCard(
    val id: String,
    val name: String,
    val setId: String,
    val setName: String,
    val number: String,
    val rarity: String,
    val imageUrl: String
)

object TcgDexClient {
    private const val API = "https://api.tcgdex.net/v2/en/"
    @Volatile private var cachedSets: Map<String, String> = emptyMap()

    internal fun imageUrlFromCards(json: String): String? {
        val cards = JSONArray(json)
        if (cards.length() != 1) return null
        return cards.optJSONObject(0)?.optString("image")?.let(::catalogImage)
    }

    internal fun parseBriefCards(json: String, setNames: Map<String, String> = emptyMap()): List<CatalogCard> {
        val cards = JSONArray(json)
        return buildList {
            for (i in 0 until cards.length()) {
                val item = cards.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val number = item.optString("localId")
                val name = item.optString("name")
                if (id.isBlank() || number.isBlank() || name.isBlank()) continue
                val setId = id.removeSuffix("-$number").takeIf { it != id } ?: id.substringBeforeLast('-')
                add(
                    CatalogCard(
                        id = id,
                        name = name,
                        setId = setId,
                        setName = setNames[setId] ?: setId,
                        number = number,
                        rarity = "",
                        imageUrl = catalogImage(item.optString("image")).orEmpty()
                    )
                )
            }
        }
    }

    internal fun parseCardDetails(json: String): CatalogCard? {
        val card = JSONObject(json)
        val set = card.optJSONObject("set") ?: return null
        val id = card.optString("id")
        val name = card.optString("name")
        val number = card.optString("localId")
        val setId = set.optString("id")
        val setName = set.optString("name")
        if (id.isBlank() || name.isBlank() || number.isBlank() || setId.isBlank() || setName.isBlank()) return null
        return CatalogCard(
            id = id,
            name = name,
            setId = setId,
            setName = setName,
            number = number,
            rarity = card.optString("rarity"),
            imageUrl = catalogImage(card.optString("image")).orEmpty()
        )
    }

    internal fun uniqueCard(
        cards: List<CatalogCard>,
        name: String,
        setName: String = "",
        cardNumber: String = ""
    ): CatalogCard? {
        val number = cardNumber.substringBefore('/').trim()
        return cards.filter {
            it.name.equals(name.trim(), ignoreCase = true) &&
                (setName.isBlank() || it.setName.equals(setName.trim(), ignoreCase = true)) &&
                (number.isBlank() || it.number.equals(number, ignoreCase = true))
        }.singleOrNull()
    }

    suspend fun searchCards(name: String, setName: String = "", cardNumber: String = ""): List<CatalogCard> =
        withContext(Dispatchers.IO) {
            if (name.isBlank()) return@withContext emptyList()
            val number = cardNumber.substringBefore('/').trim()
            val filters = buildList {
                add("name=" + encode(name))
                if (setName.isNotBlank()) add("set.name=" + encode(setName))
                if (number.isNotBlank()) add("localId=" + encode("eq:$number"))
            }
            var result = request("cards?" + filters.joinToString("&"))
            if (JSONArray(result).length() == 0 && (setName.isNotBlank() || number.isNotBlank())) {
                result = request("cards?name=" + encode(name))
            }
            val setNames = runCatching { fetchSetNames() }.getOrDefault(emptyMap())
            val candidates = parseBriefCards(result, setNames)
            val exact = candidates.filter { it.name.equals(name.trim(), ignoreCase = true) }
            exact.ifEmpty { candidates }
        }

    suspend fun fetchCard(id: String): CatalogCard? = withContext(Dispatchers.IO) {
        parseCardDetails(request("cards/" + encode(id)))
    }

    suspend fun findImage(name: String, setName: String, cardNumber: String): String? {
        val candidates = searchCards(name, setName, cardNumber)
        val selected = uniqueCard(candidates, name, setName, cardNumber) ?: return null
        return runCatching { fetchCard(selected.id) }.getOrNull()?.imageUrl?.takeIf { it.isNotBlank() }
            ?: selected.imageUrl.takeIf { it.isNotBlank() }
    }

    private fun fetchSetNames(): Map<String, String> {
        cachedSets.takeIf { it.isNotEmpty() }?.let { return it }
        val sets = JSONArray(request("sets"))
        val names = buildMap {
            for (i in 0 until sets.length()) {
                val set = sets.optJSONObject(i) ?: continue
                val id = set.optString("id")
                val name = set.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) put(id, name)
            }
        }
        if (names.isNotEmpty()) cachedSets = names
        return names
    }

    private fun request(path: String): String {
        val connection = URL(API + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            if (connection.responseCode !in 200..299) throw IOException("TCGdex HTTP " + connection.responseCode)
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun catalogImage(value: String): String? {
        if (!value.startsWith("https://assets.tcgdex.net/")) return null
        val base = value.trimEnd('/')
        return if (base.endsWith(".webp") || base.endsWith(".png") || base.endsWith(".jpg")) base else "$base/low.webp"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
