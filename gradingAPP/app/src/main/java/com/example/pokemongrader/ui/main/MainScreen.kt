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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
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

fun getRarityRank(rarity: String): Int {
    val idx = RarityOrder.indexOf(rarity)
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
            // BlendMode.Screen requires API 29+; skip shimmer draw on older devices
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
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.doInput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.connect()
                val input: InputStream = conn.inputStream
                bitmap = BitmapFactory.decodeStream(input)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier.background(Color.DarkGray))
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
        AsyncImage(url = url, contentDescription = "Profile", modifier = modifier)
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
            Box(modifier = modifier.background(Color.DarkGray))
        }
    } else {
        // Default Pokémon Dex image
        val pUrl = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/$dex.png"
        AsyncImage(url = pUrl, contentDescription = "Profile", modifier = modifier)
    }
}

@Composable
fun MainScreen(
    repository: DataRepository,
    onNavigateToScan: () -> Unit,
    onNavigateToCardDetails: (Int, Int, String) -> Unit,
    onNavigateToAccountSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cards by repository.cards.collectAsStateWithLifecycle()
    val isLoading by repository.isLoading.collectAsStateWithLifecycle()
    val username by repository.username.collectAsStateWithLifecycle()
    val profilePicSource by repository.profilePicSource.collectAsStateWithLifecycle()
    val profileFeaturedDex by repository.profileFeaturedDex.collectAsStateWithLifecycle()
    val profileImageUrl by repository.profileImageUrl.collectAsStateWithLifecycle()
    val profileImageBase64 by repository.profileImageBase64.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf("binder") } // "binder" or "collection"

    // Page selection for Binder Grid
    var currentPage by remember { mutableStateOf(1) }

    // Filter and Sort states for Collection List
    var searchQuery by remember { mutableStateOf("") }
    var selectedRarityFilter by remember { mutableStateOf("All") }
    var selectedSortOption by remember { mutableStateOf("Dex Number") }
    var showRepeated by remember { mutableStateOf(false) }

    var expandedRarityFilter by remember { mutableStateOf(false) }
    var expandedSortOption by remember { mutableStateOf(false) }

    val filteredCards = remember(cards, searchQuery, selectedRarityFilter, selectedSortOption, showRepeated) {
        var list = cards

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase().trim()
            list = list.filter { it.name.lowercase().contains(q) || it.notes.lowercase().contains(q) }
        }

        if (selectedRarityFilter != "All") {
            list = list.filter { it.type == selectedRarityFilter }
        }

        if (!showRepeated) {
            val grouped = list.groupBy { if (it.dexNumber != 0) it.dexNumber else it.name.lowercase() }
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

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF020617))) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            // Sync Profile Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0x1F3B82F6), Color(0x1F1D4ED8))))
                    .border(1.dp, Color(0x333B82F6), RoundedCornerShape(16.dp))
                    .clickable { onNavigateToAccountSettings() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileImage(
                    source = profilePicSource,
                    dex = profileFeaturedDex,
                    url = profileImageUrl,
                    base64Str = profileImageBase64,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color(0xFF3B82F6), CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = username.capitalize(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "PokeBinder Champion",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = { coroutineScope.launch { repository.fetchRemote() } }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F172A))
                    .padding(4.dp)
            ) {
                TabButton(
                    text = "BINDER",
                    isSelected = activeTab == "binder",
                    onClick = { activeTab = "binder" },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    text = "COLLECTION",
                    isSelected = activeTab == "collection",
                    onClick = { activeTab = "collection" },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (activeTab == "binder") {
                // Binder Grid View
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Page $currentPage", color = Color.White, fontWeight = FontWeight.Bold)
                        Row {
                            TextButton(onClick = { if (currentPage > 1) currentPage-- }) {
                                Text("PREV", color = Color(0xFF3B82F6))
                            }
                            TextButton(onClick = { currentPage++ }) {
                                Text("NEXT", color = Color(0xFF3B82F6))
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(9) { index ->
                            val slotIndex = index + 1
                            val pocketCards = cards.filter { it.page == currentPage && it.slot == slotIndex }
                            PocketCell(
                                slot = slotIndex,
                                cards = pocketCards,
                                onClick = {
                                    if (pocketCards.isNotEmpty()) {
                                        val topCard = pocketCards.minByOrNull { getRarityRank(it.type) }!!
                                        onNavigateToCardDetails(topCard.page, topCard.slot, topCard.dateAdded)
                                    } else {
                                        repository.prefilledPage = currentPage
                                        repository.prefilledSlot = slotIndex
                                        onNavigateToScan()
                                    }
                                }
                            )
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
                        // Rarity Filter
                        Box(modifier = Modifier.weight(1f)) {
                            Button(
                                onClick = { expandedRarityFilter = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(selectedRarityFilter, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

                        // Sort Option
                        Box(modifier = Modifier.weight(1f)) {
                            Button(
                                onClick = { expandedSortOption = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(selectedSortOption, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        contentPadding = PaddingValues(bottom = 80.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredCards) { card ->
                            CollectionRow(
                                card = card,
                                onClick = { onNavigateToCardDetails(card.page, card.slot, card.dateAdded) }
                            )
                        }
                    }
                }
            }
        }

        // Floating Scan Button - Replaced with "+" Icon
        FloatingActionButton(
            onClick = onNavigateToScan,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = Color(0xFFEF4444)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Card", tint = Color.White)
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color(0xFFEF4444))
        }
    }
}

@Composable
fun TabButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFF1E293B) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else Color.Gray,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
fun PocketCell(
    slot: Int,
    cards: List<Card>,
    onClick: () -> Unit
) {
    val topCard = remember(cards) {
        if (cards.isEmpty()) null else cards.minByOrNull { getRarityRank(it.type) }
    }
    
    Box(
        modifier = Modifier
            .aspectRatio(0.7f)
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
            // Display Pokémon image
            val imageUrl = if (topCard.dexNumber > 0) {
                "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${topCard.dexNumber}.png"
            } else ""

            // Shimmer overlay for rarity effect
            RarityShimmerOverlay(
                rarity = topCard.type,
                modifier = Modifier.matchParentSize()
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
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
                        modifier = Modifier.size(54.dp).weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Text(
                    text = topCard.name.capitalize(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        } else {
            // Empty Cell state
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("+", color = Color(0x66FFFFFF), fontSize = 24.sp)
                Spacer(modifier = Modifier.height(4.dp))
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
            val imageUrl = if (card.dexNumber > 0) {
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
                }
            }
        }
        // Shimmer overlay for rarity effect
        RarityShimmerOverlay(
            rarity = card.type,
            modifier = Modifier.matchParentSize()
        )
    }
}
