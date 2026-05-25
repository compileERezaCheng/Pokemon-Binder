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

  override val apiKey: String = "fake_key"
  override val dbUrl: String = "fake_db"
  override val email: String = "collector@example.com"
  override val uid: String = "fake_uid"
  override val token: String = "fake_token"

  override suspend fun login(email: String, password: String, apiKey: String, dbUrl: String): Boolean = true
  override suspend fun register(email: String, password: String, apiKey: String, dbUrl: String): Boolean = true
  override suspend fun addCard(card: Card): Boolean = true
  override suspend fun fetchRemote(): Boolean = true
  override fun logout() {}
}
