package com.example.pokemongrader.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay

class MarketPricesTest {
    @Test fun editionPricesRemainVisibleWithoutClaimingAnUnconfirmedVariantAndZeroIsNotAQuote() {
        val card = CatalogCard("xyp-XY202", "Pikachu", "xyp", "XY Promos", "XY202", "", "")
        val json = """{"id":"xyp-XY202","localId":"XY202","set":{"id":"xyp"},
          "variants_detailed":[{"type":"holo","pricing":{"cardmarket":{"unit":"EUR","trend-holo":0}}}],
          "pricing":{"cardmarket":{"unit":"EUR","avg":79.71,"low":12.99,"trend":78.07}}}"""
        val prices = MarketPrices.parse(json, card, "now")
        assertEquals(3, prices.size)
        assertTrue(prices.all { it.variant.contains("finish unconfirmed") })
        assertFalse(prices.any { it.amount == 0.0 })
    }
    @Test fun cacheReusesConcurrentRequestsAndKeepsExpiredDataOnNetworkFailure() = runTest {
        var now = 0L
        var calls = 0
        var offline = false
        val client = MarketClient(fetch = {
            calls++
            delay(10)
            if (offline) error("offline")
            "{}"
        }, clock = { now })
        val a = async { client.get("cards/base1-4") }
        val b = async { client.get("cards/base1-4") }
        assertEquals(a.await(), b.await())
        assertEquals(1, calls)
        now = 3600001L
        offline = true
        assertTrue(client.get("cards/base1-4").stale)
        assertEquals(2, calls)
    }
    @Test fun pricesRequireTheSelectedPrintingAndKeepCurrenciesAndFinishes() {
        val card = CatalogCard("base1-4", "Charizard", "base1", "Base", "4", "", "")
        val json = """{"id":"base1-4","localId":"4","set":{"id":"base1"},"pricing":{
          "cardmarket":{"unit":"EUR","updated":"2026-10-20","avg":12,"avg-holo":25},
          "tcgplayer":{"unit":"USD","holofoil":{"marketPrice":30,"lowPrice":null}}}}"""
        val rows = MarketPrices.parse(json, card, "queried")
        assertEquals(listOf("EUR", "EUR", "USD"), rows.map { it.currency })
        assertEquals("Average", rows[0].type)
        assertEquals("Foil (finish unspecified)", rows[1].variant)
        assertEquals("Market price", rows[2].type)
        assertEquals("Consulted", rows[2].dateLabel)
        assertTrue(MarketPrices.parse(json, card.copy(setId = "other"), "queried").isEmpty())
        assertTrue(MarketPrices.parse("""{"id":"base1-4","pricing":null}""", card, "queried").isEmpty())
    }

    @Test fun malformedAndNegativePricesAreUnavailable() {
        val card = CatalogCard("base1-4", "Charizard", "base1", "Base", "4", "", "")
        assertTrue(MarketPrices.parse("""{"id":"base1-4","localId":"4","set":{"id":"base1"},
          "pricing":{"cardmarket":{"unit":"EUR","avg":"12","low":-1}}}""", card, "now").isEmpty())
    }
}
