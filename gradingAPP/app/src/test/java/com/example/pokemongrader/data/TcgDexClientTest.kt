package com.example.pokemongrader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TcgDexClientTest {
    @Test fun printingsAreSortedAlphabeticallyBySetName() {
        val printings = listOf(
            CatalogCard("neo3-12", "Pikachu", "neo3", "Neo Revelation", "12", "", ""),
            CatalogCard("base1-25", "Pikachu", "base1", "Base", "25", "", "")
        )

        val sorted = TcgDexClient.sortBySetName(printings)
        assertEquals(listOf("Base", "Neo Revelation"), sorted.map { it.setName })
        assertEquals(listOf("Neo Revelation"), TcgDexClient.filterBySetName(sorted, "neo").map { it.setName })
    }

    @Test fun setArtworkIsNotTreatedAsACardImage() {
        listOf(
            "https://assets.tcgdex.net/en/base/base1/logo",
            "https://assets.tcgdex.net/en/base/base1/symbol.png",
            "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png",
            "https://assets.tcgdex.net/en/base/base1/4/icon.png"
        ).forEach { image ->
            org.junit.Assert.assertNull(image, TcgDexClient.imageUrlFromCards("""[{"image":"$image"}]"""))
        }
    }

    @Test fun printingArtworkUsesTheSuppliedImagePathAndPreservesItsNumber() {
        val cards = TcgDexClient.parseBriefCards("""[
            {"id":"cel25-17a","localId":"17a","name":"Umbreon ☆",
             "image":"https://assets.tcgdex.net/en/swsh/cel25/47"},
            {"id":"swsh11tg-TG03","localId":"TG03","name":"Charizard"},
            {"id":"base1-4","localId":"4","name":"Charizard","image":null}
        ]""", mapOf("cel25" to "Celebrations", "swsh11tg" to "Lost Origin Trainer Gallery"))

        assertEquals("cel25-17a", cards[0].id)
        assertEquals("17a", cards[0].number)
        assertEquals("Celebrations", cards[0].setName)
        assertEquals("https://assets.tcgdex.net/en/swsh/cel25/47/low.webp", cards[0].imageUrl)
        assertEquals("", cards[1].imageUrl)
        assertEquals("", cards[2].imageUrl)
        org.junit.Assert.assertNull(TcgDexClient.uniqueCard(cards, "Charizard"))
    }

    @Test fun catalogSearchKeepsExAndMegaVariantsButRejectsNameCollisions() {
        assertEquals(true, TcgDexClient.isPokemonCardNameMatch("Charizard ex", "Charizard"))
        assertEquals(true, TcgDexClient.isPokemonCardNameMatch("Mega Charizard X ex", "Charizard"))
        assertEquals(true, TcgDexClient.isPokemonCardNameMatch("M Charizard-EX", "Charizard"))
        assertEquals(false, TcgDexClient.isPokemonCardNameMatch("Charizard Spirit Link", "Charizard"))
        assertEquals(false, TcgDexClient.isPokemonCardNameMatch("Charizardite ex", "Charizard"))
    }

    @Test fun scrydexMatchRequiresSameSetNumberLanguageAndVariantAndRejectsAmbiguity() {
        val tcgdex = """{
            "id":"me1-084","name":"M Charizard-EX","localId":"084","stage":"MEGA","suffix":"EX",
            "set":{"id":"me1","name":"Mega Evolution"}
        }"""
        val scrydex = """{
            "totalCount":5,"data":[
                {"id":"wrong-set","name":"M Charizard-EX","number":"84","printed_number":"084/132","language_code":"EN","subtypes":["MEGA"],"expansion":{"name":"Another set"},"images":[{"type":"front","large":"https://images.scrydex.com/pokemon/wrong-set/large"}]},
                {"id":"wrong-number","name":"M Charizard-EX","number":"85","printed_number":"085/132","language_code":"EN","subtypes":["MEGA"],"expansion":{"name":"Mega Evolution"},"images":[{"type":"front","large":"https://images.scrydex.com/pokemon/wrong-number/large"}]},
                {"id":"wrong-language","name":"M Charizard-EX","number":"84","printed_number":"084/132","language_code":"JA","subtypes":["MEGA"],"expansion":{"name":"Mega Evolution"},"images":[{"type":"front","large":"https://images.scrydex.com/pokemon/wrong-language/large"}]},
                {"id":"wrong-variant","name":"Charizard ex","number":"84","printed_number":"084/132","language_code":"EN","subtypes":[],"expansion":{"name":"Mega Evolution"},"images":[{"type":"front","large":"https://images.scrydex.com/pokemon/wrong-variant/large"}]},
                {"id":"scrydex-card-84","name":"M Charizard-EX","number":"84","printed_number":"084/132","language_code":"EN","subtypes":["MEGA"],"expansion":{"id":"different-id","name":"Mega Evolution"},"images":[{"type":"front","large":"https://images.scrydex.com/pokemon/scrydex-card-84/large"}]}
            ]
        }"""

        val match = TcgDexClient.matchScrydexImage(tcgdex, scrydex)
        assertEquals("scrydex-card-84", match?.cardId)
        assertEquals("https://images.scrydex.com/pokemon/scrydex-card-84/large", match?.imageUrl)
        assertEquals(null, TcgDexClient.matchScrydexImage(tcgdex, scrydex.replace("\"totalCount\":5", "\"totalCount\":6")))
    }

    @Test fun missingDetailArtworkDoesNotBorrowTheSetLogoOrAnotherPrinting() {
        val card = TcgDexClient.parseCardDetails("""{
            "id":"cel25cc-CC002","localId":"CC002","name":"Charizard",
            "set":{"id":"cel25cc","name":"Celebrations Classic Collection",
                   "logo":"https://assets.tcgdex.net/en/swsh/cel25/logo"},
            "variants":{"holo":true,"normal":false}
        }""")!!

        assertEquals("CC002", card.number)
        assertEquals("", card.imageUrl)
    }

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
            put("Image Source", "Scrydex"); put("Image Match ID", "base1-25-alt")
        }
        val saved = Card.fromJsonObject(source).toJsonObject()
        assertEquals("base1-25", saved.optString("Catalog Card ID"))
        assertEquals("Base", saved.optString("Set Name"))
        assertEquals(details, saved.optString("Grade Details"))
        assertEquals("personal note", saved.optString("Notes"))
        assertEquals("Scrydex", saved.optString("Image Source"))
        assertEquals("base1-25-alt", saved.optString("Image Match ID"))
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
