package com.example.pokemongrader.data

object CardRank {
    private val rarityPoints = mapOf(
        "common" to 0,
        "uncommon" to 25,
        "rare" to 50,
        "rare holo" to 50,
        "holo rare" to 50,
        "holo rare v" to 50,
        "holo rare vmax" to 50,
        "holo rare vstar" to 50,
        "rare holo ex" to 50,
        "rare holo gx" to 50,
        "rare holo lv.x" to 50,
        "rare holo v" to 50,
        "rare holo vmax" to 50,
        "rare holo vstar" to 50,
        "ultra rare" to 75,
        "double rare" to 75,
        "illustration rare" to 75,
        "secret rare" to 100,
        "rare secret" to 100,
        "special illustration rare" to 100,
        "hyper rare" to 100
    )

    @JvmStatic
    fun calculate(grade: Double?, rarity: String): String {
        if (grade == null || !grade.isFinite() || grade !in 1.0..10.0) return "No Rank"
        val rarityScore = rarityPoints[rarity.trim().lowercase()] ?: return "No Rank"
        val score = 0.8 * (grade * 10.0) + 0.2 * rarityScore
        return when {
            score < 50.0 -> "Bronze"
            score < 65.0 -> "Silver"
            score < 80.0 -> "Gold"
            score < 90.0 -> "Platinum"
            else -> "Diamond"
        }
    }
}
