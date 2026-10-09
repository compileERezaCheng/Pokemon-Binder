package com.example.pokemongrader.ui.main

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.example.pokemongrader.MainActivity
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.pokemongrader.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var fakeRepository: FakeDataRepository
  private var scanOpened = false
  private var accountOpened = false
  private var openedCard: Triple<Int, Int, String>? = null

  private fun showMain(cards: List<Card> = emptyList()) {
    fakeRepository = FakeDataRepository(cards)
    scanOpened = false
    accountOpened = false
    openedCard = null
    composeTestRule.setContent {
      StatefulMainScreen(
        repository = fakeRepository,
        onNavigateToScan = { scanOpened = true },
        onNavigateToCardDetails = { page, slot, date -> openedCard = Triple(page, slot, date) },
        onNavigateToAccountSettings = { accountOpened = true }
      )
    }
  }

  @Test
  fun enteringMainDoesNotStartAnExtraCollectionRefresh() {
    showMain()
    composeTestRule.waitForIdle()
    assertEquals(0, fakeRepository.refreshAndFetchCalls)
  }

  @Test
  fun testPlaceholder_exists_whenEmpty() {
    showMain()
    composeTestRule.onNodeWithText("Binder is Empty").assertExists()
  }

  @Test
  fun collectionSearchAndRepeatedToggleShowTheExpectedCards() {
    showMain(listOf(
      testCard("pikachu", 25, "Normal"),
      testCard("pikachu", 25, "Holofoil Rare", date = "later"),
      testCard("charizard", 6, "Normal")
    ))
    composeTestRule.onNodeWithText("COLLECTION").performClick()
    composeTestRule.onNodeWithText("Search by name or notes...").performTextInput("pika")
    composeTestRule.onAllNodesWithText("Pikachu").assertCountEquals(1)

    composeTestRule.onNode(isToggleable()).performClick()
    composeTestRule.onAllNodesWithText("Pikachu").assertCountEquals(2)

    composeTestRule.onNodeWithText("All").performClick()
    composeTestRule.onNodeWithText("Holofoil Rare").performClick()
    composeTestRule.onAllNodesWithText("Pikachu").assertCountEquals(1)
    composeTestRule.onNodeWithText("Charizard").assertDoesNotExist()
  }

  @Test
  fun collectionSortChangesCardOrder() {
    showMain(listOf(testCard("zeta", 0), testCard("alpha", 0)))
    composeTestRule.onNodeWithText("COLLECTION").performClick()
    composeTestRule.onNodeWithText("Dex Number").performClick()
    composeTestRule.onNodeWithText("Name").performClick()

    val alphaY = composeTestRule.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top
    val zetaY = composeTestRule.onNodeWithText("Zeta").fetchSemanticsNode().boundsInRoot.top
    assertTrue(alphaY < zetaY)
  }

  @Test
  fun binderSwipeAndEmptySlotPrefillNavigateCorrectly() {
    showMain()
    composeTestRule.onNodeWithText("Slot 1").performTouchInput { swipeLeft() }
    composeTestRule.onNodeWithText("Page 2").assertExists()
    composeTestRule.onNodeWithText("Slot 1").performTouchInput { swipeRight() }
    composeTestRule.onNodeWithText("Slot 1").performClick()

    assertEquals(1, fakeRepository.prefilledPage)
    assertEquals(1, fakeRepository.prefilledSlot)
    assertTrue(scanOpened)
  }

  @Test
  fun binderPageTurnKeepsThePreviousPageVisibleUntilTheTurnFinishes() {
    fakeRepository = FakeDataRepository(emptyList())
    val activeTab = mutableStateOf("binder")
    val currentPage = mutableIntStateOf(1)
    composeTestRule.setContent {
      MainScreen(
        repository = fakeRepository,
        onNavigateToScan = {},
        onNavigateToCardDetails = { _, _, _ -> },
        onNavigateToAccountSettings = {},
        activeTab = activeTab.value,
        onActiveTabChange = { activeTab.value = it },
        currentPage = currentPage.intValue,
        onCurrentPageChange = { currentPage.intValue = it }
      )
    }
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.runOnIdle { currentPage.intValue = 2 }
    composeTestRule.mainClock.advanceTimeBy(16)

    composeTestRule.onNodeWithText("Page 1").assertExists()
    composeTestRule.onNodeWithText("Page 2").assertExists()
    composeTestRule.mainClock.advanceTimeBy(1000)
    composeTestRule.onAllNodesWithText("Page 1").assertCountEquals(0)
    composeTestRule.onNodeWithText("Page 2").assertExists()
  }

  @Test
  fun navigationCanReturnToBinderAtTheAddedCardPage() {
    fakeRepository = FakeDataRepository(emptyList())
    val activeTab = mutableStateOf("collection")
    val currentPage = mutableIntStateOf(1)
    composeTestRule.setContent {
      MainScreen(
        repository = fakeRepository,
        onNavigateToScan = {},
        onNavigateToCardDetails = { _, _, _ -> },
        onNavigateToAccountSettings = {},
        activeTab = activeTab.value,
        onActiveTabChange = { activeTab.value = it },
        currentPage = currentPage.intValue,
        onCurrentPageChange = { currentPage.intValue = it }
      )
    }

    composeTestRule.runOnIdle {
      activeTab.value = "binder"
      currentPage.intValue = 4
    }

    composeTestRule.onNodeWithText("Page 4").assertExists()
    composeTestRule.onNodeWithText("Slot 1").assertExists()
  }

  @Test
  fun stackedCardsOpenTheRarestCardAndProfileHeaderNavigates() {
    showMain(listOf(
      testCard("pikachu", 0, "Normal", date = "old"),
      testCard("pikachu", 0, "Holofoil Rare", date = "rare")
    ))
    composeTestRule.onNodeWithText("2").assertExists()
    composeTestRule.onNodeWithText("Pikachu").performClick()
    assertEquals(Triple(1, 1, "rare"), openedCard)

    composeTestRule.onNodeWithText("Test Trainer").performClick()
    assertTrue(accountOpened)
  }

  @Test
  fun selectedCollectionTabSurvivesStateRestoration() {
    fakeRepository = FakeDataRepository(emptyList())
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setContent {
      StatefulMainScreen(fakeRepository, {}, { _, _, _ -> }, {})
    }

    composeTestRule.onNodeWithText("COLLECTION").performClick()
    restorationTester.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithText("Search by name or notes...").assertExists()
  }

  @Test
  fun binderPageSurvivesStateRestoration() {
    fakeRepository = FakeDataRepository(emptyList())
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setContent {
      StatefulMainScreen(fakeRepository, {}, { _, _, _ -> }, {})
    }

    composeTestRule.onNodeWithText("Slot 1").performTouchInput { swipeLeft() }
    composeTestRule.onNodeWithText("Page 2").assertExists()
    restorationTester.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithText("Page 2").assertExists()
  }

  @Test
  fun mainActivityIsLockedToPortrait() {
    val activityInfo = composeTestRule.activity.packageManager.getActivityInfo(
      ComponentName(composeTestRule.activity, MainActivity::class.java),
      0
    )

    assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activityInfo.screenOrientation)
  }

  private fun testCard(name: String, dex: Int, type: String = "Normal", date: String = "now") =
    Card(1, 1, dex, name, type, "NM", "", date)
}

@Composable
private fun StatefulMainScreen(
  repository: DataRepository,
  onNavigateToScan: () -> Unit,
  onNavigateToCardDetails: (Int, Int, String) -> Unit,
  onNavigateToAccountSettings: () -> Unit
) {
  val activeTab = rememberSaveable { mutableStateOf("binder") }
  val currentPage = rememberSaveable { mutableIntStateOf(1) }
  MainScreen(
    repository = repository,
    onNavigateToScan = onNavigateToScan,
    onNavigateToCardDetails = onNavigateToCardDetails,
    onNavigateToAccountSettings = onNavigateToAccountSettings,
    activeTab = activeTab.value,
    onActiveTabChange = { activeTab.value = it },
    currentPage = currentPage.intValue,
    onCurrentPageChange = { currentPage.intValue = it }
  )
}

private class FakeDataRepository(initialCards: List<Card>) : DataRepository {
  private val _isLoggedIn = MutableStateFlow(true)
  override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

  private val _cards = MutableStateFlow(initialCards)
  override val cards: StateFlow<List<Card>> = _cards.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
  var refreshAndFetchCalls = 0

  private val _errorMessage = MutableStateFlow<String?>(null)
  override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

  override val username = MutableStateFlow("Test Trainer").asStateFlow()
  override val profilePicSource = MutableStateFlow("base64").asStateFlow()
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
  override suspend fun refreshAndFetch(): Boolean {
    refreshAndFetchCalls++
    return true
  }
  override suspend fun updateProfile(username: String, source: String, dex: Int, url: String, base64: String): Boolean = true
  override fun logout() {}
}
