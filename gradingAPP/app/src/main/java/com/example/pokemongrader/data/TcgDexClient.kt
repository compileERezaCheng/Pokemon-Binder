package com.example.pokemongrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

data class CatalogCard(
    val id: String,
    val name: String,
    val setId: String,
    val setName: String,
    val number: String,
    val rarity: String,
    val imageUrl: String,
    val imageSource: String = "",
    val imageMatchId: String = ""
)

data class ScrydexImageMatch(val cardId: String, val imageUrl: String)

object TcgDexClient {
    private const val API = "https://api.tcgdex.net/v2/en/"
    private const val SCRYDEX_API = "https://api.scrydex.com/pokemon/v1/en/cards"
    private val CARD_IMAGE = Regex("""https://assets\.tcgdex\.net/[a-z-]+/[^/?#]+/[^/?#]+/([^/?#.]+)(?:/(?:low|high)\.(?:webp|png|jpg))?""")
    @Volatile private var cachedSets: Map<String, String> = emptyMap()
    private data class CachedMatch(val value: ScrydexImageMatch?)
    private val alternateImageCache = ConcurrentHashMap<String, CachedMatch>()

    internal fun imageUrlFromCards(json: String): String? {
        val cards = JSONArray(json)
        if (cards.length() != 1) return null
        return cards.optJSONObject(0)?.optString("image")?.let(::cardImageUrl)
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
                        imageUrl = cardImageUrl(item.optString("image")).orEmpty(),
                        imageSource = if (cardImageUrl(item.optString("image")).isNullOrBlank()) "" else "TCGdex"
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
            imageUrl = cardImageUrl(card.optString("image")).orEmpty(),
            imageSource = if (cardImageUrl(card.optString("image")).isNullOrBlank()) "" else "TCGdex"
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

    internal fun sortBySetName(cards: List<CatalogCard>): List<CatalogCard> =
        cards.sortedBy { normalize(it.setName) }

    internal fun filterBySetName(cards: List<CatalogCard>, query: String): List<CatalogCard> {
        val term = normalize(query)
        return if (term.isBlank()) cards else cards.filter { normalize(it.setName).contains(term) }
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
            val exact = candidates.filter { isPokemonCardNameMatch(it.name, name) }
            sortBySetName(exact.ifEmpty { candidates })
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

    internal fun isPokemonCardNameMatch(cardName: String, pokemonName: String): Boolean {
        val target = normalize(cardName = pokemonName).split(" ").filter(String::isNotBlank)
        val tokens = normalize(cardName).split(" ").filter(String::isNotBlank).toMutableList()
        if (target.isEmpty() || tokens.isEmpty()) return false
        val mega = tokens.first() in setOf("m", "mega")
        if (mega) tokens.removeAt(0)
        if (tokens.lastOrNull() in setOf("ex", "gx", "v", "vmax", "vstar", "break")) tokens.removeAt(tokens.lastIndex)
        if (mega && tokens.lastOrNull() in setOf("x", "y")) tokens.removeAt(tokens.lastIndex)
        return tokens == target
    }

    internal fun matchScrydexImage(tcgdexJson: String, scrydexJson: String): ScrydexImageMatch? {
        return try {
            val tcgdex = JSONObject(tcgdexJson)
            val setName = normalize(tcgdex.optJSONObject("set")?.optString("name").orEmpty())
            val number = numberKey(tcgdex.optString("localId"))
            val identity = cardIdentity(tcgdex, scrydex = false)
            val data = JSONObject(scrydexJson)
            val cards = data.optJSONArray("data") ?: return null
            if (setName.isBlank() || number.isBlank() || identity.first.isBlank()) return null
            if (data.has("totalCount") && data.optInt("totalCount", Int.MAX_VALUE) > cards.length()) return null

            val matches = mutableListOf<JSONObject>()
            for (index in 0 until cards.length()) {
                val candidate = cards.optJSONObject(index) ?: continue
                val expansion = candidate.optJSONObject("expansion") ?: JSONObject()
                val language = candidate.optString("language_code").ifBlank { expansion.optString("language_code") }
                val numbers = listOf(candidate.optString("number"), candidate.optString("printed_number"))
                    .filter(String::isNotBlank).map(::numberKey)
                if (
                    normalize(expansion.optString("name")) == setName &&
                    normalize(language) == "en" && numbers.isNotEmpty() && numbers.distinct().size == 1 &&
                    numbers.first() == number && cardIdentity(candidate, scrydex = true) == identity
                ) matches += candidate
            }
            val candidate = matches.singleOrNull() ?: return null
            val cardId = candidate.optString("id")
            val images = candidate.optJSONArray("images") ?: return null
            val front = (0 until images.length()).mapNotNull { images.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "front" } ?: return null
            val image = front.optString("large")
            val parsed = URL(image)
            if (cardId.isBlank() || parsed.protocol != "https" || parsed.host != "images.scrydex.com" || parsed.path != "/pokemon/$cardId/large") return null
            ScrydexImageMatch(cardId, image)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveAlternateImage(card: CatalogCard): ScrydexImageMatch? = withContext(Dispatchers.IO) {
        alternateImageCache[card.id]?.let { return@withContext it.value }
        try {
            val tcgdex = request("cards/" + encode(card.id))
            val details = JSONObject(tcgdex)
            val setName = details.optJSONObject("set")?.optString("name").orEmpty()
            val number = numberKey(details.optString("localId"))
            if (setName.isBlank() || number.isBlank()) {
                alternateImageCache[card.id] = CachedMatch(null)
                return@withContext null
            }
            val escapedSet = setName.replace("\\", "\\\\").replace("\"", "\\\"")
            val query = "!expansion.name:\"$escapedSet\" !number:\"$number\""
            val fields = "id,name,number,printed_number,language_code,subtypes,expansion,images"
            val response = requestAbsolute("$SCRYDEX_API?q=${encode(query)}&page_size=100&select=${encode(fields)}")
            val match = matchScrydexImage(tcgdex, response)
            alternateImageCache[card.id] = CachedMatch(match)
            match
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
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

    private fun request(path: String): String = requestAbsolute(API + path)

    private fun requestAbsolute(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            if (connection.responseCode !in 200..299) throw IOException("TCGdex HTTP " + connection.responseCode)
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun cardImageUrl(value: String): String? {
        val base = value.trimEnd('/')
        if (Regex("""https://images\.scrydex\.com/pokemon/[^/?#]+/(?:small|medium|large)""").matches(base)) return base
        val match = CARD_IMAGE.matchEntire(base) ?: return null
        if (match.groupValues[1].equals("logo", true) || match.groupValues[1].equals("symbol", true)) return null
        return if (base.endsWith(".webp") || base.endsWith(".png") || base.endsWith(".jpg")) base else "$base/low.webp"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun normalize(cardName: String): String = Normalizer.normalize(cardName, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun numberKey(value: String): String = value.substringBefore('/').trim().uppercase().let {
        it.toIntOrNull()?.toString() ?: it
    }

    private fun cardIdentity(card: JSONObject, scrydex: Boolean): Pair<String, Set<String>> {
        val tokens = normalize(card.optString("name")).split(" ").filter(String::isNotBlank).toMutableList()
        val variants = mutableSetOf<String>()
        if (tokens.firstOrNull() in setOf("m", "mega")) {
            variants += "mega"
            tokens.removeAt(0)
        }
        val suffixes = setOf("ex", "gx", "v", "vmax", "vstar", "break")
        if (tokens.lastOrNull() in suffixes) variants += tokens.removeAt(tokens.lastIndex)
        if (normalize(card.optString("stage")).contains("mega") || normalize(card.optString("subtype")).contains("mega")) variants += "mega"
        normalize(card.optString("suffix")).takeIf { it in suffixes }?.let(variants::add)
        if (scrydex) {
            val subtypes = card.optJSONArray("subtypes") ?: JSONArray()
            for (index in 0 until subtypes.length()) normalize(subtypes.optString(index)).takeIf { it in suffixes || it == "mega" }?.let(variants::add)
        }
        if ("mega" in variants && tokens.lastOrNull() in setOf("x", "y")) tokens.removeAt(tokens.lastIndex)
        return tokens.joinToString(" ") to variants
    }
}
