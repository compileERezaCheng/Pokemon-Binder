package com.example.pokemongrader.data

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAiRetryTest {
    @Test
    fun retriesTemporary429AndStopsAfterFourAttempts() = runTest {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val retries = mutableListOf<Int>()

        val result = retryAiQuota(
            onRetry = { attempt, _ -> retries += attempt },
            wait = { waits += it }
        ) {
            attempts++
            if (attempts < 4) throw IOException("429 Resource exhausted; try again later")
            "identified"
        }

        assertEquals("identified", result)
        assertEquals(4, attempts)
        assertEquals(listOf(1, 2, 3), retries)
        assertEquals(listOf(4_000L, 8_000L, 12_000L), waits)
    }

    @Test
    fun dailyQuotaFailsImmediatelyWithManualEntryGuidance() = runTest {
        var attempts = 0
        val retries = mutableListOf<Int>()

        val error = runCatching {
            retryAiQuota(onRetry = { attempt, _ -> retries += attempt }, wait = {}) {
                attempts++
                throw IOException("429 quota exceeded: requests per day limit")
            }
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("daily quota", ignoreCase = true))
        assertTrue(error?.message.orEmpty().contains("manually", ignoreCase = true))
        assertEquals(1, attempts)
        assertTrue(retries.isEmpty())
    }

    @Test
    fun temporaryQuotaExhaustionStopsAfterFourAttempts() = runTest {
        var attempts = 0
        val waits = mutableListOf<Long>()

        val error = runCatching {
            retryAiQuota(onRetry = { _, _ -> }, wait = { waits += it }) {
                attempts++
                throw IOException("429 Resource exhausted; try again later")
            }
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("rate limit", ignoreCase = true))
        assertEquals(4, attempts)
        assertEquals(listOf(4_000L, 8_000L, 12_000L), waits)
    }

    @Test
    fun unrelatedFailureIsNotRetried() = runTest {
        var attempts = 0

        val error = runCatching {
            retryAiQuota(onRetry = { _, _ -> error("unexpected retry") }, wait = {}) {
                attempts++
                throw IOException("network offline")
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(1, attempts)
    }

    @Test
    fun scannerRequiresFirebaseApiKeyAppIdAndProjectId() {
        assertTrue(hasFirebaseAiConfig("public-key", "1:sender:android:app", "beta-project"))
        assertFalse(hasFirebaseAiConfig("public-key", "", "beta-project"))
        assertFalse(hasFirebaseAiConfig("", "1:sender:android:app", "beta-project"))
    }

    @Test
    fun unconfiguredScannerExplainsManualEntryFallback() {
        val error = runCatching { FirebaseAiClient.model() }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("manually", ignoreCase = true))
    }
}
