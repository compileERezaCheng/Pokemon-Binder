package com.example.pokemongrader.ui.main

import com.example.pokemongrader.data.MarketClient
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
}
