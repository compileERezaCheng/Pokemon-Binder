package com.example.pokemongrader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.pokemongrader.data.CatalogCard
import com.example.pokemongrader.data.TcgDexClient
import com.example.pokemongrader.ui.main.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSetPicker(
    name: String,
    selected: CatalogCard?,
    setHint: String = "",
    numberHint: String = "",
    onSelected: (CatalogCard?) -> Unit
) {
    val queryKey = listOf(name.trim(), setHint.trim(), numberHint.trim()).joinToString("|")
    var candidates by remember { mutableStateOf<List<CatalogCard>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var requestId by remember { mutableIntStateOf(0) }
    var userSelected by remember { mutableStateOf(false) }
    var setSearch by remember(queryKey) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val visibleCandidates = TcgDexClient.filterBySetName(candidates, setSearch)

    LaunchedEffect(queryKey, retry) {
        val request = ++requestId
        userSelected = false
        candidates = emptyList()
        error = ""
        if (name.isBlank()) return@LaunchedEffect
        loading = true
        try {
            val found = TcgDexClient.searchCards(name, setHint, numberHint)
            if (request != requestId) return@LaunchedEffect
            candidates = found
            val unique = TcgDexClient.uniqueCard(found, name, setHint, numberHint)
            if (unique != null && selected?.id != unique.id && !userSelected) {
                onSelected(unique)
                val details = runCatching { TcgDexClient.fetchCard(unique.id) }.getOrNull()
                if (request == requestId && !userSelected && details != null) onSelected(details.withCandidateImage(unique))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (request == requestId) error = "Catalog search failed."
        } finally {
            if (request == requestId) loading = false
        }
    }

    fun choose(card: CatalogCard?) {
        userSelected = true
        val request = ++requestId
        onSelected(card)
        expanded = false
        if (card != null) scope.launch {
            val details = runCatching { TcgDexClient.fetchCard(card.id) }.getOrNull()
            if (request == requestId && details != null) onSelected(details.withCandidateImage(card))
        }
    }

    fun imageResolved(resolved: CatalogCard) {
        candidates = candidates.map { if (it.id == resolved.id) resolved else it }
        if (selected?.id == resolved.id) onSelected(resolved)
    }

    Column {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selected?.let { it.setName + " · #" + it.number }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Sets") },
                placeholder = { Text(if (name.isBlank()) "Choose a Pokémon first" else "Choose a card edition") },
                trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFEF4444),
                    unfocusedBorderColor = Color(0xFF334155)
                ),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.exposedDropdownSize()
            ) {
                OutlinedTextField(
                    value = setSearch,
                    onValueChange = { setSearch = it },
                    label = { Text("Search sets") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenuItem(
                    text = { Text("Save without set") },
                    onClick = { choose(null) }
                )
                if (visibleCandidates.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No matching sets") },
                        onClick = {},
                        enabled = false
                    )
                }
                visibleCandidates.forEach { candidate ->
                    DropdownMenuItem(
                    text = { CatalogSetOption(candidate) { resolved -> imageResolved(resolved) } },
                        onClick = { choose(candidate) }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            name.isBlank() -> Text("Choose a Pokémon first.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            loading -> Text("Loading sets...", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            error.isNotBlank() -> TextButton(
                onClick = { retry++ },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
            ) { Text("Catalog unavailable. Tap to retry.") }
            candidates.isEmpty() -> Text("No set results. You can save without a set.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            selected == null -> Text("Choose a set or save without one.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun CatalogSetOption(candidate: CatalogCard, onImageResolved: (CatalogCard) -> Unit = {}) {
    var imageCard by remember(candidate.id, candidate.imageUrl) { mutableStateOf(candidate) }
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            url = imageCard.imageUrl,
            contentDescription = imageCard.setName + " · #" + imageCard.number,
            modifier = Modifier.width(84.dp).height(116.dp),
            placeholder = "Image unavailable",
            onError = {
                if (imageCard.imageSource != "Scrydex") scope.launch {
                    TcgDexClient.resolveAlternateImage(imageCard)?.let { match ->
                        imageCard = imageCard.copy(imageUrl = match.imageUrl, imageSource = "Scrydex", imageMatchId = match.cardId)
                        onImageResolved(imageCard)
                    }
                }
            }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(imageCard.setName, style = MaterialTheme.typography.bodyMedium)
            Text("#" + imageCard.number, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun CatalogCard.withCandidateImage(candidate: CatalogCard): CatalogCard =
    if (candidate.imageSource == "Scrydex") copy(
        imageUrl = candidate.imageUrl, imageSource = candidate.imageSource, imageMatchId = candidate.imageMatchId
    ) else this
