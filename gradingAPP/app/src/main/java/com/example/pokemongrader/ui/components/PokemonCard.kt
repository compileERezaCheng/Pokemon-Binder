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
import com.example.pokemongrader.ui.main.AsyncImage
import com.example.pokemongrader.ui.main.getRarityColor
import com.example.pokemongrader.ui.main.capitalize

@Composable
fun PokemonCard(
    card: Card,
    modifier: Modifier = Modifier
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

    Box(
        modifier = modifier
            .aspectRatio(0.714f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B))
            .border(2.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
    ) {
        // Holographic Shimmer Layer
        if (isHolo || isFullArt) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.2f),
                                Color.White.copy(alpha = 0.5f),
                                Color.White.copy(alpha = 0.2f),
                                Color.Transparent
                            ),
                            start = Offset(size.width * sweepProgress, 0f),
                            end = Offset(size.width * (sweepProgress + 0.5f), size.height)
                        )
                        drawRect(brush = brush, blendMode = BlendMode.Overlay)
                    }
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.name.capitalize(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = if (card.dexNumber > 0) "#${String.format("%03d", card.dexNumber)}" else "Custom",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Artwork
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (card.dexNumber > 0) {
                    AsyncImage(
                        url = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${card.dexNumber}.png",
                        contentDescription = card.name,
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                    )
                } else {
                    Text("No Artwork", color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer / Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = card.type,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = card.condition,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
