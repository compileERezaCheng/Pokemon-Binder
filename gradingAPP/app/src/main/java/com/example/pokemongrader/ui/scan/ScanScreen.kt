package com.example.pokemongrader.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.CatalogCard
import com.example.pokemongrader.ui.components.CatalogSetPicker
import com.example.pokemongrader.ui.components.GradeSummary
import com.example.pokemongrader.data.DataRepository
import com.example.pokemongrader.data.FirebaseAiClient
import com.example.pokemongrader.data.PersonalGeminiSession
import com.example.pokemongrader.data.PokeApiClient
import com.example.pokemongrader.data.TcgDexClient
import com.example.pokemongrader.data.TcgDexLocale
import com.example.pokemongrader.data.retryAiQuota
import com.example.pokemongrader.ui.main.AsyncImage
import com.example.pokemongrader.ui.main.capitalize
import com.example.pokemongrader.ui.components.TcgDexLanguagePicker
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONException
import org.json.JSONObject

internal val CONDITION_ESTIMATE_PROMPT = """
    Estimate physical condition from these front and back photos. This is not professional certification.
    Look for observable evidence only: border centering, corner wear, edge wear, and surface defects.
    Do not use copyright year, release date, or suspected authenticity to refuse condition grading.
    Assess only the visible physical condition of the card.
    If either photo is cropped, blurred, obscured by glare, or too small to assess any criterion,
    return {"assessable":false,"grade":null,"reason":"short observable reason"}.
    Otherwise return ONLY a JSON object: assessable=true; numeric grade from 1 to 10;
    criteria with centering, corners, edges and surface objects. Each object needs a numeric
    score from 1 to 10 and a short evidence string describing a visible observation.
    If a defect cannot be observed reliably, use the non-assessable form. Do not invent defects or scores.
""".trimIndent()

enum class ScanState {
    CAMERA,
    REVIEW,
    GRADING,
    CONFIRM,
    MANUAL
}

enum class ScanSide {
    FRONT, BACK
}

data class ConditionEstimate(
    val grade: Double?,
    val evidence: String,
    val criteriaJson: String = "",
    val detailsJson: String = ""
)

fun parseConditionEstimate(json: JSONObject): ConditionEstimate {
    val assessable = json.getBoolean("assessable")
    if (!assessable) {
        require(json.has("grade") && json.isNull("grade")) { "Unevaluable response must have a null grade" }
        val reason = json.getString("reason").trim()
        require(reason.isNotEmpty()) { "Missing reason" }
        return ConditionEstimate(null, "Not assessable: $reason", detailsJson = json.toString())
    }
    val rawGrade = json.get("grade")
    require(rawGrade is Number) { "Missing numeric grade" }
    val grade = rawGrade.toDouble()
    require(grade.isFinite() && grade in 1.0..10.0) { "Grade outside 1–10" }
    val criteria = json.getJSONObject("criteria")
    val evidence = listOf("centering", "corners", "edges", "surface").joinToString("; ") { name ->
        val item = criteria.getJSONObject(name)
        val rawScore = item.get("score")
        require(rawScore is Number) { "Missing $name score" }
        val score = rawScore.toDouble()
        require(score.isFinite() && score in 1.0..10.0) { "$name outside 1–10" }
        val observation = item.getString("evidence").trim()
        require(observation.isNotEmpty()) { "Missing $name evidence" }
        "$name $score: $observation"
    }
    return ConditionEstimate(grade, evidence, criteria.toString(), json.toString())
}

@Composable
fun ScanScreen(
    repository: DataRepository,
    onCardAdded: (Int) -> Unit,
    onNavigateBack: () -> Unit,
    onConfigureAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val personalGemini = repository.personalGemini
    val personalClient = personalGemini?.client?.collectAsStateWithLifecycle()?.value
    val scannerReady = personalClient != null || FirebaseAiClient.isReady

    var scanState by remember { mutableStateOf(ScanState.CAMERA) }
    var scanSide by remember { mutableStateOf(ScanSide.FRONT) }

    // CameraX ImageCapture
    val imageCapture = remember { ImageCapture.Builder().build() }
    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var backBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Permission State
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(scannerReady) {
        if (scannerReady && !hasCameraPermission) {
            launcher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // Result Data
    var resolvedName by remember { mutableStateOf("") }
    var resolvedSpecies by remember { mutableStateOf("") }
    var resolvedForm by remember { mutableStateOf("") }
    var resolvedCardLanguage by remember { mutableStateOf("") }
    var catalogLocale by remember { mutableStateOf("en") }
    var resolvedVariant by remember { mutableStateOf("") }
    var speciesLookupRevision by remember { mutableIntStateOf(0) }
    var resolvedDex by remember { mutableStateOf(0) }
    var resolvedRarity by remember { mutableStateOf("Normal") }
    var resolvedImageUrl by remember { mutableStateOf("") }
    var selectedCatalogCard by remember { mutableStateOf<CatalogCard?>(null) }
    var catalogSetHint by remember { mutableStateOf("") }
    var catalogNumberHint by remember { mutableStateOf("") }
    var resolvedGradeDetails by remember { mutableStateOf("") }
    var personalNotes by remember { mutableStateOf("") }
    var resolvedGrade by remember { mutableStateOf(0.0) }
    var resolvedCritique by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf("") }
    var captureError by remember { mutableStateOf("") }

    // Status text for loading screen
    var gradingStatus by remember { mutableStateOf("Analyzing Centering, Corners & Surface...") }

    val cards by repository.cards.collectAsState()

    // Coordinates
    var page by remember { mutableStateOf("1") }
    var slot by remember { mutableStateOf("1") }

    LaunchedEffect(repository.prefilledPage, repository.prefilledSlot, cards) {
        val prefP = repository.prefilledPage
        val prefS = repository.prefilledSlot
        if (prefP != null && prefS != null) {
            page = prefP.toString()
            slot = prefS.toString()
        } else {
            // Find first empty pocket (Page 1..20, Slot 1..9)
            var found = false
            for (p in 1..20) {
                for (s in 1..9) {
                    if (cards.none { it.page == p && it.slot == s }) {
                        page = p.toString()
                        slot = s.toString()
                        found = true
                        break
                    }
                }
                if (found) break
            }
        }
    }

    // All Pokémon names for autocomplete (fetched once)
    var allPokemonNames by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        allPokemonNames = PokeApiClient.fetchAllNames()
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF020617))) {
        if (!scannerReady && scanState != ScanState.MANUAL) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Text("Add a card", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { scanState = ScanState.MANUAL }) { Text("Enter Manually") }
                OutlinedButton(onClick = onConfigureAi) { Text("Set up Gemini scanner") }
                TextButton(onClick = onNavigateBack) { Text("Back") }
            }
        } else if (!hasCameraPermission && scanState != ScanState.MANUAL) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Camera permission is required to scan cards.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(android.Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
                TextButton(onClick = { scanState = ScanState.MANUAL }) {
                    Text("Enter Manually Instead", color = Color.Gray)
                }
            }
        } else {
            when (scanState) {
                ScanState.CAMERA -> {
                    CameraViewfinder(
                        side = scanSide,
                        imageCapture = imageCapture,
                        captureError = captureError,
                        onCapture = {
                            captureError = ""
                            resolvedName = ""
                            resolvedSpecies = ""
                            resolvedForm = ""
                            resolvedCardLanguage = ""
                            catalogLocale = "en"
                            resolvedVariant = ""
                            speciesLookupRevision++
                            resolvedRarity = "Normal"
                            resolvedImageUrl = ""
                            selectedCatalogCard = null
                            catalogSetHint = ""
                            catalogNumberHint = ""
                            resolvedGradeDetails = ""
                            personalNotes = ""
                            resolvedGrade = 0.0
                            resolvedCritique = ""
                            resolvedDex = 0

                            coroutineScope.launch {
                                val bitmap = takePhoto(context, imageCapture)
                                if (bitmap != null) {
                                    if (scanSide == ScanSide.FRONT) {
                                        frontBitmap = bitmap
                                    } else {
                                        backBitmap = bitmap
                                    }
                                    scanState = ScanState.REVIEW
                                } else captureError = "Photo failed. Try again."
                            }
                        },
                        onManualEntry = { scanState = ScanState.MANUAL },
                        onCancel = {
                            scanSide = ScanSide.FRONT
                            onNavigateBack()
                        }
                    )
                }
                ScanState.REVIEW -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text("Review ${if (scanSide == ScanSide.FRONT) "front" else "back"}: check all corners, focus and reflections", color = Color.White)
                        val photo = if (scanSide == ScanSide.FRONT) frontBitmap else backBitmap
                        photo?.let { Image(it.asImageBitmap(), contentDescription = "Captured ${scanSide.name.lowercase()} photo", modifier = Modifier.weight(1f).fillMaxWidth()) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                if (scanSide == ScanSide.FRONT) frontBitmap = null else backBitmap = null
                                scanState = ScanState.CAMERA
                            }) { Text("Retake photo") }
                            Button(onClick = {
                                if (scanState != ScanState.REVIEW) return@Button
                                if (scanSide == ScanSide.FRONT) {
                                    scanSide = ScanSide.BACK
                                    scanState = ScanState.CAMERA
                                } else {
                                    scanState = ScanState.GRADING
                                    gradingStatus = "Identifying the card from its front..."
                                    coroutineScope.launch {
                                        try {
                                            val result = identifyCardWithGemini(frontBitmap!!, personalGemini) { attempt, delayMs ->
                                                gradingStatus = "Quota limit. Retry $attempt in ${delayMs / 1000}s..."
                                            }
                                            resolvedName = result.optString("printed_name").ifBlank { result.optString("name") }.trim()
                                            resolvedSpecies = result.optString("species_name").trim()
                                            resolvedForm = result.optString("form").trim()
                                            val detectedLanguage = result.optString("card_language").trim()
                                                .takeUnless { it.equals("unknown", true) }.orEmpty()
                                            val recognizedLocale = TcgDexLocale.languages.firstOrNull {
                                                it.code.equals(detectedLanguage, true) || it.label.equals(detectedLanguage, true)
                                            }?.code
                                            resolvedCardLanguage = recognizedLocale ?: detectedLanguage
                                            if (recognizedLocale != null) catalogLocale = recognizedLocale
                                            resolvedVariant = result.optString("variant").trim()
                                            resolvedRarity = result.optString("rarity").trim().ifBlank { "Unknown" }
                                            resolvedDex = 0
                                            catalogSetHint = result.optString("set_name")
                                            catalogNumberHint = result.optString("card_number")
                                            selectedCatalogCard = null
                                            resolvedImageUrl = ""
                                            val query = resolvedSpecies.ifBlank { resolvedName }
                                            val request = ++speciesLookupRevision
                                            if (query.isNotBlank()) coroutineScope.launch {
                                                val dex = PokeApiClient.fetchDexNumber(query)
                                                if (request == speciesLookupRevision && dex > 0) {
                                                    resolvedDex = dex
                                                    if (repository.prefilledPage == null) {
                                                        val position = PokeApiClient.binderPosition(dex)
                                                        page = position.first.toString()
                                                        slot = position.second.toString()
                                                    }
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            resolvedCritique = "Identification unavailable: ${e.message}. Correct the fields before saving."
                                        }
                                        scanState = ScanState.CONFIRM
                                    }
                                }
                            }) { Text("Use photo") }
                        }
                    }
                }
                ScanState.GRADING -> {
                    GradingLoadingScreen(status = gradingStatus)
                }
                ScanState.CONFIRM, ScanState.MANUAL -> {
                    ConfirmationScreen(
                        name = resolvedName,
                        speciesName = resolvedSpecies,
                        form = resolvedForm,
                        cardLanguage = resolvedCardLanguage,
                        catalogLocale = catalogLocale,
                        catalogVariant = resolvedVariant,
                        dex = resolvedDex,
                        rarity = resolvedRarity,
                        grade = resolvedGrade,
                        gradeDetails = resolvedGradeDetails,
                        personalNotes = personalNotes,
                        catalogCard = selectedCatalogCard,
                        catalogSetHint = catalogSetHint,
                        catalogNumberHint = catalogNumberHint,
                        critique = resolvedCritique,
                        page = page,
                        slot = slot,
                        isManual = scanState == ScanState.MANUAL,
                        allPokemonNames = allPokemonNames,
                        frontBitmap = frontBitmap,
                        backBitmap = backBitmap,
                        personalGemini = personalGemini,
                        saveError = saveError,
                        onNameChange = {
                            resolvedName = it
                            selectedCatalogCard = null
                            resolvedImageUrl = ""
                            resolvedDex = 0
                            val query = resolvedSpecies.ifBlank { it }
                            val request = ++speciesLookupRevision
                            if (query.length > 2) {
                                coroutineScope.launch {
                                    val dex = PokeApiClient.fetchDexNumber(query)
                                    if (request == speciesLookupRevision && dex > 0) {
                                        resolvedDex = dex
                                        if (repository.prefilledPage == null) {
                                            val position = PokeApiClient.binderPosition(dex)
                                            page = position.first.toString()
                                            slot = position.second.toString()
                                        }
                                    }
                                }
                            }
                        },
                        onSpeciesNameChange = {
                            resolvedSpecies = it
                            selectedCatalogCard = null
                            resolvedImageUrl = ""
                            resolvedDex = 0
                            val request = ++speciesLookupRevision
                            if (it.length > 2) coroutineScope.launch {
                                val dex = PokeApiClient.fetchDexNumber(it)
                                if (request == speciesLookupRevision && dex > 0) {
                                    resolvedDex = dex
                                    if (repository.prefilledPage == null) {
                                        val position = PokeApiClient.binderPosition(dex)
                                        page = position.first.toString()
                                        slot = position.second.toString()
                                    }
                                }
                            }
                        },
                        onFormChange = {
                            resolvedForm = it
                            selectedCatalogCard = null
                            resolvedImageUrl = ""
                            speciesLookupRevision++
                        },
                        onCardLanguageChange = {
                            resolvedCardLanguage = it
                            selectedCatalogCard = null
                            resolvedImageUrl = ""
                        },
                        onCatalogLocaleChange = {
                            catalogLocale = it
                            selectedCatalogCard = null
                            resolvedImageUrl = ""
                        },
                        onCatalogVariantChange = { resolvedVariant = it },
                        onCatalogSetHintChange = { catalogSetHint = it; selectedCatalogCard = null; resolvedImageUrl = "" },
                        onCatalogNumberHintChange = { catalogNumberHint = it; selectedCatalogCard = null; resolvedImageUrl = "" },
                        onDexChange = {
                            val dex = it.toIntOrNull() ?: 0
                            resolvedDex = dex
                            speciesLookupRevision++
                            selectedCatalogCard = null
                            resolvedImageUrl = ""
                            if (dex > 0 && repository.prefilledPage == null) {
                                val position = PokeApiClient.binderPosition(dex)
                                page = position.first.toString()
                                slot = position.second.toString()
                            }
                        },
                        onRarityChange = { resolvedRarity = it },
                        onGradeChange = { resolvedGrade = it.toDoubleOrNull() ?: 0.0 },
                        onCritiqueChange = { resolvedCritique = it },
                        onGradeDetailsChange = { resolvedGradeDetails = it },
                        onNotesChange = { personalNotes = it },
                        onCatalogSelected = {
                            selectedCatalogCard = it
                            resolvedImageUrl = it?.imageUrl.orEmpty()
                            it?.speciesDex?.takeIf { dex -> dex > 0 }?.let { dex ->
                                resolvedDex = dex
                                speciesLookupRevision++
                                if (repository.prefilledPage == null) {
                                    val position = PokeApiClient.binderPosition(dex)
                                    page = position.first.toString()
                                    slot = position.second.toString()
                                }
                            }
                            if (!it?.rarity.isNullOrBlank()) resolvedRarity = it!!.rarity
                        },
                        onPageChange = { page = it },
                        onSlotChange = { slot = it },
                        onConfirm = {
                            coroutineScope.launch {
                                val finalPage = page.toIntOrNull()
                                val finalSlot = slot.toIntOrNull()
                                if (resolvedName.isBlank() || finalPage == null || finalPage < 1 || finalSlot == null || finalSlot !in 1..9 || (resolvedGrade != 0.0 && (!resolvedGrade.isFinite() || resolvedGrade !in 1.0..10.0))) {
                                    saveError = "Enter a name, valid page and slot before saving."
                                    return@launch
                                }
                                if (cards.any { it.page == finalPage && it.slot == finalSlot }) {
                                    saveError = "That binder slot is occupied. Choose another slot; the existing card was not changed."
                                    return@launch
                                }
                                val card = Card(
                                    page = finalPage,
                                    slot = finalSlot,
                                    dexNumber = resolvedDex,
                                    name = resolvedName.trim(),
                                    type = resolvedRarity,
                                    // ponytail: coarse mapping until reference cards can calibrate category thresholds.
                                    condition = when {
                                        resolvedGrade == 0.0 -> "UNASSESSED"
                                        resolvedGrade >= 8.0 -> "NM"
                                        resolvedGrade >= 6.0 -> "LP"
                                        resolvedGrade >= 4.0 -> "MP"
                                        else -> "HP"
                                    },
                                    notes = personalNotes.trim(),
                                    gradeDetails = resolvedGradeDetails,
                                    speciesName = resolvedSpecies.trim(),
                                    form = resolvedForm.trim(),
                                    cardLanguage = resolvedCardLanguage.trim(),
                                    catalogProvider = selectedCatalogCard?.provider ?: if (catalogLocale.isNotBlank()) "TCGdex" else "",
                                    catalogLocale = selectedCatalogCard?.locale ?: catalogLocale,
                                    catalogVariant = resolvedVariant.trim(),
                                    catalogCardId = selectedCatalogCard?.id.orEmpty(),
                                    setId = selectedCatalogCard?.setId.orEmpty(),
                                    setName = selectedCatalogCard?.setName ?: catalogSetHint.trim(),
                                    cardNumber = selectedCatalogCard?.number ?: catalogNumberHint.trim(),
                                    catalogRarity = selectedCatalogCard?.rarity.orEmpty(),
                                    dateAdded = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                                    grade = resolvedGrade,
                                    imageUrl = selectedCatalogCard?.imageUrl?.takeIf { it.isNotBlank() } ?: resolvedImageUrl,
                                    imageSource = selectedCatalogCard?.imageSource.orEmpty(),
                                    imageMatchId = selectedCatalogCard?.imageMatchId.orEmpty()
                                )
                                // No training-photo upload without an explicit opt-in flow.
                                if (repository.addCard(card)) {
                                    onCardAdded(card.page)
                                    onNavigateBack()
                                }
                                else saveError = repository.errorMessage.value ?: "Could not save the card. Check the connection and retry; photos are still here."
                            }
                        },
                        onCancel = {
                            frontBitmap = null
                            backBitmap = null
                            scanState = ScanState.CAMERA
                        }
                    )
                }
            }
        }
    }
}

suspend fun takePhoto(context: android.content.Context, imageCapture: ImageCapture): Bitmap? = withContext(Dispatchers.IO) {
    var bitmap: Bitmap? = null
    val latch = java.util.concurrent.CountDownLatch(1)

    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val matrix = android.graphics.Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                    val oriented = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                    // Preserve the whole card and enough detail for review; very large photos are capped for memory.
                    val scale = 4096f / maxOf(oriented.width, oriented.height)
                    bitmap = if (scale < 1f) Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt(), (oriented.height * scale).toInt(), true) else oriented
                } catch (_: Exception) {
                    bitmap = null
                } finally {
                    image.close()
                    latch.countDown()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                exception.printStackTrace()
                latch.countDown()
            }
        }
    )

    latch.await()
    bitmap
}

private suspend fun identifyCardWithGemini(
    front: Bitmap,
    personalGemini: PersonalGeminiSession?,
    onRetry: (attempt: Int, delayMs: Long) -> Unit
): JSONObject {
    val prompt = """
        Analyze the front image of this physical Pokémon card.
        Transcribe the printed card name exactly as shown, preserving its language and writing system.
        Separately identify the base Pokémon species and regional form only when confident.
        Identify the physical card language, printed expansion set name, printed card number,
        rarity and finish/variant only when visible. Do not translate the printed name.
        For every uncertain or unreadable field, return an empty string. Never guess a Pokédex number.

        Return ONLY a JSON object:
        {
          "printed_name": "Name exactly as printed",
          "species_name": "Canonical species name or empty",
          "form": "Regional form or empty",
          "card_language": "Language name or code, or empty",
          "rarity": "Visible rarity or empty",
          "variant": "Finish or variant, or empty",
          "set_name": "Printed expansion name or empty",
          "card_number": "123/198"
        }
    """.trimIndent()

    return requestCardJson(prompt, listOf(front), personalGemini, onRetry)
}

private suspend fun gradeCardWithGemini(
    front: Bitmap,
    back: Bitmap,
    personalGemini: PersonalGeminiSession?,
    onRetry: (attempt: Int, delayMs: Long) -> Unit = { _, _ -> }
): JSONObject {
    val json = requestCardJson(CONDITION_ESTIMATE_PROMPT, listOf(front, back), personalGemini, onRetry)
    parseConditionEstimate(json)
    return json
}

private suspend fun requestCardJson(
    prompt: String,
    photos: List<Bitmap>,
    personalGemini: PersonalGeminiSession?,
    onRetry: (attempt: Int, delayMs: Long) -> Unit
): JSONObject {
    val personal = personalGemini?.client?.value
    val answer = withContext(Dispatchers.IO) {
        if (personal != null) {
            val images = photos.map { photo ->
                val scale = minOf(1f, 2560f / maxOf(photo.width, photo.height))
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
                    photo, (photo.width * scale).toInt().coerceAtLeast(1), (photo.height * scale).toInt().coerceAtLeast(1), true
                ) else photo
                try {
                    val jpeg = ByteArrayOutputStream()
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 90, jpeg)) { "Couldn't prepare the photo for scanning." }
                    Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
                } finally {
                    if (scaled !== photo) scaled.recycle()
                }
            }
            check(personal === personalGemini?.client?.value) { "The Gemini key changed. Try scanning again or use manual entry." }
            personal.generateContent(prompt, images)
        } else {
            val model = FirebaseAiClient.model()
            val input = content {
                photos.forEach { image(it) }
                text(prompt)
            }
            retryAiQuota(onRetry) {
                model.generateContent(input).text?.trim()
                    ?: throw IllegalStateException("Empty response from AI")
            }
        }
    }
    return try {
        JSONObject(answer.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    } catch (_: JSONException) {
        throw IllegalStateException("Gemini returned an unreadable result. You can enter the card manually.")
    }
}

@Composable
fun CameraViewfinder(
    side: ScanSide,
    imageCapture: ImageCapture,
    captureError: String,
    onCapture: () -> Unit,
    onManualEntry: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay UI
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (side == ScanSide.BACK) Color.White else Color(0x66FFFFFF)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (side == ScanSide.FRONT) "Scan Front Side" else "Scan Back Side",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .aspectRatio(0.714f)
                    .border(2.dp, Color.White, RoundedCornerShape(16.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Text("✖", color = Color.White, fontSize = 24.sp)
                }

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { onCapture() }
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(2.dp, Color.Black, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = "AI Scan", tint = Color.Black)
                    }
                }

                IconButton(onClick = onManualEntry) {
                    Icon(Icons.Default.Edit, contentDescription = "Manual Entry", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            if (captureError.isNotEmpty()) Text(captureError, color = Color(0xFFEF4444))
        }
    }
}

@Composable
fun GradingLoadingScreen(status: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "grading")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .drawWithContent {
                    drawContent()
                    val brush = Brush.sweepGradient(
                        colors = listOf(Color(0xFFEF4444), Color(0xFF3B82F6), Color(0xFFEF4444)),
                        center = center
                    )
                    drawCircle(brush = brush, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx()))
                },
            contentAlignment = Alignment.Center
        ) {
            Text("AI", color = Color.White, fontWeight = FontWeight.Black, fontSize = 32.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text("Gemini Card Critic", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(status, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

// ─────────────────────────────────────────────────────────────
// Autocomplete dropdown field for Pokémon names
// ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PokemonNameField(
    value: String,
    allNames: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Pokémon Name"
) {
    val suggestions = remember(value, allNames) {
        PokeApiClient.searchPokemon(value, allNames, limit = 6)
    }
    var expanded by remember { mutableStateOf(false) }

    // Show dropdown only when there are suggestions and something has been typed
    LaunchedEffect(suggestions) {
        expanded = suggestions.isNotEmpty() && value.length >= 2
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label, color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFEF4444),
                unfocusedBorderColor = Color(0xFF334155)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        if (suggestions.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = Color(0xFF0F172A)
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(suggestion, color = Color.White) },
                        onClick = {
                            onValueChange(suggestion)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Simple number dropdown (for Page 1-20 and Slot 1-9)
// ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberDropdownField(
    value: String,
    label: String,
    options: List<Int>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = Color.Gray) },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Gray)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFEF4444),
                unfocusedBorderColor = Color(0xFF334155)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color(0xFF0F172A)
        ) {
            options.forEach { n ->
                DropdownMenuItem(
                    text = { Text(n.toString(), color = Color.White) },
                    onClick = {
                        onValueChange(n.toString())
                        expanded = false
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Confirmation / Manual Entry Screen
// ─────────────────────────────────────────────────────────────
@Composable
fun ConfirmationScreen(
    name: String,
    dex: Int,
    rarity: String,
    grade: Double,
    critique: String,
    page: String,
    slot: String,
    isManual: Boolean,
    allPokemonNames: List<String>,
    frontBitmap: Bitmap?,
    backBitmap: Bitmap?,
    saveError: String,
    onNameChange: (String) -> Unit,
    onDexChange: (String) -> Unit,
    onRarityChange: (String) -> Unit,
    onGradeChange: (String) -> Unit,
    onCritiqueChange: (String) -> Unit,
    onPageChange: (String) -> Unit,
    onSlotChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    gradeDetails: String = "",
    personalNotes: String = "",
    catalogCard: CatalogCard? = null,
    catalogSetHint: String = "",
    catalogNumberHint: String = "",
    onGradeDetailsChange: (String) -> Unit = {},
    onNotesChange: (String) -> Unit = {},
    personalGemini: PersonalGeminiSession? = null,
    onCatalogSelected: (CatalogCard?) -> Unit = {},
    speciesName: String = "",
    form: String = "",
    cardLanguage: String = "",
    catalogLocale: String = "en",
    catalogVariant: String = "",
    onSpeciesNameChange: (String) -> Unit = {},
    onFormChange: (String) -> Unit = {},
    onCardLanguageChange: (String) -> Unit = {},
    onCatalogLocaleChange: (String) -> Unit = {},
    onCatalogVariantChange: (String) -> Unit = {},
    onCatalogSetHintChange: (String) -> Unit = {},
    onCatalogNumberHintChange: (String) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var isGrading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = if (isManual) "Manual Card Entry" else "Card identification",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Pokémon image preview card ──────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Pokémon artwork — updates live as dex changes
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A)),
                    contentAlignment = Alignment.Center
                ) {
                    val speciesImage = if (dex > 0) "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/" + dex + ".png" else null
                    val cardImage = catalogCard?.imageUrl?.takeIf { it.startsWith("https://assets.tcgdex.net/") }
                    if (cardImage != null || speciesImage != null) {
                        AsyncImage(
                            url = cardImage ?: speciesImage!!,
                            fallbackUrl = if (cardImage != null) speciesImage else null,
                            contentDescription = name,
                            modifier = Modifier.size(80.dp)
                        )
                    } else {
                        Text("?", color = Color(0xFF475569), fontSize = 32.sp, fontWeight = FontWeight.Black)
                    }
                }

                // Info column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (name.isBlank()) "Unknown Pokémon" else name.capitalize(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rarity chip
                        if (rarity.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF334155))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = rarity, color = Color.White, fontSize = 10.sp)
                            }
                        }
                        // Grade chip (AI scans only)
                        if (!isManual && grade > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF1C1A00))
                                    .border(1.dp, Color(0xFFEAB308), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFEAB308),
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = grade.let {
                                        if (it == it.toLong().toDouble()) it.toLong().toString() else "%.1f".format(it)
                                    },
                                    color = Color(0xFFEAB308),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(text = " / 10", color = Color(0xFF92710A), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

                if (!isManual && (frontBitmap != null || backBitmap != null)) {
            Spacer(modifier = Modifier.height(16.dp))
            if (grade == 0.0 && !isGrading) {
                Button(
                    onClick = {
                        if (!isGrading && frontBitmap != null && backBitmap != null) {
                            isGrading = true
                            coroutineScope.launch {
                                try {
                                    val estimate = parseConditionEstimate(gradeCardWithGemini(frontBitmap, backBitmap, personalGemini))
                                    onGradeChange(estimate.grade?.toString() ?: "")
                                    onCritiqueChange(estimate.evidence)
                                    onGradeDetailsChange(estimate.detailsJson)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    onGradeChange("")
                                    onCritiqueChange("Condition estimate unavailable: " + e.message)
                                    onGradeDetailsChange(
                                        JSONObject().put("assessable", false).put("grade", JSONObject.NULL)
                                            .put("reason", e.message ?: "Condition estimate unavailable").toString()
                                    )
                                } finally {
                                    isGrading = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEAB308)),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ESTIMATE CONDITION (BETA)", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            } else if (isGrading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = Color(0xFFEAB308), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Estimating condition from both photos...", color = Color.LightGray, fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Editable fields ────────────────────────────────────

        // Pokémon Name with autocomplete
        PokemonNameField(
            value = name,
            allNames = allPokemonNames,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = "Printed card name"
        )

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = speciesName,
            onValueChange = onSpeciesNameChange,
            label = { Text("Species name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = form,
            onValueChange = onFormChange,
            label = { Text("Regional form") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        TcgDexLanguagePicker(
            "Physical card language",
            cardLanguage.takeIf(TcgDexLocale::isSupported).orEmpty(),
            onCardLanguageChange
        )
        if (!TcgDexLocale.isSupported(cardLanguage)) {
            OutlinedTextField(
                value = cardLanguage,
                onValueChange = onCardLanguageChange,
                label = { Text("Other physical language") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TcgDexLanguagePicker("Catalog language", catalogLocale, onCatalogLocaleChange)
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(catalogSetHint, onCatalogSetHintChange, label = { Text("Printed set name") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(catalogNumberHint, onCatalogNumberHintChange, label = { Text("Printed card number") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(catalogVariant, onCatalogVariantChange, label = { Text("Catalog card variant / finish") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(modifier = Modifier.height(12.dp))
        CatalogSetPicker(
            name = name,
            selected = catalogCard,
            setHint = catalogSetHint,
            numberHint = catalogNumberHint,
            locale = catalogLocale,
            identityKey = "$speciesName|$form|$cardLanguage",
            onSelected = onCatalogSelected
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row {
            OutlinedTextField(
                value = if (dex > 0) dex.toString() else "",
                onValueChange = onDexChange,
                label = { Text("Dex #", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFEF4444),
                    unfocusedBorderColor = Color(0xFF334155)
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            var expandedRarity by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1.5f)) {
                OutlinedTextField(
                    value = rarity,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Rarity", color = Color.Gray) },
                    trailingIcon = {
                        IconButton(onClick = { expandedRarity = true }) {
                            Icon(Icons.Default.ArrowDropDown, null, tint = Color.Gray)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFEF4444),
                        unfocusedBorderColor = Color(0xFF334155)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = expandedRarity,
                    onDismissRequest = { expandedRarity = false },
                    containerColor = Color(0xFF0F172A)
                ) {
                    listOf(
                        "Mega Hyper Rare", "Hyper Rare", "Mega Attack Rare",
                        "Special Illustration Rare", "Illustration Rare", "Ace Spec Rare",
                        "Secret Rare", "Ultra Rare", "Double Rare", "Shiny Rare",
                        "Reverse Holo", "Holofoil Rare", "Rare", "Normal"
                    ).forEach { r ->
                        DropdownMenuItem(
                            text = { Text(r, color = Color.White) },
                            onClick = { onRarityChange(r); expandedRarity = false }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Page (suggestions 1-20) + Slot (suggestions 1-9)
        Row {
            NumberDropdownField(
                value = page,
                label = "Page",
                options = (1..20).toList(),
                onValueChange = onPageChange,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            NumberDropdownField(
                value = slot,
                label = "Slot",
                options = (1..9).toList(),
                onValueChange = onSlotChange,
                modifier = Modifier.weight(1f)
            )
        }

        if (isManual) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = if (grade > 0) grade.toString() else "",
                onValueChange = onGradeChange,
                label = { Text("Condition Grade (1.0 - 10.0)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFEF4444),
                    unfocusedBorderColor = Color(0xFF334155)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (grade > 0 || gradeDetails.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            val condition = when {
                grade <= 0 -> "UNASSESSED"
                grade >= 8.0 -> "NM"
                grade >= 6.0 -> "LP"
                grade >= 4.0 -> "MP"
                else -> "HP"
            }
            GradeSummary(grade, catalogCard?.rarity.orEmpty(), condition, gradeDetails)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Notes", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = personalNotes,
            onValueChange = onNotesChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFEF4444),
                unfocusedBorderColor = Color(0xFF334155)
            )
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (saveError.isNotEmpty()) Text(saveError, color = Color(0xFFEF4444))

        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("COMMIT TO BINDER", color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("CANCEL", color = Color.Gray)
        }

        // Bottom padding so last field isn't hidden behind keyboard
        Spacer(modifier = Modifier.height(32.dp))
    }
}
