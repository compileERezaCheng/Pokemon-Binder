package com.example.pokemongrader.ui.account

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.pokemongrader.data.AndroidGeminiKeyStore
import com.example.pokemongrader.data.PersonalGeminiSession
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test

class PersonalGeminiSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = "test-${UUID.randomUUID()}"
    private val store by lazy { AndroidGeminiKeyStore(ApplicationProvider.getApplicationContext()) }

    @After
    fun clearKey() = store.remove(owner)

    @Test
    fun invalidKeyLeavesScannerOffAndValidKeyCanBeRemoved() {
        val session = PersonalGeminiSession(store).apply { selectAccount(owner) }
        compose.setContent { MaterialTheme { PersonalGeminiSettings(session) } }

        compose.onNodeWithText("Use my Gemini key").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("bad")
        compose.onNodeWithText("Save Gemini key").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Enter a valid Gemini API key.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Enter a valid Gemini API key.").assertExists()

        compose.onNode(hasSetTextAction()).performTextReplacement("fake-test-key-0000000000000000000000")
        compose.onNodeWithText("Save Gemini key").performClick()
        compose.waitUntil(5_000) { session.client.value != null }
        compose.onNodeWithText("Your key is saved on this device.").assertExists()
        compose.onNodeWithText("Remove my Gemini key").performClick()
        compose.waitUntil(5_000) { session.client.value == null }
        compose.onNodeWithText("Use my Gemini key").assertExists()
    }
}
