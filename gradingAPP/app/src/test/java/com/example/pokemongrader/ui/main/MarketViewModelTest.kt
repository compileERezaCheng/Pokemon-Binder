package com.example.pokemongrader.ui.main

import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.MarketClient
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketViewModelTest {
    @Test fun newSearchCancelsDebounceAndAnOldResponseCannotReplaceIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val old = CompletableDeferred<String>()
            val started = CompletableDeferred<Unit>()
            val client = MarketClient(fetch = { path ->
                when {
                    path.contains("old") -> { started.complete(Unit); withContext(NonCancellable) { old.await() } }
                    path == "sets" -> "[]"
                    else -> """[{"id":"base1-4","name":"new","localId":"4"}]"""
                }
            })
            val model = MarketViewModel(client)
            model.search("old", "", "")
            advanceTimeBy(350); runCurrent()
            assertTrue(started.isCompleted)
            model.search("new", "", "")
            old.complete("[]")
            advanceUntilIdle()
            assertEquals("new", model.state.value.name)
            assertEquals(listOf("new"), model.state.value.cards.map { it.name })
            assertFalse(model.state.value.loading)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun openingSavedPrintingUsesItsCatalogLocaleAndExactId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val paths = mutableListOf<String>()
            val client = MarketClient(fetch = { path ->
                paths += path
                throw IOException("offline test fixture")
            })
            val card = Card(
                page = 1, slot = 1, dexNumber = 570, name = "ヒスイゾロア", type = "Rare",
                condition = "NM", notes = "", dateAdded = "today", cardLanguage = "ja",
                catalogProvider = "TCGdex", catalogLocale = "ja", catalogCardId = "sv10-1",
                setId = "sv10", setName = "Black Bolt", cardNumber = "1"
            )
            val model = MarketViewModel(client)

            model.openCard(card)

            assertEquals("ヒスイゾロア", model.state.value.name)
            assertEquals("Black Bolt", model.state.value.set)
            assertEquals("1", model.state.value.number)
            assertEquals("ja", model.state.value.locale)
            assertEquals("sv10-1", model.state.value.selected?.id)
            advanceUntilIdle()
            assertEquals(listOf("ja/cards/sv10-1"), paths)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun missingCatalogLocaleDoesNotTreatPhysicalLanguageAsEnglish() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val paths = mutableListOf<String>()
            val model = MarketViewModel(MarketClient(fetch = { path -> paths += path; "[]" }))
            val card = Card(
                page = 1, slot = 1, dexNumber = 570, name = "ヒスイゾロア", type = "Rare",
                condition = "NM", notes = "", dateAdded = "today", cardLanguage = "ja",
                setName = "Black Bolt", cardNumber = "1"
            )

            model.openCard(card)

            assertEquals(card.name, model.state.value.name)
            assertEquals(card.setName, model.state.value.set)
            assertEquals(card.cardNumber, model.state.value.number)
            assertEquals("", model.state.value.locale)
            assertNull(model.state.value.selected)
            assertTrue(paths.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}
