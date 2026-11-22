package com.example.pokemongrader.ui.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.printIdentityKey
import com.example.pokemongrader.data.DataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// Shared rarity order (most rare = lowest index)
val RarityOrder = listOf(
    "Mega Hyper Rare", "Hyper Rare", "Mega Attack Rare",
    "Special Illustration Rare", "Illustration Rare", "Ace Spec Rare",
    "Secret Rare", "Ultra Rare", "Double Rare", "Shiny Rare",
    "Reverse Holo", "Holofoil Rare", "Rare", "Normal"
)

private val rarityWhitespace = Regex("\\s+")

fun getRarityRank(rarity: String): Int {
    val normalized = rarity.trim().replace(rarityWhitespace, " ")
    val idx = RarityOrder.indexOfFirst { it.equals(normalized, ignoreCase = true) }
    return if (idx == -1) RarityOrder.size else idx
}

fun getRarityColor(rarity: String): Color {
    val r = rarity.trim().lowercase()
    return when {
        r.contains("hyper") -> Color(0xFFEAB308)
        r.contains("special illustration") -> Color(0xFFA855F7)
        r.contains("illustration") -> Color(0xFFF97316)
        r.contains("secret") -> Color(0xFFEC4899)
        r.contains("ultra") -> Color(0xFF3B82F6)
        r.contains("double") -> Color(0xFF06B6D4)
        r.contains("holo") || r.contains("foil") -> Color(0xFF10B981)
        r.contains("rare") -> Color(0xFFF43F5E)
        else -> Color(0xFF64748B)
    }
}

fun getRarityAbbrev(rarity: String): String {
    return when (rarity) {
        "Special Illustration Rare" -> "SIR"
        "Illustration Rare" -> "IR"
        "Secret Rare" -> "SR"
        "Hyper Rare" -> "HR"
        "Ultra Rare" -> "UR"
        "Double Rare" -> "DR"
        "Reverse Holo" -> "R Holo"
        "Holofoil Rare" -> "Holo"
        "Mega Hyper Rare" -> "MHR"
        "Mega Attack Rare" -> "MAR"
        "Ace Spec Rare" -> "Ace"
        "Shiny Rare" -> "Shiny"
        else -> rarity
    }
}

@Composable
fun RarityShimmerOverlay(rarity: String, modifier: Modifier = Modifier) {
    val r = rarity.trim().lowercase()
    val shouldShimmer = when {
        r.contains("normal") && !r.contains("rare") -> false
        r == "rare" -> false
        else -> true
    }
    if (!shouldShimmer) {
        Box(modifier = modifier)
        return
    }

    val shimmerColors = when {
        r.contains("hyper") -> listOf(
            Color.Transparent, Color(0x55EAB308), Color(0xAAFBBF24), Color(0x55EAB308), Color.Transparent
        )
        r.contains("special illustration") -> listOf(
            Color.Transparent, Color(0x55A855F7), Color(0xAAC084FC), Color(0x55A855F7), Color.Transparent
        )
        r.contains("illustration") -> listOf(
            Color.Transparent, Color(0x55F97316), Color(0xAAFB923C), Color(0x55F97316), Color.Transparent
        )
        r.contains("secret") -> listOf(
            Color.Transparent, Color(0x55EC4899), Color(0xAAF472B6), Color(0x55EC4899), Color.Transparent
        )
        r.contains("ultra") -> listOf(
            Color.Transparent, Color(0x553B82F6), Color(0xAA60A5FA), Color(0x553B82F6), Color.Transparent
        )
        r.contains("double") || r.contains("ace") -> listOf(
            Color.Transparent, Color(0x5506B6D4), Color(0xAA22D3EE), Color(0x5506B6D4), Color.Transparent
        )
        r.contains("shiny") -> listOf(
            Color.Transparent, Color(0x5510B981), Color(0xAA34D399), Color(0x5510B981), Color.Transparent
        )
        r.contains("holo") || r.contains("foil") || r.contains("reverse") -> listOf(
            Color.Transparent, Color(0x4010B981), Color(0x8034D399), Color(0x4010B981), Color.Transparent
        )
        else -> listOf(
            Color.Transparent, Color(0x30FFFFFF), Color(0x60FFFFFF), Color(0x30FFFFFF), Color.Transparent
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer_$rarity")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset_$rarity"
    )

    Box(
        modifier = modifier.drawWithContent {
            drawContent()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val sweepWidth = size.width * 0.6f
                val startX = size.width * shimmerOffset - sweepWidth / 2
                drawRect(
                    brush = Brush.linearGradient(
                        colors = shimmerColors,
                        start = Offset(startX, 0f),
                        end = Offset(startX + sweepWidth, size.height)
                    ),
                    blendMode = BlendMode.Screen
                )
            }
        }
    )
}

fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

@Composable
fun AsyncImage(
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    placeholder: String? = null,
    onClick: (() -> Unit)? = null,
    onError: (() -> Unit)? = null
) {
    var image by remember(url, fallbackUrl) { mutableStateOf<Pair<String, Bitmap>?>(null) }
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(url, fallbackUrl) {
        image = withContext(Dispatchers.IO) {
            var loaded: Pair<String, Bitmap>? = null
            listOfNotNull(url, fallbackUrl).filter { it.isNotBlank() }.distinct().forEach { source ->
                ensureActive()
                if (loaded == null) {
                    try {
                        val conn = URL(source).openConnection() as HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        try {
                            if (conn.responseCode in 200..299) {
                                conn.inputStream.use { stream ->
                                    BitmapFactory.decodeStream(stream)?.let { loaded = source to it }
                                }
                            }
                        } finally {
                            conn.disconnect()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        loaded = null
                    }
                }
            }
            loaded
        }
        if (image == null) currentOnError?.invoke()
    }

    val loaded = image
    if (loaded != null) {
        Image(
            bitmap = loaded.second.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = if (onClick != null && loaded.first == url) {
                modifier.clickable(onClickLabel = "Enlarge image", onClick = onClick)
            } else modifier
        )
    } else {
        Box(
            modifier = modifier.background(Color.DarkGray).semantics {
                if (placeholder != null) this.contentDescription = placeholder
            },
            contentAlignment = Alignment.Center
        ) {
            if (placeholder != null) {
                Text(placeholder, color = Color.LightGray, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun ProfileImage(
    source: String,
    dex: Int,
    url: String,
    base64Str: String,
    modifier: Modifier = Modifier
) {
    if (source == "url" && url.isNotEmpty()) {
        AsyncImage(url = url, contentDescription = "Profile", modifier = modifier, placeholder = "●")
    } else if ((source == "base64" || source == "upload") && base64Str.isNotEmpty()) {
        val bitmap = remember(base64Str) {
            try {
                val bytes = Base64.decode(base64Str, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Profile", modifier = modifier)
        } else {
            Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) { Text("●", color = Color.LightGray) }
        }
    } else {
        val pUrl = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/$dex.png"
        AsyncImage(url = pUrl, contentDescription = "Profile", modifier = modifier, placeholder = "●")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun MainScreen(
    repository: DataRepository,
    onNavigateToScan: () -> Unit,
    onNavigateToCardDetails: (Int, Int, String, String) -> Unit,
    onNavigateToAccountSettings: () -> Unit,
    activeTab: String,
    onActiveTabChange: (String) -> Unit,
    currentPage: Int,
    onCurrentPageChange: (Int) -> Unit,
    marketCardToOpen: Card? = null,
    onMarketCardConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cards by repository.cards.collectAsStateWithLifecycle()
    val isLoading by repository.isLoading.collectAsStateWithLifecycle()
    val errorMessage by repository.errorMessage.collectAsStateWithLifecycle()
    val profilePicSource by repository.profilePicSource.collectAsStateWithLifecycle()
    val profileFeaturedDex by repository.profileFeaturedDex.collectAsStateWithLifecycle()
    val profileImageUrl by repository.profileImageUrl.collectAsStateWithLifecycle()
    val profileImageBase64 by repository.profileImageBase64.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val marketModel: MarketViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val marketState by marketModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(marketCardToOpen) {
        marketCardToOpen?.let {
            marketModel.openCard(it)
            onMarketCardConsumed()
        }
    }
    val navigationColors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White,
        unselectedIconColor = Color.LightGray, indicatorColor = Color(0xFF1E293B))
    BackHandler(activeTab != "binder") { onActiveTabChange("binder") }

    // Filter and Sort states for Collection List
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedRarityFilter by rememberSaveable { mutableStateOf("All") }
    var selectedSortOption by rememberSaveable { mutableStateOf("Dex Number") }
    var showRepeated by rememberSaveable { mutableStateOf(false) }

    var expandedRarityFilter by remember { mutableStateOf(false) }
    var expandedSortOption by remember { mutableStateOf(false) }

    val filteredCards = remember(cards, searchQuery, selectedRarityFilter, selectedSortOption, showRepeated) {
        var list = cards

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase().trim()
            list = list.filter { listOf(it.name, it.speciesName, it.form, it.cardLanguage, it.catalogProvider,
                it.catalogLocale, it.catalogCardId, it.setName, it.cardNumber, it.catalogVariant, it.notes)
                .any { value -> value.lowercase().contains(q) } }
        }

        if (selectedRarityFilter != "All") {
            list = list.filter { it.type == selectedRarityFilter }
        }

        if (!showRepeated) {
            val grouped = list.groupBy { it.printIdentityKey() }
            list = grouped.map { entry ->
                entry.value.minByOrNull { getRarityRank(it.type) }!!
            }
        }

        when (selectedSortOption) {
            "Dex Number" -> {
                list.sortedWith(compareBy<Card> { if (it.dexNumber != 0) it.dexNumber else 9999 }
                    .thenBy { getRarityRank(it.type) })
            }
            "Location" -> {
                list.sortedWith(compareBy<Card> { it.page }
                    .thenBy { it.slot }
                    .thenBy { getRarityRank(it.type) })
            }
            "Name" -> {
                list.sortedWith(compareBy<Card> { it.name.lowercase() }
                    .thenBy { getRarityRank(it.type) })
            }
            "Rarity" -> {
                list.sortedWith(compareBy<Card> { getRarityRank(it.type) }
                    .thenBy { it.name.lowercase() })
            }
            "Date Added" -> {
                list.sortedByDescending { it.dateAdded }
            }
            else -> list
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF020617),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // Navigation.kt already applies safeDrawingPadding, including the system navigation bar.
            Row(Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF0F172A)), verticalAlignment = Alignment.CenterVertically) {
                NavigationBarItem(activeTab == "binder", { onActiveTabChange("binder") },
                    colors = navigationColors, icon = { Icon(Icons.Default.MenuBook, contentDescription = "Binder") })
                NavigationBarItem(activeTab == "collection", { onActiveTabChange("collection") },
                    colors = navigationColors, icon = { Icon(Icons.Default.Search, contentDescription = "Collection/Search") })
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FilledIconButton(onClick = {
                        repository.prefilledPage = null
                        repository.prefilledSlot = null
                        onNavigateToScan()
                    }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFEF4444))) {
                        Icon(Icons.Default.Add, contentDescription = "Add Card", tint = Color.White)
                    }
                }
                NavigationBarItem(activeTab == "market", { onActiveTabChange("market") },
                    colors = navigationColors, icon = { Icon(Icons.Default.Payments, contentDescription = "Market Prices") })
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onNavigateToAccountSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Account and profile" }) {
                        ProfileImage(profilePicSource, profileFeaturedDex, profileImageUrl, profileImageBase64,
                            Modifier.size(36.dp).clip(CircleShape))
                    }
                }
            }
        }
    ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 4.dp)) {
                errorMessage?.let { Text(it, color = Color(0xFFFCA5A5), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) }
                if (activeTab != "binder") {
                    TextButton(onClick = { coroutineScope.launch { repository.refreshAndFetch() } }, enabled = !isLoading) { Text(if (isLoading) "Syncing…" else "Sync collection") }
                }
                if (activeTab == "market") {
                    MarketScreen(marketState, marketModel)
                } else if (activeTab == "binder") {
                    AnimatedContent(
                        targetState = currentPage,
                        transitionSpec = {
                            val direction = if (targetState > initialState) 1 else -1
                            (slideInHorizontally(tween(250)) { direction * it } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(250)) { -direction * it } + fadeOut(tween(180)))
                        },
                        label = "binder-page-turn",
                        modifier = Modifier.weight(1f)
                    ) { page ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(48.dp)) {
                                if (page > 1) TextButton(
                                    onClick = { onCurrentPageChange((page - 1).coerceAtLeast(1)) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF93C5FD)),
                                    modifier = Modifier.align(Alignment.CenterStart)
                                ) { Text("Previous page") }
                                TextButton(onClick = { coroutineScope.launch { repository.refreshAndFetch() } }, enabled = !isLoading,
                                    modifier = Modifier.align(Alignment.Center).semantics { contentDescription = "Sync collection" },
                                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Page $page", color = Color.White, fontWeight = FontWeight.Bold)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.LightGray)
                                            Text(if (isLoading) "Syncing…" else "Sync", color = Color.LightGray, fontSize = 10.sp)
                                        }
                                    }
                                }
                                TextButton(onClick = { onCurrentPageChange(page + 1) }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF93C5FD)), modifier = Modifier.align(Alignment.CenterEnd)) { Text("Next page") }
                            }

                            var offsetX by remember { mutableStateOf(0f) }
                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth().weight(1f)
                                    .semantics { contentDescription = "Binder slots" }
                                    .pointerInput(page) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                if (offsetX < -150f) {
                                                    onCurrentPageChange(page + 1)
                                                } else if (offsetX > 150f && page > 1) {
                                                    onCurrentPageChange((page - 1).coerceAtLeast(1))
                                                }
                                                offsetX = 0f
                                            },
                                            onDragCancel = { offsetX = 0f },
                                            onHorizontalDrag = { change, dragAmount ->
                                                change.consume()
                                                offsetX += dragAmount
                                            }
                                        )
                                    }
                            ) {
                                val gap = 4.dp
                                val availableHeight = ((maxHeight - gap * 2) / 3).coerceAtLeast(1.dp)
                                val cellWidth = minOf((maxWidth - gap * 2) / 3, maxOf(48.dp, availableHeight * 0.7f))
                                val cellHeight = minOf(availableHeight, cellWidth / 0.7f)
                                Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(gap)) {
                                  repeat(3) { row ->
                                   Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    repeat(3) { column ->
                                    val index = row * 3 + column
                                    val slotIndex = index + 1
                                    val pocketCards = cards.filter { it.page == page && it.slot == slotIndex }
                                    PocketCell(
                                        slot = slotIndex,
                                        cards = pocketCards,
                                        modifier = Modifier.size(cellWidth, cellHeight),
                                        compact = cellHeight < 110.dp,
                                        onClick = {
                                            if (pocketCards.isNotEmpty()) {
                                                val topCard = pocketCards.minByOrNull { getRarityRank(it.type) }!!
                                                onNavigateToCardDetails(topCard.page, topCard.slot, topCard.dateAdded, topCard.copyId)
                                            } else {
                                                repository.prefilledPage = page
                                                repository.prefilledSlot = slotIndex
                                                onNavigateToScan()
                                            }
                                        }
                                    )
                                    }
                                   }
                                  }
                                }
                            }
                        }
                    }
                } else {
                    // Collection List Filters
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search by name or notes...", color = Color.DarkGray) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Rarity Filter — Fix: explicit contentColor = White so text isn't dimmed
                            Box(modifier = Modifier.weight(1f)) {
                                Button(
                                    onClick = { expandedRarityFilter = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1E293B),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(selectedRarityFilter, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                                }
                                DropdownMenu(
                                    expanded = expandedRarityFilter,
                                    onDismissRequest = { expandedRarityFilter = false },
                                    containerColor = Color(0xFF0F172A)
                                ) {
                                    listOf("All", "Normal", "Rare", "Holofoil Rare", "Reverse Holo", "Ultra Rare", "Illustration Rare", "Special Illustration Rare", "Secret Rare", "Hyper Rare").forEach { rarity ->
                                        DropdownMenuItem(
                                            text = { Text(rarity, color = Color.White) },
                                            onClick = {
                                                selectedRarityFilter = rarity
                                                expandedRarityFilter = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Sort Option — Fix: explicit contentColor = White
                            Box(modifier = Modifier.weight(1f)) {
                                Button(
                                    onClick = { expandedSortOption = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1E293B),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(selectedSortOption, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                                }
                                DropdownMenu(
                                    expanded = expandedSortOption,
                                    onDismissRequest = { expandedSortOption = false },
                                    containerColor = Color(0xFF0F172A)
                                ) {
                                    listOf("Date Added", "Dex Number", "Name", "Rarity", "Location").forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option, color = Color.White) },
                                            onClick = {
                                                selectedSortOption = option
                                                expandedSortOption = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Show Repeated Toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = showRepeated,
                                onCheckedChange = { showRepeated = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF3B82F6))
                            )
                            Text("Show Repeated Cards", color = Color.LightGray, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredCards) { card ->
                                CollectionRow(
                                    card = card,
                                    onClick = { onNavigateToCardDetails(card.page, card.slot, card.dateAdded, card.copyId) }
                                )
                            }
                        }
                    }
                }
            }

    }
}

@Composable
fun PocketCell(
    slot: Int,
    cards: List<Card>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val topCard = remember(cards) {
        if (cards.isEmpty()) null else cards.minByOrNull { getRarityRank(it.type) }
    }
    
    Box(
        modifier = modifier
            .semantics { contentDescription = "Slot $slot" }
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1F1E293B))
            .border(
                1.dp,
                if (topCard != null) getRarityColor(topCard.type).copy(alpha = 0.5f) else Color(0x33FFFFFF),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        if (topCard != null) {
            val imageUrl = topCard.imageUrl.takeIf { it.startsWith("https://assets.tcgdex.net/") } ?: if (topCard.dexNumber > 0) {
                "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${topCard.dexNumber}.png"
            } else ""

            // Shimmer overlay
            RarityShimmerOverlay(
                rarity = topCard.type,
                modifier = Modifier.matchParentSize()
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                if (!compact) Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rarity Badge (Top-Left)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(getRarityColor(topCard.type))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = getRarityAbbrev(topCard.type),
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Stack Count (Top-Right)
                    if (cards.size > 1) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = cards.size.toString(),
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (imageUrl.isNotEmpty()) {
                    AsyncImage(
                        url = imageUrl,
                        contentDescription = topCard.name,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Name + Grade Badge row at bottom
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text(
                        text = topCard.name.capitalize(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(end = if (topCard.grade > 0) 22.dp else 0.dp)
                    )

                    // Grade badge — bottom-right corner
                    if (topCard.grade > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F172A).copy(alpha = 0.9f))
                                .border(1.dp, Color(0xFFEAB308), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = topCard.grade.let {
                                    if (it == it.toLong().toDouble()) it.toLong().toString() else "%.1f".format(it)
                                },
                                color = Color(0xFFEAB308),
                                fontSize = 6.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            // Empty Cell state
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (!compact) Text("+", color = Color(0x66FFFFFF), fontSize = 24.sp)
                Text("Slot $slot", color = Color(0x66FFFFFF), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun CollectionRow(
    card: Card,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x1AFFFFFF))
                .border(1.dp, getRarityColor(card.type).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val imageUrl = card.imageUrl.takeIf { it.startsWith("https://assets.tcgdex.net/") } ?: if (card.dexNumber > 0) {
                "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${card.dexNumber}.png"
            } else ""

            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl.isNotEmpty()) {
                    AsyncImage(
                        url = imageUrl,
                        contentDescription = card.name,
                        modifier = Modifier.size(54.dp)
                    )
                } else {
                    Text("Poké", color = Color.Gray, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (card.dexNumber > 0) "#${String.format("%03d", card.dexNumber)}" else "#???",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = card.name.capitalize(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(getRarityColor(card.type))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = card.type,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "P${card.page} S${card.slot}",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                    // Show grade chip if present
                    if (card.grade > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E293B))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFEAB308),
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = card.grade.let {
                                    if (it == it.toLong().toDouble()) it.toLong().toString() else "%.1f".format(it)
                                },
                                color = Color(0xFFEAB308),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        // Shimmer overlay
        RarityShimmerOverlay(
            rarity = card.type,
            modifier = Modifier.matchParentSize()
        )
    }
}
