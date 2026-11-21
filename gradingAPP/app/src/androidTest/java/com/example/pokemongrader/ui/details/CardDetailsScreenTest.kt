package com.example.pokemongrader.ui.details

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class CardDetailsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun savedPrintingShowsSpeciesPhysicalLanguageAndCatalogLanguageSeparately() {
        compose.setContent {
            MaterialTheme {
                CardDetailsScreen(1, 1, "today", FakeRepository(), {}, copyId = "copy-1")
            }
        }

        compose.onNodeWithText("Species name").assertIsDisplayed()
        compose.onNodeWithText("Regional form").assertIsDisplayed()
        compose.onNodeWithText("Physical card language").assertIsDisplayed()
        compose.onNodeWithText("Catalog language").assertIsDisplayed()
        compose.onNodeWithText("Zorua").assertIsDisplayed()
        compose.onNodeWithText("Hisuian").assertIsDisplayed()
        compose.onAllNodesWithText("Japanese").assertCountEquals(2)
        compose.onNodeWithText("Market Prices").assertIsDisplayed()
    }

    @Test
    fun marketActionReturnsSavedCardWithoutChangingCollection() {
        val repository = FakeRepository()
        var marketCard: Card? = null

        compose.setContent {
            MaterialTheme {
                CardDetailsScreen(1, 1, "today", repository, {}, copyId = "copy-1", onNavigateToMarket = { marketCard = it })
            }
        }

        compose.onNodeWithText("Market Prices").performClick()
        compose.runOnIdle {
            assertEquals(repository.originalCard, marketCard)
            assertNull(repository.updatedCard)
            assertEquals(repository.originalCard, repository.cards.value.single())
        }
    }

    @Test
    fun identityEditCanBeCancelledWithoutChangingSavedCard() {
        val repository = FakeRepository()
        compose.setContent {
            MaterialTheme {
                CardDetailsScreen(1, 1, "today", repository, {}, copyId = "copy-1")
            }
        }

        compose.onNodeWithText("Edit identity").performClick()
        compose.onNodeWithTag("printed-name").performTextClearance()
        compose.onNodeWithTag("printed-name").performTextInput("Changed name")
        compose.onNodeWithText("Cancel").performClick()

        compose.runOnIdle {
            assertNull(repository.updatedCard)
            assertEquals(repository.originalCard, repository.cards.value.single())
        }
    }

    @Test
    fun changingPhysicalLanguageAndSavingKeepsCatalogLanguageSeparateAndClearsOldPrinting() {
        val repository = FakeRepository()
        compose.setContent {
            MaterialTheme {
                CardDetailsScreen(1, 1, "today", repository, {}, copyId = "copy-1")
            }
        }

        compose.onNodeWithText("Edit identity").performClick()
        compose.onNodeWithText("Physical card language").performClick()
        compose.onNodeWithText("English (en)").performClick()
        compose.onNodeWithText("Save identity").performClick()
        compose.waitUntil(5_000) { repository.updatedCard != null }

        compose.runOnIdle {
            val saved = repository.updatedCard!!
            assertEquals("en", saved.cardLanguage)
            assertEquals("ja", saved.catalogLocale)
            assertEquals("", saved.catalogCardId)
            assertEquals("", saved.catalogProvider)
            assertEquals("Black Bolt", saved.setName)
            assertEquals("1", saved.cardNumber)
            assertEquals("copy-1", saved.copyId)
        }
    }

    @Test
    fun failedDeleteKeepsTheDetailsOpenAndShowsTheSyncError() {
        val repository = FakeRepository()
        var navigatedBack = false

        compose.setContent {
            MaterialTheme {
                CardDetailsScreen(
                    page = 1,
                    slot = 1,
                    dateAdded = "today",
                    repository = repository,
                    onNavigateBack = { navigatedBack = true },
                    copyId = "copy-1"
                )
            }
        }

        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("DELETE").performClick()

        compose.onNodeWithText("Conflict detected. Your local delete is saved for recovery.").assertIsDisplayed()
        compose.runOnIdle { assertFalse(navigatedBack) }
    }

    private class FakeRepository : DataRepository {
        val originalCard = Card(1, 1, 570, "ヒスイゾロア", "Normal", "NM", "", "today", copyId = "copy-1",
            speciesName = "Zorua", form = "Hisuian", cardLanguage = "ja", catalogProvider = "TCGdex",
            catalogLocale = "ja", catalogCardId = "sv10-1", setId = "sv10", setName = "Black Bolt", cardNumber = "1")
        private val mutableCards = MutableStateFlow(listOf(originalCard))
        var updatedCard: Card? = null
        override val isLoggedIn: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val cards: StateFlow<List<Card>> = mutableCards.asStateFlow()
        override val isLoading: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val errorMessage = MutableStateFlow<String?>(null)
        override val username: StateFlow<String> = MutableStateFlow("Trainer").asStateFlow()
        override val profilePicSource: StateFlow<String> = MutableStateFlow("pokemon").asStateFlow()
        override val profileFeaturedDex: StateFlow<Int> = MutableStateFlow(25).asStateFlow()
        override val profileImageUrl: StateFlow<String> = MutableStateFlow("").asStateFlow()
        override val profileImageBase64: StateFlow<String> = MutableStateFlow("").asStateFlow()
        override var prefilledPage: Int? = null
        override var prefilledSlot: Int? = null

        override suspend fun login(email: String, password: String) = true
        override suspend fun register(email: String, password: String) = true
        override suspend fun addCard(card: Card) = true
        override suspend fun updateCard(card: Card): Boolean {
            updatedCard = card
            mutableCards.value = listOf(card)
            return true
        }
        override suspend fun removeCard(card: Card): Boolean {
            errorMessage.value = "Conflict detected. Your local delete is saved for recovery."
            return false
        }
        override suspend fun fetchRemote() = true
        override suspend fun refreshAndFetch() = true
        override suspend fun updateProfile(username: String, source: String, dex: Int, url: String, base64: String) = true
        override fun logout() = Unit
    }
}
