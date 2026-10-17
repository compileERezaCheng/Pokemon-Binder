package com.example.pokemongrader.ui.details

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.CatalogCard
import com.example.pokemongrader.ui.components.CatalogSetOption
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory

class CardArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val card = Card(
        1, 1, 6, "Charizard", "Normal", "NM", "", "today",
        imageUrl = "https://assets.tcgdex.net/en/base/base1/4/low.webp",
        catalogCardId = "base1-4"
    )

    @Test fun setOptionKeepsTheSetAndNumberAndReservesALargePlaceholderOnNarrowScreens() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    CatalogSetOption(CatalogCard("cel25-17a", "Umbreon ☆", "cel25", "Celebrations Classic Collection", "17a", "", ""))
                }
            }
        }
        compose.onNodeWithText("Celebrations Classic Collection").assertExists()
        compose.onNodeWithText("#17a").assertExists()
        compose.onNodeWithText("Umbreon ☆").assertDoesNotExist()
        val image = compose.onNodeWithContentDescription("Image unavailable").fetchSemanticsNode().boundsInRoot
        val number = compose.onNodeWithText("#17a").fetchSemanticsNode().boundsInRoot
        assertTrue(image.right <= number.left)
        assertTrue(image.width >= with(compose.density) { 84.dp.toPx() })
    }

    @Test fun loadedCardArtworkOpensAndClosesTheLargeViewer() {
        compose.setContent { MaterialTheme { CardDetailsArtwork(card) } }
        awaitImage("Charizard card")
        compose.onNodeWithContentDescription("Charizard card").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Close image").assertExists()
        awaitImage("Enlarged Charizard card")
        compose.onNodeWithContentDescription("Close image").performClick()
        compose.onNodeWithContentDescription("Enlarged Charizard card").assertDoesNotExist()
    }

    @Test fun speciesArtworkCannotOpenTheViewer() {
        compose.setContent { MaterialTheme { CardDetailsArtwork(card.copy(imageUrl = "", catalogCardId = "")) } }
        awaitImage("Charizard Pokémon")
        compose.onNodeWithContentDescription("Charizard Pokémon").assertHasNoClickAction()
        compose.onNodeWithContentDescription("Close image").assertDoesNotExist()
    }

    @Test fun setLogosCannotOpenTheViewer() {
        compose.setContent {
            MaterialTheme { CardDetailsArtwork(card.copy(imageUrl = "https://assets.tcgdex.net/en/base/base1/logo.png")) }
        }
        awaitImage("Charizard Pokémon")
        compose.onNodeWithContentDescription("Charizard Pokémon").assertHasNoClickAction()
    }

    @Test fun failedCardArtworkShowsANonClickablePlaceholder() {
        compose.setContent {
            MaterialTheme { CardDetailsArtwork(card.copy(imageUrl = "https://assets.tcgdex.net/en/base/base1/missing/low.webp")) }
        }
        compose.onNodeWithText("Image unavailable").assertHasNoClickAction()
        compose.onNodeWithContentDescription("Charizard card").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close image").assertDoesNotExist()
    }

    @Test fun changingTheImageRemovesThePreviouslyLoadedCard() {
        val current = mutableStateOf(card)
        compose.setContent { MaterialTheme { CardDetailsArtwork(current.value) } }
        awaitImage("Charizard card")
        compose.runOnIdle { current.value = card.copy(imageUrl = "", dexNumber = 0) }
        compose.onNodeWithContentDescription("Charizard card").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close image").assertDoesNotExist()
    }

    private fun awaitImage(description: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    companion object {
        @BeforeClass @JvmStatic fun stubImageDownloads() {
            val png = ByteArrayOutputStream().use { output ->
                Bitmap.createBitmap(60, 82, Bitmap.Config.ARGB_8888).apply {
                    compress(Bitmap.CompressFormat.PNG, 100, output)
                    recycle()
                }
                output.toByteArray()
            }
            // Keep image success/failure deterministic and independent of the catalog's availability.
            URL.setURLStreamHandlerFactory(URLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getResponseCode() = if (url.path.contains("missing")) 404 else 200
                        override fun getInputStream() = ByteArrayInputStream(png)
                    }
                }
            })
        }
    }
}
