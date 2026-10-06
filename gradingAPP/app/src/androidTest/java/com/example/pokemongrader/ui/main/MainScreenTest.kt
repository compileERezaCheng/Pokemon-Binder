package com.example.pokemongrader.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.pokemongrader.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val fakeRepository = FakeDataRepository()

  @Test
  fun pageSurvivesStateRestoration() {
    val restoration = StateRestorationTester(composeTestRule)
    restoration.setContent {
      MainScreen(
        repository = fakeRepository,
        onNavigateToScan = {},
        onNavigateToCardDetails = { _, _, _ -> },
        onNavigateToAccountSettings = {}
      )
    }
    repeat(6) { composeTestRule.onNodeWithText("Next page").performClick() }
    composeTestRule.onNodeWithText("Page 7").assertExists()
    composeTestRule.onNodeWithText("COLLECTION").performClick()
    composeTestRule.onNodeWithText("All").performClick()
    composeTestRule.onNodeWithText("Rare").performClick()
    restoration.emulateSavedInstanceStateRestore()
    composeTestRule.onNodeWithText("Rare").assertExists()
    composeTestRule.onNodeWithText("BINDER").performClick()
    composeTestRule.onNodeWithText("Page 7").assertExists()
  }

  @Test
  fun compactLayoutCanReachLastSlot() {
    composeTestRule.setContent {
      val compact = Configuration(LocalConfiguration.current).apply { screenHeightDp = 400 }
      CompositionLocalProvider(LocalConfiguration provides compact) {
        MainScreen(fakeRepository, {}, { _, _, _ -> }, {})
      }
    }
    composeTestRule.onNodeWithText("Scroll to see all 9 slots").assertExists()
    composeTestRule.onNodeWithContentDescription("Binder slots").performScrollToIndex(8)
    composeTestRule.onNodeWithText("Slot 9").assertExists()
    composeTestRule.onNodeWithText("Next page").assertExists()
  }
}

private class FakeDataRepository : DataRepository {
  private val _isLoggedIn = MutableStateFlow(true)
  override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

  private val _cards = MutableStateFlow<List<Card>>(emptyList())
  override val cards: StateFlow<List<Card>> = _cards.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _errorMessage = MutableStateFlow<String?>(null)
  override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

  override val username = MutableStateFlow("Dr4g0n").asStateFlow()
  override val profilePicSource = MutableStateFlow("pokemon").asStateFlow()
  override val profileFeaturedDex = MutableStateFlow(25).asStateFlow()
  override val profileImageUrl = MutableStateFlow("").asStateFlow()
  override val profileImageBase64 = MutableStateFlow("").asStateFlow()

  override var prefilledPage: Int? = null
  override var prefilledSlot: Int? = null

  override suspend fun login(email: String, password: String): Boolean = true
  override suspend fun register(email: String, password: String): Boolean = true
  override suspend fun addCard(card: Card): Boolean = true
  override suspend fun removeCard(card: Card): Boolean = true
  override suspend fun fetchRemote(): Boolean = true
  override suspend fun refreshAndFetch(): Boolean = true
  override suspend fun updateProfile(username: String, source: String, dex: Int, url: String, base64: String): Boolean = true
  override fun logout() {}

}
