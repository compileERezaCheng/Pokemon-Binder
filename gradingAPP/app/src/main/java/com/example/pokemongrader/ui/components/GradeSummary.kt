package com.example.pokemongrader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pokemongrader.data.CardRank
import org.json.JSONObject
import java.util.Locale

@Composable
fun GradeSummary(grade: Double, rarity: String, condition: String, gradeDetails: String, modifier: Modifier = Modifier) {
    val details = remember(gradeDetails) {
        runCatching { JSONObject(gradeDetails) }.getOrNull()
    }
    val criteria = details?.optJSONObject("criteria")
    val canShowDetails = !gradeDetails.isBlank()
    var expanded by rememberSaveable(gradeDetails) { mutableStateOf(false) }
    val validGrade = grade.isFinite() && grade in 1.0..10.0
    val rank = CardRank.calculate(grade.takeIf { validGrade }, rarity)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (validGrade) "Grade" else "Not assessed", color = Color.White, fontWeight = FontWeight.Bold)
                if (validGrade) Text(String.format(Locale.US, "%.1f/10", grade), color = Color(0xFFEAB308), fontWeight = FontWeight.Bold)
            }
            listOf("centering" to "Centering", "corners" to "Corners", "edges" to "Edges", "surface" to "Surface").forEach { (key, label) ->
                val score = criteria?.optJSONObject(key)?.optDouble("score", Double.NaN) ?: Double.NaN
                if (score.isFinite() && score in 1.0..10.0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                        Text(String.format(Locale.US, "%.1f/10", score), color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            if (condition.isNotBlank() && !condition.equals("UNASSESSED", true)) {
                Text("Condition: " + condition, color = Color(0xFFCBD5E1), fontSize = 13.sp)
            }
            if (rank != "No Rank") {
                val color = when (rank) {
                    "Bronze" -> Color(0xFFB77946)
                    "Silver" -> Color(0xFF94A3B8)
                    "Gold" -> Color(0xFFEAB308)
                    "Platinum" -> Color(0xFF38BDF8)
                    else -> Color(0xFFA78BFA)
                }
                Text(rank + " rank", color = color, fontWeight = FontWeight.Bold)
            }
            if (canShowDetails) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide details" else "More details")
                }
                if (expanded) {
                    val reason = details?.optString("reason").orEmpty()
                    if (reason.isNotBlank()) Text(reason, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    listOf("centering" to "Centering", "corners" to "Corners", "edges" to "Edges", "surface" to "Surface").forEach { (key, label) ->
                        val evidence = criteria?.optJSONObject(key)?.optString("evidence").orEmpty()
                        if (evidence.isNotBlank()) Text(label + ": " + evidence, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    }
                    if (details == null) Text(gradeDetails, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                }
            }
        }
    }
}
