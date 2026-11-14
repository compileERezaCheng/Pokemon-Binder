package com.example.pokemongrader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MarketPrice(val amount: Double, val currency: String, val type: String, val variant: String,
    val source: String, val updated: String, val dateLabel: String)

data class MarketResponse(val json: String, val consulted: String, val stale: Boolean = false, val error: String? = null)

/** In-memory one-hour cache of complete English responses (all currencies, finishes and conditions). */
class MarketClient(
    private val fetch: suspend (String) -> String = { MarketPrices.request(it) },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Pair<Long, MarketResponse>>()

    suspend fun get(path: String): MarketResponse = mutex.withLock {
        val old = cache[path]
        if (old != null && clock() - old.first < 3600000L) return@withLock old.second
        try {
            val response = MarketResponse(fetch(path), MarketPrices.timestamp(clock()))
            if (cache.size >= 128) cache.remove(cache.keys.first())
            cache[path] = clock() to response
            response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            old?.second?.copy(stale = true, error = e.message) ?: throw e
        }
    }
}

object MarketPrices {
    private var retryAt = 0L
    val client = MarketClient()

    internal fun timestamp(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))

    internal suspend fun request(path: String): String = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() < retryAt) throw IOException("API limit reached. Retry later.")
        val conn = URL("https://api.tcgdex.net/v2/en/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode == 429) {
                retryAt = System.currentTimeMillis() + (conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 86400) ?: 60) * 1000
                throw IOException("API limit reached. Retry later.")
            }
            if (conn.responseCode !in 200..299) throw IOException("TCGdex HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }

    internal fun parse(json: String, selected: CatalogCard, consulted: String): List<MarketPrice> {
        val card = JSONObject(json)
        if (selected.id.isBlank() || card.optString("id") != selected.id ||
            card.optJSONObject("set")?.optString("id") != selected.setId || card.optString("localId") != selected.number) return emptyList()
        val detailed = card.optJSONArray("variants_detailed")
        val scopes = if (detailed != null && detailed.length() > 0) (0 until detailed.length()).mapNotNull { detailed.optJSONObject(it) }
            else listOf(card)
        val rows = buildList {
            for (scope in scopes) {
                val pricing = scope.optJSONObject("pricing") ?: continue
                val label = if (scope === card) "" else buildList {
                    listOf("type", "subtype", "foil", "size").forEach { key -> scope.optString(key).takeIf { it.isNotBlank() }?.let(::add) }
                    scope.optJSONArray("stamp")?.let { stamps -> for (i in 0 until stamps.length()) add(stamps.optString(i)) }
                }.joinToString(" / ")
                fun addPrice(provider: JSONObject?, source: String, field: String, type: String, finish: String, meta: JSONObject? = provider) {
                    val value = provider?.opt(field) as? Number ?: return
                    val amount = value.toDouble()
                    val currency = meta?.optString("unit").orEmpty()
                    if (!amount.isFinite() || amount <= 0 || !Regex("[A-Z]{3}").matches(currency)) return
                    val updated = when (val date = meta?.opt("updated")) {
                        is Number -> timestamp(date.toLong())
                        is String -> date
                        else -> ""
                    }
                    add(MarketPrice(amount, currency, type, listOf(label, finish).filter(String::isNotBlank).joinToString(" · "),
                        source, updated.ifBlank { consulted }, if (updated.isBlank()) "Consulted" else "Source updated"))
                }
                val cm = pricing.optJSONObject("cardmarket")
                for ((suffix, finish) in listOf("" to "Non-foil", "-holo" to "Foil (finish unspecified)")) {
                    if (scope !== card && scope.optString("type") == "normal" && suffix.isNotEmpty()) continue
                    if (scope !== card && scope.optString("type") in listOf("holo", "reverse") && suffix.isEmpty()) continue
                    for ((field, type) in listOf("avg" to "Average", "trend" to "Trend", "low" to "Low")) {
                        addPrice(cm, "Cardmarket via TCGdex", field + suffix, type, finish)
                    }
                }
                val tp = pricing.optJSONObject("tcgplayer")
                tp?.keys()?.forEach { finish ->
                    val value = tp.optJSONObject(finish) ?: return@forEach
                    val expected = mapOf("normal" to "normal", "reverse" to "reverse-holofoil", "holo" to "holofoil")[scope.optString("type")]
                    if (scope !== card && (expected == null || finish != expected)) return@forEach
                    for ((field, type) in listOf("marketPrice" to "Market price", "midPrice" to "Median", "lowPrice" to "Low")) {
                        addPrice(value, "TCGplayer via TCGdex", field, type, finish, tp)
                    }
                }
            }
        }
        if (detailed != null && detailed.length() > 0) {
            val missing = JSONObject()
            listOf("cardmarket" to "Cardmarket via TCGdex", "tcgplayer" to "TCGplayer via TCGdex").forEach { (provider, source) ->
                if (rows.none { it.source == source }) missing.put(provider, card.optJSONObject("pricing")?.opt(provider))
            }
            val root = JSONObject(card.toString()).put("variants_detailed", org.json.JSONArray()).put("pricing", missing)
            return (rows + parse(root.toString(), selected, consulted).map {
                it.copy(variant = "Edition reference · ${if (it.variant == "Non-foil") "Standard price group" else it.variant} (finish unconfirmed)")
            }).sortedBy { if (it.currency == "EUR") 0 else 1 }
        }
        return rows.sortedBy { if (it.currency == "EUR") 0 else 1 }
    }
}
