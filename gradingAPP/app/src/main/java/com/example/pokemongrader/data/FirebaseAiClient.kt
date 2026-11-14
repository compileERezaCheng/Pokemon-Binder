package com.example.pokemongrader.data

import android.content.Context
import com.example.pokemongrader.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.delay

private const val MODEL_NAME = "gemini-3.5-flash-lite"
private const val APP_NAME = "pokebinder-ai"

internal fun hasFirebaseAiConfig(apiKey: String, appId: String, projectId: String): Boolean =
    apiKey.isNotBlank() && appId.isNotBlank() && projectId.isNotBlank()

object FirebaseAiClient {
    @Volatile private var configuredModel: GenerativeModel? = null

    val isReady: Boolean get() = configuredModel != null

    fun initialize(context: Context) {
        configuredModel = null
        if (!hasFirebaseAiConfig(FirebaseClient.firebaseApiKey, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_PROJECT_ID)) return

        try {
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApiKey(FirebaseClient.firebaseApiKey)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .build(),
                    APP_NAME
                )

            FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            configuredModel = FirebaseAI.getInstance(app, GenerativeBackend.googleAI()).generativeModel(MODEL_NAME)
        } catch (_: Exception) {
            configuredModel = null
        }
    }

    fun model(): GenerativeModel = configuredModel
        ?: throw IllegalStateException("AI scanner unavailable in this build. You can add cards manually.")
}

internal suspend fun <T> retryAiQuota(
    onRetry: (attempt: Int, delayMs: Long) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
    request: suspend () -> T
): T {
    var delayMs = 4_000L
    for (attempt in 1..4) {
        try {
            return request()
        } catch (error: Exception) {
            val message = generateSequence<Throwable>(error) { it.cause }
                .joinToString(" ") { "${it::class.simpleName} ${it.message.orEmpty()}" }
                .lowercase()
                .replace('_', ' ')
            val dailyQuota = listOf("per day", "perday", "daily", "requests/day", "tokens/day", "rpd", "tpd")
                .any(message::contains)
            val quotaError = listOf("429", "resource exhausted", "quota", "too many requests", "rate limit")
                .any(message::contains)

            if (!quotaError) throw error
            if (dailyQuota) throw IllegalStateException("Daily quota exhausted for AI. Try again tomorrow or add the card manually.", error)
            if (attempt == 4) throw IllegalStateException("AI rate limit reached. Try again later or add the card manually.", error)

            onRetry(attempt, delayMs)
            wait(delayMs)
            delayMs += 4_000L
        }
    }
    error("AI request ended without a response.")
}
