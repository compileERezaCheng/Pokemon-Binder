package com.example.pokemongrader.ui.main

import com.example.pokemongrader.data.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MainScreenViewModelTest {

    @Test
    fun testParseCollection_NormalArray() {
        val json = """
            [
                {"Page": 1, "Slot": 1, "Dex Number": 25, "Name": "pikachu", "Condition": "NM", "Notes": "Holo", "Date Added": "2026-05-24 12:00:00"},
                {"Page": 1, "Slot": 2, "Dex Number": 6, "Name": "charizard", "Condition": "MINT", "Notes": "Shiny", "Date Added": "2026-05-24 12:05:00"}
            ]
        """.trimIndent()

        val cards = Card.parseCollection(json)
        assertEquals(2, cards.size)
        assertEquals("pikachu", cards[0].name)
        assertEquals(25, cards[0].dexNumber)
        assertEquals("charizard", cards[1].name)
        assertEquals("MINT", cards[1].condition)
    }

    @Test
    fun testParseCollection_SparseArrayWithNulls() {
        // Firebase Realtime Database can return arrays with 'null' if indexes are not contiguous
        val json = """
            [
                null,
                {"Page": 1, "Slot": 2, "Dex Number": 6, "Name": "charizard", "Condition": "MINT", "Notes": "Shiny", "Date Added": "2026-05-24 12:05:00"},
                null
            ]
        """.trimIndent()

        val cards = Card.parseCollection(json)
        assertEquals(1, cards.size)
        assertEquals("charizard", cards[0].name)
        assertEquals(1, cards[0].page)
        assertEquals(2, cards[0].slot)
    }

    @Test
    fun testParseCollection_MapRepresentation() {
        // Firebase might also return lists as map objects
        val json = """
            {
                "1": {"Page": 1, "Slot": 1, "Dex Number": 25, "Name": "pikachu", "Condition": "NM", "Notes": "Holo"},
                "2": {"Page": 1, "Slot": 2, "Dex Number": 6, "Name": "charizard", "Condition": "MINT", "Notes": "Shiny"}
            }
        """.trimIndent()

        val cards = Card.parseCollection(json)
        assertEquals(2, cards.size)
        val names = cards.map { it.name }
        assertTrue(names.contains("pikachu"))
        assertTrue(names.contains("charizard"))
    }

    @Test
    fun testSerializeCollection() {
        val cards = listOf(
            Card(1, 1, 25, "pikachu", "Normal", "NM", "Holo", "2026-05-24 12:00:00")
        )
        val json = Card.serializeCollection(cards)
        assertTrue(json.contains("\"Name\":\"pikachu\""))
        assertTrue(json.contains("\"Page\":1"))
        assertTrue(json.contains("\"Slot\":1"))
    }

    @Test
    fun testCalculateAverageGrade() {
        val notes1 = "Grade: 9.5/10. Near-perfect borders."
        val notes2 = "Grade: 8.5/10. Light corner whitening."
        val notes3 = "No grade notes here."

        val cards = listOf(
            Card(1, 1, 25, "pikachu", "Normal", "NM", notes1, ""),
            Card(1, 2, 6, "charizard", "Normal", "EX", notes2, ""),
            Card(1, 3, 1, "bulbasaur", "Normal", "LP", notes3, "")
        )

        val avg = calculateAverageGrade(cards)
        assertEquals(9.0, avg, 0.01)
    }

    private fun calculateAverageGrade(cards: List<Card>): Double {
        var sum = 0.0
        var count = 0
        val regex = Regex("Grade: (\\d+\\.\\d+)/10")
        cards.forEach { card ->
            val match = regex.find(card.notes)
            if (match != null) {
                sum += match.groupValues[1].toDoubleOrNull() ?: 0.0
                count++
            }
        }
        return if (count > 0) sum / count else 0.0
    }
}
