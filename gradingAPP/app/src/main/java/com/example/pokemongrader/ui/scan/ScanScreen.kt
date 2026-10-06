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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.pokemongrader.BuildConfig
import com.example.pokemongrader.data.Card
import com.example.pokemongrader.data.DataRepository
import com.example.pokemongrader.data.PokeApiClient
import com.example.pokemongrader.ui.main.AsyncImage
import com.example.pokemongrader.ui.main.capitalize
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject


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

data class ConditionEstimate(val grade: Double?, val evidence: String)

fun parseConditionEstimate(json: JSONObject): ConditionEstimate {
    val assessable = json.getBoolean("assessable")
    if (!assessable) {
        require(json.has("grade") && json.isNull("grade")) { "Unevaluable response must have a null grade" }
        val reason = json.getString("reason").trim()
        require(reason.isNotEmpty()) { "Missing reason" }
        return ConditionEstimate(null, "Not assessable: $reason")
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
    return ConditionEstimate(grade, evidence)
}

@Composable
fun ScanScreen(
    repository: DataRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

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

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // Result Data
    var resolvedName by remember { mutableStateOf("") }
    var resolvedDex by remember { mutableStateOf(0) }
    var resolvedRarity by remember { mutableStateOf("Normal") }
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
        if (!hasCameraPermission && scanState != ScanState.MANUAL) {
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
                            resolvedRarity = "Normal"
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
                                if (scanSide == ScanSide.FRONT) {
                                    scanSide = ScanSide.BACK
                                    scanState = ScanState.CAMERA
                                } else {
                                    scanState = ScanState.GRADING
                                    gradingStatus = "Identifying the card from its front..."
                                    coroutineScope.launch {
                                        try {
                                            val result = identifyCardWithGemini(frontBitmap!!) { attempt, delayMs ->
                                                gradingStatus = "Quota limit. Retry $attempt in ${delayMs / 1000}s..."
                                            }
                                            resolvedName = result.getString("name")
                                            resolvedRarity = result.getString("rarity")
                                            resolvedDex = result.getInt("dex_number")
                                            if (resolvedDex > 0 && repository.prefilledPage == null) {
                                                page = (((resolvedDex - 1) / 9) + 1).toString()
                                                slot = (((resolvedDex - 1) % 9) + 1).toString()
                                            }
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
                        dex = resolvedDex,
                        rarity = resolvedRarity,
                        grade = resolvedGrade,
                        critique = resolvedCritique,
                        page = page,
                        slot = slot,
                        isManual = scanState == ScanState.MANUAL,
                        allPokemonNames = allPokemonNames,
                        frontBitmap = frontBitmap,
                        backBitmap = backBitmap,
                        saveError = saveError,
                        onNameChange = {
                            resolvedName = it
                            if (it.length > 2) {
                                coroutineScope.launch {
                                    val dex = PokeApiClient.fetchDexNumber(it)
                                    if (dex > 0) {
                                        resolvedDex = dex
                                        if (repository.prefilledPage == null) {
                                            page = (((dex - 1) / 9) + 1).toString()
                                            slot = (((dex - 1) % 9) + 1).toString()
                                        }
                                    }
                                }
                            }
                        },
                        onDexChange = {
                            val dex = it.toIntOrNull() ?: 0
                            resolvedDex = dex
                            if (dex > 0 && repository.prefilledPage == null) {
                                page = (((dex - 1) / 9) + 1).toString()
                                slot = (((dex - 1) % 9) + 1).toString()
                            }
                        },
                        onRarityChange = { resolvedRarity = it },
                        onGradeChange = { resolvedGrade = it.toDoubleOrNull() ?: 0.0 },
                        onCritiqueChange = { resolvedCritique = it },
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
                                val card = Card(
                                    page = finalPage,
                                    slot = finalSlot,
                                    dexNumber = resolvedDex,
                                    name = resolvedName.trim().lowercase(),
                                    type = resolvedRarity,
                                    // ponytail: coarse mapping until reference cards can calibrate category thresholds.
                                    condition = when {
                                        resolvedGrade == 0.0 -> "UNASSESSED"
                                        resolvedGrade >= 8.0 -> "NM"
                                        resolvedGrade >= 6.0 -> "LP"
                                        resolvedGrade >= 4.0 -> "MP"
                                        else -> "HP"
                                    },
                                    notes = resolvedCritique,
                                    dateAdded = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                                    grade = resolvedGrade
                                )
                                // No training-photo upload without an explicit opt-in flow.
                                if (repository.addCard(card)) onNavigateBack()
                                else saveError = "Could not save the card. Check the connection and retry; photos are still here."
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

private fun decryptKey(encrypted: String): String {
    val key = "PokeGraderSecureKey2026"
    return try {
        val decodedBytes = Base64.decode(encrypted, Base64.DEFAULT)
        val decodedStr = String(decodedBytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        for (i in decodedStr.indices) {
            sb.append((decodedStr[i].code xor key[i % key.length].code).toChar())
        }
        sb.toString()
    } catch (e: Exception) {
        ""
    }
}

suspend fun identifyCardWithGemini(
    front: Bitmap,
    onRetry: (attempt: Int, delayMs: Long) -> Unit
): JSONObject = withContext(Dispatchers.IO) {
    val apiKey = decryptKey(BuildConfig.ENC_GEMINI_API_KEY)
    if (apiKey.isEmpty() || apiKey == "PLACEHOLDER_ENC_GEMINI_API_KEY") {
        throw Exception("Gemini API Key missing or invalid.")
    }

    val modelVariations = listOf("gemini-3.5-flash-lite")
    var lastException: Exception? = null

    for (modelName in modelVariations) {
        try {
            val generativeModel = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey
            )

            val prompt = """
                Analyze the front image of this physical Pokémon card.
                Identify only the standard English Pokémon name, national Pokedex number, and visible rarity.
                Do not infer the expansion set. If identification is uncertain, return an empty name.
                
                Return ONLY a JSON object:
                {
                  "name": "Pokémon Name",
                  "dex_number": 25,
                  "rarity": "Rarity Tier"
                }
            """.trimIndent()

            val inputContent = content {
                image(front)
                text(prompt)
            }

            var delayMs = 4000L
            for (attempt in 1..4) {
                try {
                    val response = generativeModel.generateContent(inputContent)
                    val text = response.text?.trim() ?: throw Exception("Empty response from AI")

                    val jsonStr = if (text.contains("```json")) {
                        text.substringAfter("```json").substringBefore("```").trim()
                    } else if (text.contains("```")) {
                        text.substringAfter("```").substringBeforeLast("```").trim()
                    } else {
                        text
                    }

                    val json = JSONObject(jsonStr)
                    val rName = json.getString("name").trim()
                    if (rName.isEmpty() || rName.equals("Unknown", ignoreCase = true)) {
                        throw Exception("Model $modelName returned Unknown name.")
                    }
                    require(json.getInt("dex_number") > 0 && json.getString("rarity").isNotBlank()) { "Incomplete identification" }
                    return@withContext json
                } catch (e: Exception) {
                    val msgText = e.toString()
                    if (msgText.contains("429") || msgText.contains("Too Many Requests", ignoreCase = true) || msgText.contains("quota", ignoreCase = true)) {
                        if (attempt < 4) {
                            onRetry(attempt, delayMs)
                            delay(delayMs)
                            delayMs += 4000L
                            continue
                        }
                    }
                    if (msgText.contains("404") || msgText.contains("not found", ignoreCase = true)) {
                        throw e
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            lastException = e
            val msgText = e.toString()
            if (msgText.contains("404") || msgText.contains("not found", ignoreCase = true)) {
                continue
            } else {
                throw e
            }
        }
    }

    throw lastException ?: Exception("Unknown error during Gemini processing")
}

suspend fun gradeCardWithGemini(
    front: Bitmap,
    back: Bitmap,
    onRetry: (attempt: Int, delayMs: Long) -> Unit = { _, _ -> }
): JSONObject = withContext(Dispatchers.IO) {
    val apiKey = decryptKey(BuildConfig.ENC_GEMINI_API_KEY)
    if (apiKey.isEmpty() || apiKey == "PLACEHOLDER_ENC_GEMINI_API_KEY") {
        throw Exception("Gemini API Key missing or invalid.")
    }

    val modelVariations = listOf("gemini-3.5-flash-lite")
    var lastException: Exception? = null

    for (modelName in modelVariations) {
        try {
            val generativeModel = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey
            )

            val prompt = """
                Estimate physical condition from these front and back photos. This is not professional certification.
                Look for observable evidence only: border centering, corner wear, edge wear, and surface defects.
                If either photo is cropped, blurred, obscured by glare, or too small to assess any criterion,
                return {"assessable":false,"grade":null,"reason":"short observable reason"}.
                Otherwise return ONLY a JSON object: assessable=true; numeric grade from 1 to 10;
                criteria with centering, corners, edges and surface objects. Each object needs a numeric
                score from 1 to 10 and a short evidence string describing a visible observation.
                If a defect cannot be observed reliably, use the non-assessable form. Do not invent defects or scores.
            """.trimIndent()

            val inputContent = content {
                image(front)
                image(back)
                text(prompt)
            }

            var delayMs = 4000L
            for (attempt in 1..4) {
                try {
                    val response = generativeModel.generateContent(inputContent)
                    val text = response.text?.trim() ?: throw Exception("Empty response from AI")

                    val jsonStr = if (text.contains("```json")) {
                        text.substringAfter("```json").substringBefore("```").trim()
                    } else if (text.contains("```")) {
                        text.substringAfter("```").substringBeforeLast("```").trim()
                    } else {
                        text
                    }

                    val json = JSONObject(jsonStr)
                    parseConditionEstimate(json)
                    return@withContext json
                } catch (e: Exception) {
                    val msgText = e.toString()
                    if (msgText.contains("429") || msgText.contains("Too Many Requests", ignoreCase = true) || msgText.contains("quota", ignoreCase = true)) {
                        if (attempt < 4) {
                            onRetry(attempt, delayMs)
                            delay(delayMs)
                            delayMs += 4000L
                            continue
                        }
                    }
                    if (msgText.contains("404") || msgText.contains("not found", ignoreCase = true)) {
                        throw e
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            lastException = e
            val msgText = e.toString()
            if (msgText.contains("404") || msgText.contains("not found", ignoreCase = true)) {
                continue
            } else {
                throw e
            }
        }
    }

    throw lastException ?: Exception("Unknown error during Gemini processing")
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
    modifier: Modifier = Modifier
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
            label = { Text("Pokémon Name", color = Color.Gray) },
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
    onCancel: () -> Unit
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
                    if (dex > 0) {
                        val artUrl = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/$dex.png"
                        AsyncImage(url = artUrl, contentDescription = name, modifier = Modifier.size(80.dp))
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
                        if (frontBitmap != null && backBitmap != null) {
                            isGrading = true
                            coroutineScope.launch {
                                try {
                                    val estimate = parseConditionEstimate(gradeCardWithGemini(frontBitmap, backBitmap))
                                    onGradeChange(estimate.grade?.toString() ?: "")
                                    onCritiqueChange(estimate.evidence)
                                } catch (e: Exception) {
                                    onGradeChange("")
                                    onCritiqueChange("Condition estimate unavailable: ${e.message}")
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
            value = if (isManual) name else name.capitalize(),
            allNames = allPokemonNames,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth()
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

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            if (isManual) "Notes" else "Condition observations",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = critique,
            onValueChange = onCritiqueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
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
