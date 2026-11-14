package com.example.pokemongrader.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pokemongrader.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale

data class MarketState(
    val name: String = "", val set: String = "", val number: String = "",
    val cards: List<CatalogCard> = emptyList(), val selected: CatalogCard? = null,
    val prices: List<MarketPrice> = emptyList(), val loading: Boolean = false,
    val status: String = "Search by name, then select an edition.", val error: Boolean = false
)

class MarketViewModel(private val client: MarketClient = MarketPrices.client) : ViewModel() {
    private val mutable = MutableStateFlow(MarketState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var revision = 0

    fun search(name: String, set: String, number: String) {
        request?.cancel()
        val token = ++revision
        mutable.value = MarketState(name, set, number, loading = name.isNotBlank(), status = if (name.isBlank()) "Search by name, then select an edition." else "Searching…")
        if (name.isBlank()) return
        request = viewModelScope.launch {
            delay(350)
            try {
                val result = client.get("cards?name=" + URLEncoder.encode(name.trim(), "UTF-8"))
                val sets = client.get("sets")
                val names = JSONArray(sets.json).let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .associate { it.optString("id") to it.optString("name") } }
                val cards = TcgDexClient.sortBySetName(TcgDexClient.parseBriefCards(result.json, names)).filter {
                    it.setName.contains(set.trim(), true) && (number.isBlank() || it.number.equals(number.substringBefore('/').trim(), true))
                }
                if (token == revision) mutable.value = mutable.value.copy(cards = cards, loading = false,
                    status = (if (result.stale || sets.stale) "Outdated cached catalog · " else "") +
                        if (cards.isEmpty()) "No results. Try another name, set or number." else "${cards.size} editions. Select the exact printing.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == revision) mutable.value = mutable.value.copy(loading = false, error = true, status = "Network error: ${e.message}") }
        }
    }

    fun select(card: CatalogCard) {
        request?.cancel()
        val token = ++revision
        mutable.value = mutable.value.copy(selected = card, prices = emptyList(), loading = true, error = false, status = "Loading prices…")
        request = viewModelScope.launch {
            try {
                val result = client.get("cards/" + URLEncoder.encode(card.id, "UTF-8"))
                val prices = withContext(Dispatchers.Default) { MarketPrices.parse(result.json, card, result.consulted) }
                if (token == revision) mutable.value = mutable.value.copy(prices = prices, loading = false,
                    status = (if (result.stale) "Outdated cached prices · ${result.error} · " else "") +
                        if (prices.isEmpty()) "Price unavailable for this printing." else "References per finish · English catalog")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == revision) mutable.value = mutable.value.copy(loading = false, error = true, status = "Network error: ${e.message}") }
        }
    }

    fun retry() { state.value.let { if (it.selected != null) select(it.selected) else search(it.name, it.set, it.number) } }
}

@Composable
fun MarketScreen(state: MarketState, model: MarketViewModel) {
    val inputColors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedLabelColor = Color(0xFF93C5FD), unfocusedLabelColor = Color.LightGray, cursorColor = Color.White,
        focusedBorderColor = Color(0xFF93C5FD), unfocusedBorderColor = Color.Gray)
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Market Prices", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("Uncertified cards · Marketplace language and condition unspecified. AI grades are not certification.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(state.name, { model.search(it, state.set, state.number) }, colors = inputColors, label = { Text("Card name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.set, { model.search(state.name, it, state.number) }, colors = inputColors, label = { Text("Set / expansion") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(state.number, { model.search(state.name, state.set, it) }, colors = inputColors, label = { Text("Number") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        item {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(state.status, color = if (state.error) MaterialTheme.colorScheme.error else Color.LightGray)
            TextButton(onClick = model::retry, enabled = !state.loading, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF93C5FD))) { Text("Retry") }
        }
        state.selected?.let { selected ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(selected.imageUrl, selected.name, Modifier.size(72.dp, 100.dp), placeholder = "Imagem indisponível")
                    Text("${selected.name}\n${selected.setName} #${selected.number}\nEN", color = Color.White)
                }
            }
            items(state.prices) { price ->
                Surface(color = Color(0xFF0F172A), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(String.format(Locale.US, "%.2f %s · %s", price.amount, price.currency, price.type), color = Color.White)
                        Text("${price.variant}\n${price.source}\n${price.dateLabel}: ${price.updated}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(state.cards, key = { it.id }) { card ->
            Row(modifier = Modifier.fillMaxWidth().clickable { model.select(card) }.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(card.imageUrl, card.name, Modifier.size(48.dp, 68.dp), placeholder = "Imagem indisponível")
                Text("${card.name}\n${card.setName} #${card.number} · EN", color = Color.White)
            }
        }
    }
}
