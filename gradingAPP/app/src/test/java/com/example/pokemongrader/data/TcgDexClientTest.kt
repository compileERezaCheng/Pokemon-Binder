package com.example.pokemongrader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TcgDexClientTest {
    @Test fun cardSearchUsesTheCatalogImage() {
        assertEquals(
            "https://assets.tcgdex.net/en/base/base1/4/low.webp",
            TcgDexClient.imageUrlFromCards("""[{"image":"https://assets.tcgdex.net/en/base/base1/4"}]""")
        )
    }

    @Test fun cardImageUrlSurvivesCollectionSerialization() {
        val imageUrl = "https://assets.tcgdex.net/en/base/base1/4/low.webp"
        val card = Card(1, 1, 6, "charizard", "Normal", "NM", "", "today", imageUrl = imageUrl)

        assertEquals(imageUrl, Card.fromJsonObject(card.toJsonObject()).imageUrl)
    }    @Test fun ambiguousSearchDoesNotChooseFirstEdition() {
        org.junit.Assert.assertNull(TcgDexClient.imageUrlFromCards("""[
            {"image":"https://assets.tcgdex.net/en/base/base1/4"},
            {"image":"https://assets.tcgdex.net/en/neo/neo3/12"}
        ]"""))
    }

    @Test fun catalogAndGradeMetadataSurviveCardJsonRoundTrip() {
        val details = """{"assessable":true,"criteria":{"centering":{"score":8,"evidence":"even"}}}"""
        val source = org.json.JSONObject().apply {
            put("Page", 2); put("Slot", 3); put("Dex Number", 25); put("Name", "pikachu")
            put("Type", "Rare Holo"); put("Condition", "LP"); put("Notes", "personal note")
            put("Date Added", "today"); put("Grade", 8.5); put("Grade Details", details)
            put("Catalog Card ID", "base1-25"); put("Set ID", "base1"); put("Set Name", "Base")
            put("Card Number", "25"); put("Catalog Rarity", "Rare Holo")
            put("Image URL", "https://assets.tcgdex.net/en/base/base1/25/low.webp")
        }
        val saved = Card.fromJsonObject(source).toJsonObject()
        assertEquals("base1-25", saved.optString("Catalog Card ID"))
        assertEquals("Base", saved.optString("Set Name"))
        assertEquals(details, saved.optString("Grade Details"))
        assertEquals("personal note", saved.optString("Notes"))
    }

    private fun rank(grade: Double?, rarity: String): String {
        val method = Class.forName("com.example.pokemongrader.data.CardRank")
            .getMethod("calculate", java.lang.Double::class.java, String::class.java)
        return method.invoke(null, grade, rarity) as String
    }

    @Test fun rankUsesTheDocumentedThresholdsAndRejectsUnknownInputs() {
        assertEquals("Silver", rank(4.375, "Ultra Rare"))
        assertEquals("Bronze", rank(4.37, "Ultra Rare"))
        assertEquals("Gold", rank(6.25, "Ultra Rare"))
        assertEquals("Silver", rank(6.24, "Ultra Rare"))
        assertEquals("Platinum", rank(8.125, "Ultra Rare"))
        assertEquals("Gold", rank(8.12, "Ultra Rare"))
        assertEquals("Diamond", rank(9.375, "Ultra Rare"))
        assertEquals("Platinum", rank(9.37, "Ultra Rare"))
        assertEquals("No Rank", rank(null, "Ultra Rare"))
        assertEquals("No Rank", rank(8.5, "Normal"))
        assertEquals("No Rank", rank(11.0, "Ultra Rare"))
    }


}
