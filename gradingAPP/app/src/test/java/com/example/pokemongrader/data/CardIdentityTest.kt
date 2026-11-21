package com.example.pokemongrader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CardIdentityTest {
    @Test fun printIdentityGroupsCopiesButKeepsLanguagesFormsAndLocalesSeparate() {
        val card = Card(1, 1, 570, "Zorua", "Rare", "NM", "", "today",
            speciesName = "Zorua", form = "Hisuian", cardLanguage = "ja",
            catalogProvider = "TCGdex", catalogLocale = "ja", catalogCardId = "sv10-1", setId = "sv10", cardNumber = "1")

        assertEquals(card.printIdentityKey(), card.copy(copyId = "copy-2").printIdentityKey())
        assertNotEquals(card.printIdentityKey(), card.copy(cardLanguage = "en").printIdentityKey())
        assertNotEquals(card.printIdentityKey(), card.copy(form = "").printIdentityKey())
        assertNotEquals(card.printIdentityKey(), card.copy(catalogLocale = "en").printIdentityKey())
    }

    @Test fun legacyCatalogIdKeepsCatalogContextButPhysicalLanguageUnknown() {
        val legacy = JSONObject()
            .put("Name", "Charizard")
            .put("Dex Number", 6)
            .put("Catalog Card ID", "base1-4")
            .put("Copy ID", "legacy-copy-1")

        val card = Card.fromJsonObject(legacy)
        val saved = card.toJsonObject()

        assertEquals("TCGdex", card.catalogProvider)
        assertEquals("en", card.catalogLocale)
        assertEquals("", card.cardLanguage)
        assertEquals("legacy-copy-1", card.copyId)
        assertEquals("TCGdex", saved.optString("Catalog Provider"))
        assertEquals("en", saved.optString("Catalog Locale"))
        assertEquals(false, saved.has("Card Language"))

        val manual = Card.fromJsonObject(JSONObject().put("Card Language", "ja"))
        assertEquals("", manual.catalogProvider)
        assertEquals("", manual.catalogLocale)
        assertEquals("ja", manual.cardLanguage)
    }

    @Test fun printIdentitySurvivesJsonRoundTripWithoutOverloadingType() {
        val source = JSONObject().apply {
            put("Page", 64)
            put("Slot", 3)
            put("Dex Number", 570)
            put("Name", "ヒスイゾロア")
            put("Species Name", "Zorua")
            put("Form", "Hisuian")
            put("Card Language", "ja")
            put("Catalog Provider", "TCGdex")
            put("Catalog Locale", "ja")
            put("Catalog Card ID", "sv10-1")
            put("Set ID", "sv10")
            put("Set Name", "Black Bolt")
            put("Card Number", "1")
            put("Catalog Variant", "normal")
            put("Type", "Mega Hyper Rare")
            put("Condition", "NM")
            put("Notes", "fixture")
            put("Date Added", "today")
            put("Copy ID", "copy-ja-1")
        }

        val saved = Card.fromJsonObject(source).toJsonObject()

        assertEquals("ヒスイゾロア", saved.optString("Name"))
        assertEquals("Zorua", saved.optString("Species Name"))
        assertEquals("Hisuian", saved.optString("Form"))
        assertEquals("ja", saved.optString("Card Language"))
        assertEquals("TCGdex", saved.optString("Catalog Provider"))
        assertEquals("ja", saved.optString("Catalog Locale"))
        assertEquals("sv10-1", saved.optString("Catalog Card ID"))
        assertEquals("normal", saved.optString("Catalog Variant"))
        assertEquals("Mega Hyper Rare", saved.optString("Type"))
        assertEquals("copy-ja-1", saved.optString("Copy ID"))
    }
}
