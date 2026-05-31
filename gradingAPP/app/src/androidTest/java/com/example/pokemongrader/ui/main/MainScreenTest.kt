package com.example.pokemongrader.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.pokemongrader.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val fakeRepository = FakeDataRepository()

  @Before
  fun setup() {
    composeTestRule.setContent { 
      MainScreen(
        repository = fakeRepository,
        onNavigateToScan = {},
        onLogout = {}
      )
    }
  }

  @Test
  fun testPlaceholder_exists_whenEmpty() {
    composeTestRule.onNodeWithText("Binder is Empty").assertExists()
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

  override suspend fun submitDatasetSample(
    front: android.graphics.Bitmap?,
    back: android.graphics.Bitmap?,
    name: String,
    set: String,
    rarity: String,
    grade: Double,
    critique: String
  ): Boolean = true

  override suspend fun login(email: String, password: String): Boolean = true
  override suspend fun register(email: String, password: String): Boolean = true
  override suspend fun addCard(card: Card): Boolean = true
  override suspend fun removeCard(card: Card): Boolean = true
  override suspend fun fetchRemote(): Boolean = true
  override suspend fun refreshAndFetch(): Boolean = true
  override suspend fun updateProfile(username: String, source: String, dex: Int, url: String, base64: String): Boolean = true
  override fun logout() {}

  override val customSets = MutableStateFlow<Map<String, String>>(emptyMap()).asStateFlow()
  override suspend fun addCustomSet(code: String, name: String) {}
  override suspend fun removeCustomSet(code: String) {}
}
