package com.example.pokemongrader.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.CardRank
import com.example.pokemongrader.data.TcgDexClient
import com.example.pokemongrader.ui.main.AsyncImage
import com.example.pokemongrader.ui.main.getRarityColor
import com.example.pokemongrader.ui.main.capitalize

@Composable
fun PokemonCard(
    card: Card,
    modifier: Modifier = Modifier,
    onImageClick: ((String) -> Unit)? = null,
    onImageFailure: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "holographic")
    val sweepProgress by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    val rarity = card.type.lowercase()
    val isHolo = rarity.contains("holo") || rarity.contains("foil") || rarity.contains("rare")
    val isFullArt = rarity.contains("special") || rarity.contains("illustration") || rarity.contains("hyper") || rarity.contains("secret")
    val accentColor = getRarityColor(card.type)
    val rank = CardRank.calculate(card.grade.takeIf { it > 0.0 }, card.catalogRarity)
    val rankColor = when (rank) {
        "Bronze" -> Color(0xFFB77946)
        "Silver" -> Color(0xFF94A3B8)
        "Gold" -> Color(0xFFEAB308)
        "Platinum" -> Color(0xFF38BDF8)
        "Diamond" -> Color(0xFFA78BFA)
        else -> Color.Gray
    }
    val speciesImage = if (card.dexNumber > 0) "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/" + card.dexNumber + ".png" else null
    val catalogImage = TcgDexClient.cardImageUrl(card.imageUrl)
    val image = catalogImage ?: if (card.catalogCardId.isBlank()) speciesImage else null

    LaunchedEffect(card.copyId, card.catalogCardId, card.imageUrl, card.imageSource) {
        if (card.catalogCardId.isNotBlank() && catalogImage == null && card.imageSource != "Scrydex") onImageFailure?.invoke()
    }

    Box(
        modifier = modifier
            .aspectRatio(0.714f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B))
            .border(2.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
    ) {
        if (isHolo || isFullArt) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent, Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.5f),
                                Color.White.copy(alpha = 0.2f), Color.Transparent
                            ),
                            start = Offset(size.width * sweepProgress, 0f),
                            end = Offset(size.width * (sweepProgress + 0.5f), size.height)
                        )
                        drawRect(brush = brush, blendMode = BlendMode.Overlay)
                    }
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(card.name.capitalize(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(if (card.dexNumber > 0) "#" + String.format("%03d", card.dexNumber) else "Custom", color = Color.LightGray, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    AsyncImage(
                        url = image,
                        contentDescription = card.name + if (catalogImage != null) " card" else " Pokémon",
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        placeholder = "Imagem indisponível",
                        onClick = if (catalogImage != null && onImageClick != null) {
                            { onImageClick(catalogImage) }
                        } else null,
                        onError = if (card.catalogCardId.isNotBlank() && card.imageSource != "Scrydex") onImageFailure else null
                    )
                } else {
                    Text("Image unavailable", color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(accentColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(card.type, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (rank != "No Rank") {
                        Text(rank + " rank", color = rankColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(card.condition, color = Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
