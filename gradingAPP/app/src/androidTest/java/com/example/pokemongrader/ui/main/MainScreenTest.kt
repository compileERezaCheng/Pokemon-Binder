package com.example.pokemongrader.ui.main

import android.content.ComponentName
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.example.pokemongrader.MainActivity
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import androidx.compose.ui.platform.LocalConfiguration
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
  fun syncErrorIsVisibleOnTheMainScreen() {
    showMain()
    fakeRepository.reportError("Conflict detected. Your local edit is saved for recovery.")

    composeTestRule.onNodeWithText("Conflict detected. Your local edit is saved for recovery.").assertIsDisplayed()
  }

  @Test
  fun testPlaceholder_exists_whenEmpty() {
    showMain()
    composeTestRule.onNodeWithText("Slot 9").assertIsDisplayed()
    composeTestRule.onNodeWithText("Previous page").assertDoesNotExist()
    composeTestRule.onNodeWithText("Next page").performClick()
    composeTestRule.onNodeWithText("Previous page").assertIsDisplayed().performClick()
    composeTestRule.onNodeWithText("Page 1").assertExists()
    composeTestRule.onNodeWithText("Previous page").assertDoesNotExist()
  }

  @Test fun bottomNavigationHasCentralAddAndAvatarOnlyProfile() {
    showMain()
    fakeRepository.prefilledPage = 9
    fakeRepository.prefilledSlot = 8
    composeTestRule.onNodeWithContentDescription("Add Card").performClick()
    assertTrue(scanOpened)
    assertEquals(null, fakeRepository.prefilledPage)
    assertEquals(null, fakeRepository.prefilledSlot)
    composeTestRule.onNodeWithContentDescription("Account and profile").performClick()
    assertTrue(accountOpened)
    composeTestRule.onNodeWithText("Test Trainer").assertDoesNotExist()
    composeTestRule.onNodeWithContentDescription("Market Prices").performClick()
    composeTestRule.onNodeWithText("Card name").assertExists()
    composeTestRule.onNodeWithContentDescription("Binder").performClick()
    composeTestRule.onNodeWithText("Slot 9").assertIsDisplayed()
  }

  @Test
  fun collectionSearchAndRepeatedToggleShowTheExpectedCards() {
    showMain(listOf(
      testCard("pikachu", 25, "Normal"),
      testCard("pikachu", 25, "Holofoil Rare", date = "later"),
      testCard("charizard", 6, "Normal")
    ))
    composeTestRule.onNodeWithContentDescription("Collection/Search").performClick()
    composeTestRule.onNodeWithText("Search by name or notes...").performTextInput("pika")
    composeTestRule.onAllNodesWithText("Pikachu").assertCountEquals(1)

    composeTestRule.onNode(isToggleable()).performClick()
    composeTestRule.onAllNodesWithText("Pikachu").assertCountEquals(2)

    composeTestRule.onNodeWithText("All").performClick()
    composeTestRule.onNode(hasText("Holofoil Rare") and hasAnyAncestor(isPopup())).performClick()
    composeTestRule.onAllNodesWithText("Pikachu").assertCountEquals(1)
    composeTestRule.onNodeWithText("Charizard").assertDoesNotExist()
  }

  @Test
  fun collectionSortChangesCardOrder() {
    showMain(listOf(testCard("zeta", 0), testCard("alpha", 0)))
    composeTestRule.onNodeWithContentDescription("Collection/Search").performClick()
    composeTestRule.onNodeWithText("Dex Number").performClick()
    composeTestRule.onNodeWithText("Name").performClick()

    val alphaY = composeTestRule.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top
    val zetaY = composeTestRule.onNodeWithText("Zeta").fetchSemanticsNode().boundsInRoot.top
    assertTrue(alphaY < zetaY)
  }

  @Test
  fun raritySortGroupsCatalogAndManualRaritiesAndReordersNewCards() {
    val savedCards = Card.parseCollection("""
      [
        {"Name":"golurk","Type":"Double rare"},
        {"Name":"mew"},
        {"Name":"watchog","Type":"Illustration Rare"},
        {"Name":"sprigatito","Type":"Illustration rare"},
        {"Name":"absol","Type":"Unknown"}
      ]
    """.trimIndent())
    showMain(savedCards)
    composeTestRule.onNodeWithContentDescription("Collection/Search").performClick()
    composeTestRule.onNodeWithText("Dex Number").performClick()
    composeTestRule.onNodeWithText("Rarity").performClick()

    fun assertOrder(vararg names: String) {
      val displayedOrder = names.sortedBy {
        composeTestRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
      }
      assertEquals(names.toList(), displayedOrder)
    }

    assertOrder("Sprigatito", "Watchog", "Golurk", "Mew", "Absol")

    // Synthetic spacing variant to exercise a fresh collection emission.
    fakeRepository.emitCards(savedCards + testCard("spewpa", 0, " iLLustration  rare "))
    assertOrder("Spewpa", "Sprigatito", "Watchog", "Golurk")
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
        onNavigateToCardDetails = { _, _, _, _ -> },
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
        onNavigateToCardDetails = { _, _, _, _ -> },
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

    composeTestRule.onNodeWithContentDescription("Account and profile").performClick()
    assertTrue(accountOpened)
  }

  @Test
  fun selectedCollectionTabSurvivesStateRestoration() {
    fakeRepository = FakeDataRepository(emptyList())
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setContent {
      StatefulMainScreen(fakeRepository, {}, { _, _, _ -> }, {})
    }

    composeTestRule.onNodeWithContentDescription("Collection/Search").performClick()
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
  fun compactLayoutCanReachLastSlot() {
    fakeRepository = FakeDataRepository(emptyList())
    composeTestRule.setContent {
      val compact = Configuration(LocalConfiguration.current).apply { screenHeightDp = 400 }
      CompositionLocalProvider(LocalConfiguration provides compact) {
        StatefulMainScreen(fakeRepository, {}, { _, _, _ -> }, {})
      }
    }

    composeTestRule.onNodeWithText("Scroll to see all 9 slots").assertDoesNotExist()
    composeTestRule.onNodeWithText("Slot 9").assertIsDisplayed()
    composeTestRule.onNodeWithText("Next page").assertExists()
  }

  @Test fun allNineSlotsAndBottomBarFitWithoutScrolling() {
    showMain((1..9).map { slot -> testCard("card $slot", 0).copy(slot = slot) })
    for (slot in 1..9) composeTestRule.onNodeWithContentDescription("Slot $slot").assertIsDisplayed()
    val bottom = composeTestRule.onNodeWithContentDescription("Add Card").fetchSemanticsNode().boundsInRoot
    val last = composeTestRule.onNodeWithContentDescription("Slot 9").fetchSemanticsNode().boundsInRoot
    assertTrue(last.bottom <= bottom.top)
    val page = composeTestRule.onNodeWithText("Page 1").fetchSemanticsNode().boundsInRoot
    val root = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
    assertEquals(root.center.x, page.center.x, 1f)
    composeTestRule.onNodeWithText("Previous page").assertDoesNotExist()
    composeTestRule.onNodeWithText("Next page").assertIsDisplayed()
    val file = java.io.File(composeTestRule.activity.getExternalFilesDir(null), "binder-layout.png")
    file.outputStream().use { composeTestRule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun mainActivitySupportsLandscape() {
    val activityInfo = composeTestRule.activity.packageManager.getActivityInfo(
      ComponentName(composeTestRule.activity, MainActivity::class.java),
      0
    )

    assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activityInfo.screenOrientation)
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
    onNavigateToCardDetails = { page, slot, date, _ -> onNavigateToCardDetails(page, slot, date) },
    onNavigateToAccountSettings = onNavigateToAccountSettings,
    activeTab = activeTab.value,
    onActiveTabChange = { activeTab.value = it },
    currentPage = currentPage.intValue,
    onCurrentPageChange = { currentPage.intValue = it },
    modifier = Modifier.safeDrawingPadding()
  )
}

private class FakeDataRepository(initialCards: List<Card>) : DataRepository {
  private val _isLoggedIn = MutableStateFlow(true)
  override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

  private val _cards = MutableStateFlow(initialCards)
  override val cards: StateFlow<List<Card>> = _cards.asStateFlow()
  fun emitCards(cards: List<Card>) { _cards.value = cards }

  private val _isLoading = MutableStateFlow(false)
  override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
  var refreshAndFetchCalls = 0

  private val _errorMessage = MutableStateFlow<String?>(null)
  override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
  fun reportError(message: String) { _errorMessage.value = message }

  override val username = MutableStateFlow("Test Trainer").asStateFlow()
  override val profilePicSource = MutableStateFlow("base64").asStateFlow()
  override val profileFeaturedDex = MutableStateFlow(25).asStateFlow()
  override val profileImageUrl = MutableStateFlow("").asStateFlow()
  override val profileImageBase64 = MutableStateFlow("").asStateFlow()

  override var prefilledPage: Int? = null
  override var prefilledSlot: Int? = null

  override suspend fun login(email: String, password: String): Boolean = true
  override suspend fun register(email: String, password: String): Boolean = true
  override suspend fun addCard(card: Card): Boolean = true
  override suspend fun updateCard(card: Card): Boolean = true
  override suspend fun removeCard(card: Card): Boolean = true
  override suspend fun fetchRemote(): Boolean = true
  override suspend fun refreshAndFetch(): Boolean {
    refreshAndFetchCalls++
    return true
  }
  override suspend fun updateProfile(username: String, source: String, dex: Int, url: String, base64: String): Boolean = true
  override fun logout() {}
}
