package com.example.pokemongrader.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val CARD_SCANNER_MODEL = "gemini-3.5-flash-lite"

internal class GeminiApiClient(
    private val apiKey: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    fun generateContent(prompt: String, jpegPhotos: List<String>): String {
        val parts = JSONArray()
        jpegPhotos.forEach { photo ->
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", photo)))
        }
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 4096))
            .toString().toByteArray(Charsets.UTF_8)
        if (body.size > 19_000_000) throw IOException("The card photos are too large. Retake them or enter the card manually.")

        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$CARD_SCANNER_MODEL:generateContent")
            val connection = openConnection(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 45_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("x-goog-api-key", apiKey)
                connection.outputStream.use { it.write(body) }
                when (val status = connection.responseCode) {
                    in 200..299 -> {
                        val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val partsFromGemini = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")
                        val answer = buildString {
                            for (index in 0 until (partsFromGemini?.length() ?: 0)) {
                                val part = partsFromGemini?.optJSONObject(index) ?: continue
                                if (!part.optBoolean("thought")) (part.opt("text") as? String)?.let { append(it) }
                            }
                        }.trim()
                        if (answer.isEmpty()) throw GeminiRequestError("Gemini did not return a result. Try again or enter the card manually.")
                        return answer
                    }
                    400, 401, 403 -> throw GeminiRequestError(
                        "Gemini rejected this request. Check your API key and the billing on its project, or enter the card manually."
                    )
                    429 -> throw GeminiRequestError("Your Gemini project hit its usage limit. Try later or enter the card manually.")
                    else -> throw GeminiRequestError("Gemini is unavailable (HTTP $status). Try again or enter the card manually.")
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: GeminiRequestError) {
            throw error
        } catch (_: JSONException) {
            throw IOException("Gemini returned an unreadable response. Try again or enter the card manually.")
        } catch (_: IOException) {
            throw IOException("Couldn't reach Gemini. Check your connection or enter the card manually.")
        }
    }

    private class GeminiRequestError(message: String) : IOException(message)
}
