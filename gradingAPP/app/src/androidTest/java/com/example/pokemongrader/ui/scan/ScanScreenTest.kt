package com.example.pokemongrader.ui.scan

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ScanScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test
    fun aiGradingIsOfferedOnlyOnRequestWhenBothCardSidesExist() {
        val gradeChanges = AtomicInteger()
        showConfirmation(isManual = false, back = bitmap, onGradeChange = { gradeChanges.incrementAndGet() })

        composeTestRule.onNodeWithText("GRADE THIS CARD WITH AI (BETA)").assertExists()
        assertEquals(0, gradeChanges.get())
    }

    @Test
    fun aiGradingButtonRequiresBothSides() {
        showConfirmation(isManual = false, back = null)
        composeTestRule.onNodeWithText("GRADE THIS CARD WITH AI (BETA)").assertDoesNotExist()
    }

    @Test
    fun manualEntryConfirmsWithoutOfferingAiGrading() {
        val confirmed = AtomicInteger()
        showConfirmation(isManual = true, back = null, onConfirm = { confirmed.incrementAndGet() })

        composeTestRule.onNodeWithText("GRADE THIS CARD WITH AI (BETA)").assertDoesNotExist()
        composeTestRule.onNodeWithText("COMMIT TO BINDER").performClick()
        assertEquals(1, confirmed.get())
    }

    private fun showConfirmation(
        isManual: Boolean,
        back: Bitmap?,
        onGradeChange: (String) -> Unit = {},
        onConfirm: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                ConfirmationScreen(
                    name = "pikachu",
                    dex = 0,
                    rarity = "Normal",
                    grade = 0.0,
                    critique = "",
                    page = "1",
                    slot = "1",
                    isManual = isManual,
                    allPokemonNames = emptyList(),
                    frontBitmap = if (isManual) null else bitmap,
                    backBitmap = back,
                    saveError = "",
                    onNameChange = {},
                    onDexChange = {},
                    onRarityChange = {},
                    onGradeChange = onGradeChange,
                    onCritiqueChange = {},
                    onPageChange = {},
                    onSlotChange = {},
                    onConfirm = onConfirm,
                    onCancel = {}
                )
            }
        }
    }
}
