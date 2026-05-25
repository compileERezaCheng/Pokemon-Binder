package com.example.pokemongrader.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pokemongrader.data.DataRepository
import com.example.pokemongrader.ui.components.PokemonCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailsScreen(
    page: Int,
    slot: Int,
    dateAdded: String,
    repository: DataRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cards by repository.cards.collectAsStateWithLifecycle()
    val card = remember(cards, page, slot, dateAdded) {
        cards.find { it.page == page && it.slot == slot && it.dateAdded == dateAdded }
    }
    
    val coroutineScope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PokemonCard(
                    card = card,
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Info Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A))
                        .padding(16.dp)
                ) {
                    DetailRow("Name", card.name)
                    DetailRow("Dex Number", if (card.dexNumber > 0) "#${card.dexNumber}" else "Custom")
                    DetailRow("Rarity", card.type)
                    DetailRow("Condition", card.condition)
                    DetailRow("Location", "Page ${card.page}, Slot ${card.slot}")
                    DetailRow("Date Added", card.dateAdded)
                    
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
                                repository.removeCard(it)
                                onNavigateBack()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("CANCEL")
                }
            },
            containerColor = Color(0xFF0F172A),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
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
