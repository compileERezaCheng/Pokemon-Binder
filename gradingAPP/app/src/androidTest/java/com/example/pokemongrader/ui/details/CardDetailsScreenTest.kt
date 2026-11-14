package com.example.pokemongrader.ui.details

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CardDetailsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
        private val card = Card(1, 1, 25, "Pikachu", "Normal", "NM", "", "today", copyId = "copy-1")
        override val isLoggedIn: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val cards: StateFlow<List<Card>> = MutableStateFlow(listOf(card)).asStateFlow()
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
        override suspend fun updateCard(card: Card) = true
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
