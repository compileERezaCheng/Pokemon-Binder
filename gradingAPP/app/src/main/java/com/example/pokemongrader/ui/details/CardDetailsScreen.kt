package com.example.pokemongrader.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pokemongrader.data.CatalogCard
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import com.example.pokemongrader.data.TcgDexClient
import com.example.pokemongrader.data.TcgDexLocale
import com.example.pokemongrader.ui.components.CatalogSetPicker
import com.example.pokemongrader.ui.components.GradeSummary
import com.example.pokemongrader.ui.components.PokemonCard
import com.example.pokemongrader.ui.components.TcgDexLanguagePicker
import com.example.pokemongrader.ui.main.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailsScreen(
    page: Int,
    slot: Int,
    dateAdded: String,
    repository: DataRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    copyId: String = "",
    onNavigateToMarket: (Card) -> Unit = {}
) {
    val cards by repository.cards.collectAsStateWithLifecycle()
    val card = remember(cards, page, slot, dateAdded, copyId) {
        cards.find { copyId.isNotBlank() && it.copyId == copyId }
            ?: cards.find { it.page == page && it.slot == slot && it.dateAdded == dateAdded }
    }
    val coroutineScope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }
    var isSavingIdentity by remember { mutableStateOf(false) }
    var isEditingIdentity by remember(card?.copyId) { mutableStateOf(false) }
    var showCatalogPicker by remember(card?.copyId) { mutableStateOf(false) }
    val currentCatalog = card?.takeIf { it.catalogProvider == "TCGdex" && it.catalogCardId.isNotBlank() }?.let {
        CatalogCard(
            id = card.catalogCardId, name = card.name, setId = card.setId, setName = card.setName,
            number = card.cardNumber, rarity = card.catalogRarity, imageUrl = card.imageUrl,
            imageSource = card.imageSource, imageMatchId = card.imageMatchId,
            locale = card.catalogLocale, provider = card.catalogProvider, speciesDex = card.dexNumber
        )
    }
    var selectedCatalog by remember(card?.copyId) { mutableStateOf(currentCatalog) }
    var editName by remember(card?.copyId) { mutableStateOf(card?.name.orEmpty()) }
    var editSpeciesName by remember(card?.copyId) { mutableStateOf(card?.speciesName.orEmpty()) }
    var editForm by remember(card?.copyId) { mutableStateOf(card?.form.orEmpty()) }
    var editDexNumber by remember(card?.copyId) { mutableStateOf(card?.dexNumber?.takeIf { it > 0 }?.toString().orEmpty()) }
    var editCardLanguage by remember(card?.copyId) { mutableStateOf(card?.cardLanguage.orEmpty()) }
    var editCatalogLocale by remember(card?.copyId) { mutableStateOf(card?.catalogLocale.orEmpty()) }
    var editSetName by remember(card?.copyId) { mutableStateOf(card?.setName.orEmpty()) }
    var editCardNumber by remember(card?.copyId) { mutableStateOf(card?.cardNumber.orEmpty()) }
    var editVariant by remember(card?.copyId) { mutableStateOf(card?.catalogVariant.orEmpty()) }
    val identityChanged = card != null && (editName != card.name || editSpeciesName != card.speciesName ||
        editForm != card.form || editDexNumber != card.dexNumber.takeIf { it > 0 }?.toString().orEmpty() ||
        editCardLanguage != card.cardLanguage || editCatalogLocale != card.catalogLocale ||
        editSetName != card.setName || editCardNumber != card.cardNumber || editVariant != card.catalogVariant ||
        selectedCatalog != currentCatalog)
    val isDexNumberValid = editDexNumber.isBlank() || editDexNumber.trim().toIntOrNull()?.let { it >= 0 } == true

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Card Details", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF020617))
            )
        },
        containerColor = Color(0xFF020617)
    ) { paddingValues ->
        if (card == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("Card not found", color = Color.Gray)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (saveMessage.isNotBlank()) Text(saveMessage, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                CardDetailsArtwork(
                    card, modifier = Modifier.fillMaxWidth(0.8f),
                    onImageFailure = {
                        if (card.catalogProvider == "TCGdex" && card.catalogLocale == "en" && card.catalogCardId.isNotBlank() && card.imageSource != "Scrydex") coroutineScope.launch {
                            val source = currentCatalog ?: return@launch
                            val match = TcgDexClient.resolveAlternateImage(source) ?: return@launch
                            val resolved = source.copy(imageUrl = match.imageUrl, imageSource = "Scrydex", imageMatchId = match.cardId)
                            if (repository.updateCard(card.copy(imageUrl = match.imageUrl, imageSource = "Scrydex", imageMatchId = match.cardId))) {
                                selectedCatalog = resolved
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(20.dp))
                GradeSummary(card.grade, card.catalogRarity, card.condition, card.gradeDetails)
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A)).padding(16.dp)
                ) {
                    DetailRow("Name", card.name)
                    if (!isEditingIdentity) {
                        DetailRow("Species name", card.speciesName.ifBlank { "Unknown" })
                        DetailRow("Regional form", card.form.ifBlank { "Unknown" })
                        DetailRow("Physical card language", card.cardLanguage.ifBlank { "Unknown / manual" }.let(TcgDexLocale::label))
                        DetailRow("Catalog language", card.catalogLocale.ifBlank { "Unknown / manual" }.let(TcgDexLocale::label))
                    }
                    DetailRow("Dex Number", if (card.dexNumber > 0) "#" + card.dexNumber else "Custom")
                    DetailRow("Rarity", card.catalogRarity.ifBlank { card.type })
                    DetailRow("Condition", card.condition)
                    if (card.setName.isNotBlank()) DetailRow("Set", card.setName)
                    if (card.cardNumber.isNotBlank()) DetailRow("Card Number", card.cardNumber)
                    DetailRow("Location", "Page " + card.page + ", Slot " + card.slot)
                    DetailRow("Date Added", card.dateAdded)

                    Spacer(modifier = Modifier.height(16.dp))
                    if (!isEditingIdentity) {
                        OutlinedButton(onClick = { isEditingIdentity = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Edit identity")
                        }
                        TextButton(onClick = { onNavigateToMarket(card) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Market Prices")
                        }
                    } else {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it; selectedCatalog = null },
                            label = { Text("Printed name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("printed-name")
                        )
                        OutlinedTextField(
                            value = editSpeciesName,
                            onValueChange = { value ->
                                editSpeciesName = value
                                editDexNumber = if (value == card.speciesName) card.dexNumber.takeIf { it > 0 }?.toString().orEmpty() else ""
                                selectedCatalog = null
                            },
                            label = { Text("Species name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editDexNumber,
                            onValueChange = { editDexNumber = it },
                            label = { Text("National Dex number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editForm,
                            onValueChange = { editForm = it; selectedCatalog = null },
                            label = { Text("Regional form") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TcgDexLanguagePicker(
                            "Physical card language",
                            editCardLanguage.takeIf(TcgDexLocale::isSupported).orEmpty(),
                            { editCardLanguage = it; selectedCatalog = null }
                        )
                        if (!TcgDexLocale.isSupported(editCardLanguage)) {
                            OutlinedTextField(
                                value = editCardLanguage,
                                onValueChange = { editCardLanguage = it; selectedCatalog = null },
                                label = { Text("Other physical language") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        TcgDexLanguagePicker(
                            "Catalog language",
                            editCatalogLocale,
                            { editCatalogLocale = it; selectedCatalog = null }
                        )
                        OutlinedTextField(
                            value = editSetName,
                            onValueChange = { editSetName = it; selectedCatalog = null },
                            label = { Text("Set name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editCardNumber,
                            onValueChange = { editCardNumber = it; selectedCatalog = null },
                            label = { Text("Card number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editVariant,
                            onValueChange = { editVariant = it },
                            label = { Text("TCG variant / finish") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = { showCatalogPicker = !showCatalogPicker },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (showCatalogPicker) "Hide catalog search" else "Search catalog printing") }
                        if (showCatalogPicker) {
                            CatalogSetPicker(
                                name = editName,
                                selected = selectedCatalog,
                                setHint = editSetName,
                                numberHint = editCardNumber,
                                locale = editCatalogLocale,
                                identityKey = editSpeciesName + "|" + editForm + "|" + editCardLanguage,
                                onSelected = { candidate ->
                                    selectedCatalog = candidate
                                    if (candidate != null) {
                                        editSetName = candidate.setName
                                        editCardNumber = candidate.number
                                    }
                                }
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    editName = card.name
                                    editSpeciesName = card.speciesName
                                    editForm = card.form
                                    editDexNumber = card.dexNumber.takeIf { it > 0 }?.toString().orEmpty()
                                    editCardLanguage = card.cardLanguage
                                    editCatalogLocale = card.catalogLocale
                                    editSetName = card.setName
                                    editCardNumber = card.cardNumber
                                    editVariant = card.catalogVariant
                                    selectedCatalog = currentCatalog
                                    showCatalogPicker = false
                                    isEditingIdentity = false
                                    saveMessage = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                            Button(
                                enabled = !isSavingIdentity && identityChanged && isDexNumberValid,
                                onClick = {
                                    val selection = selectedCatalog
                                    isSavingIdentity = true
                                    saveMessage = ""
                                    coroutineScope.launch {
                                        val updated = card.copy(
                                            name = editName.trim(),
                                            dexNumber = editDexNumber.trim().toIntOrNull() ?: 0,
                                            speciesName = editSpeciesName.trim(),
                                            form = editForm.trim(),
                                            cardLanguage = editCardLanguage.trim(),
                                            catalogProvider = selection?.provider.orEmpty(),
                                            catalogLocale = selection?.locale ?: editCatalogLocale.trim(),
                                            catalogCardId = selection?.id.orEmpty(),
                                            setId = selection?.setId.orEmpty(),
                                            setName = selection?.setName?.ifBlank { editSetName.trim() } ?: editSetName.trim(),
                                            cardNumber = selection?.number?.ifBlank { editCardNumber.trim() } ?: editCardNumber.trim(),
                                            catalogRarity = selection?.rarity.orEmpty(),
                                            imageUrl = selection?.imageUrl.orEmpty(),
                                            imageSource = selection?.imageSource.orEmpty(),
                                            imageMatchId = selection?.imageMatchId.orEmpty(),
                                            catalogVariant = editVariant.trim(),
                                            type = selection?.rarity?.ifBlank { card.type } ?: card.type
                                        )
                                        val saved = repository.updateCard(updated)
                                        isSavingIdentity = false
                                        if (saved) {
                                            selectedCatalog = selection
                                            showCatalogPicker = false
                                            isEditingIdentity = false
                                            saveMessage = "Card details saved."
                                        } else {
                                            saveMessage = repository.errorMessage.value ?: "Could not save card details. Retry."
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                            ) { Text(if (isSavingIdentity) "Saving..." else "Save identity") }
                        }
                    }
                    if (card.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Notes", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(card.notes, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Card") },
            text = { Text("Are you sure you want to remove this card from your collection?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        card?.let {
                            coroutineScope.launch {
                                if (repository.removeCard(it)) onNavigateBack()
                                else {
                                    showDeleteConfirm = false
                                    saveMessage = repository.errorMessage.value ?: "Could not remove the card. Retry."
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("CANCEL") } },
            containerColor = Color(0xFF0F172A),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}

@Composable
internal fun CardDetailsArtwork(card: Card, modifier: Modifier = Modifier, onImageFailure: (() -> Unit)? = null) {
    var enlargedImage by remember(card.copyId, card.imageUrl) { mutableStateOf<String?>(null) }
    PokemonCard(card, modifier, onImageClick = { enlargedImage = it }, onImageFailure = onImageFailure)
    enlargedImage?.let { image ->
        Dialog(
            onDismissRequest = { enlargedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF020617)).safeDrawingPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(onClick = { enlargedImage = null }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Default.Close, contentDescription = "Close image", tint = Color.White)
                }
                AsyncImage(
                    url = image.replace("/low.", "/high."),
                    fallbackUrl = image,
                    contentDescription = "Enlarged " + card.name + " card",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = "Image unavailable"
                )
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
