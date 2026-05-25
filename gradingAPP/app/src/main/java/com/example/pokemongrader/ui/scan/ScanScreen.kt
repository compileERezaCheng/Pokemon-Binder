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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

enum class ScanState {
    CAMERA,
    GRADING,
    CONFIRM,
    MANUAL
}

enum class ScanSide {
    FRONT, BACK
}

@Composable
fun ScanScreen(
    repository: DataRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
    var resolvedSet by remember { mutableStateOf("") }
    var resolvedDex by remember { mutableStateOf(0) }
    var resolvedRarity by remember { mutableStateOf("Normal") }
    var resolvedGrade by remember { mutableStateOf(0.0) }
    var resolvedCritique by remember { mutableStateOf("") }

    // Status text for loading screen
    var gradingStatus by remember { mutableStateOf("Analyzing Centering, Corners & Surface...") }

    // Coordinates
    var page by remember { mutableStateOf(repository.prefilledPage?.toString() ?: "1") }
    var slot by remember { mutableStateOf(repository.prefilledSlot?.toString() ?: "1") }

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
                        onCapture = {
                            coroutineScope.launch {
                                val bitmap = takePhoto(context, imageCapture)
                                if (bitmap != null) {
                                    if (scanSide == ScanSide.FRONT) {
                                        frontBitmap = bitmap
                                        scanSide = ScanSide.BACK
                                    } else {
                                        backBitmap = bitmap
                                        gradingStatus = "Analyzing Centering, Corners & Surface..."
                                        scanState = ScanState.GRADING

                                        try {
                                            val result = processWithGemini(
                                                frontBitmap!!,
                                                backBitmap!!,
                                                onRetry = { attempt, delayMs ->
                                                    gradingStatus = "Rate limit reached. Retrying in ${delayMs / 1000}s... (Attempt $attempt)"
                                                }
                                            )
                                            resolvedName = result.optString("name", "Unknown")
                                            resolvedSet = result.optString("set", "Unknown")
                                            resolvedRarity = result.optString("rarity", "Common")
                                            resolvedGrade = result.optDouble("grade", 0.0)
                                            resolvedCritique = result.optString("critique", "No critique provided.")
                                            resolvedDex = PokeApiClient.fetchDexNumber(resolvedName)
                                        } catch (e: Exception) {
                                            resolvedName = "Error"
                                            val fullMsg = e.toString()
                                            resolvedCritique = when {
                                                fullMsg.contains("MissingFieldException") ->
                                                    "AI Error: Connection failed (SDK Bug). Please try again in 30 seconds."
                                                fullMsg.contains("404") ->
                                                    "AI Error: Model not found. Updating configuration..."
                                                fullMsg.contains("429") || fullMsg.contains("quota", ignoreCase = true) ->
                                                    "AI Error: Daily Quota reached. Please try scanning again in a few minutes or tomorrow."
                                                else -> "AI Error: ${e.message}"
                                            }
                                            e.printStackTrace()
                                        }

                                        scanState = ScanState.CONFIRM
                                        scanSide = ScanSide.FRONT
                                    }
                                }
                            }
                        },
                        onManualEntry = { scanState = ScanState.MANUAL },
                        onCancel = {
                            scanSide = ScanSide.FRONT
                            onNavigateBack()
                        }
                    )
                }
                ScanState.GRADING -> {
                    GradingLoadingScreen(status = gradingStatus)
                }
                ScanState.CONFIRM, ScanState.MANUAL -> {
                    ConfirmationScreen(
                        name = resolvedName,
                        set = resolvedSet,
                        dex = resolvedDex,
                        rarity = resolvedRarity,
                        grade = resolvedGrade,
                        critique = resolvedCritique,
                        page = page,
                        slot = slot,
                        isManual = scanState == ScanState.MANUAL,
                        allPokemonNames = allPokemonNames,
                        onNameChange = {
                            resolvedName = it
                            if (it.length > 2) {
                                coroutineScope.launch {
                                    val dex = PokeApiClient.fetchDexNumber(it)
                                    if (dex > 0) resolvedDex = dex
                                }
                            }
                        },
                        onSetChange = { resolvedSet = it },
                        onDexChange = { resolvedDex = it.toIntOrNull() ?: 0 },
                        onRarityChange = { resolvedRarity = it },
                        onGradeChange = { resolvedGrade = it.toDoubleOrNull() ?: 0.0 },
                        onCritiqueChange = { resolvedCritique = it },
                        onPageChange = { page = it },
                        onSlotChange = { slot = it },
                        onConfirm = {
                            coroutineScope.launch {
                                val card = Card(
                                    page = page.toIntOrNull() ?: 1,
                                    slot = slot.toIntOrNull() ?: 1,
                                    dexNumber = resolvedDex,
                                    name = resolvedName.trim().lowercase(),
                                    type = resolvedRarity,
                                    condition = "NM",
                                    notes = if (resolvedSet.isNotEmpty()) "[$resolvedSet] $resolvedCritique" else resolvedCritique,
                                    dateAdded = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                                    grade = resolvedGrade
                                )
                                repository.addCard(card)
                                onNavigateBack()
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
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                val matrix = android.graphics.Matrix()
                matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
                val rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)

                val targetDimension = 1024f
                val scale = targetDimension / Math.max(rotatedBitmap.width, rotatedBitmap.height)
                bitmap = if (scale < 1.0f) {
                    Bitmap.createScaledBitmap(rotatedBitmap, (rotatedBitmap.width * scale).toInt(), (rotatedBitmap.height * scale).toInt(), true)
                } else {
                    rotatedBitmap
                }

                image.close()
                latch.countDown()
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

suspend fun processWithGemini(
    front: Bitmap,
    back: Bitmap,
    onRetry: (attempt: Int, delayMs: Long) -> Unit
): JSONObject = withContext(Dispatchers.IO) {
    val apiKey = decryptKey(BuildConfig.ENC_GEMINI_API_KEY)
    if (apiKey.isEmpty() || apiKey == "PLACEHOLDER_ENC_GEMINI_API_KEY") {
        throw Exception("Gemini API Key missing or invalid.")
    }

    val modelVariations = listOf(
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash",
        "gemini-2.5-flash"
    )
    var lastException: Exception? = null

    for (modelName in modelVariations) {
        try {
            val generativeModel = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey
            )

            val prompt = """
                Analyze these two images of a physical Pokémon card (Front and Back).
                
                1. PRIMARY TASK - IDENTIFICATION:
                - Identify the Pokémon name (top of card).
                - ZOOM IN and read the bottom-left and bottom-right corners very carefully.
                - Look for the COLLECTOR NUMBER (e.g., 045/198, 123/203) and the SET SYMBOL.
                - Identify the EXACT EXPANSION SET based on the symbol and collector number. 
                
                2. SECONDARY TASK - GRADING (Scale 1.0 - 10.0):
                - Centering: Analyze border width consistency.
                - Corners: Check for rounding and white spots.
                - Edges: Check for silvering, nicks, or wear.
                - Surface: Check for scratches, print lines, or stains.
                
                Return ONLY a JSON object:
                {
                  "name": "Pokémon Name",
                  "set": "Exact Expansion Name",
                  "rarity": "Rarity Tier",
                  "grade": 9.2,
                  "critique": "[C: 0.0, Cr: 0.0, E: 0.0, S: 0.0] Reason for grade..."
                }
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

                    return@withContext JSONObject(jsonStr)
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
                    .fillMaxWidth(0.8f)
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
    set: String,
    dex: Int,
    rarity: String,
    grade: Double,
    critique: String,
    page: String,
    slot: String,
    isManual: Boolean,
    allPokemonNames: List<String>,
    onNameChange: (String) -> Unit,
    onSetChange: (String) -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = if (isManual) "Manual Card Entry" else "AI Analysis Result",
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
                    if (set.isNotBlank()) {
                        Text(text = set, color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
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

        OutlinedTextField(
            value = set,
            onValueChange = onSetChange,
            label = { Text("Expansion Set", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFEF4444),
                unfocusedBorderColor = Color(0xFF334155)
            ),
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
            if (isManual) "Notes" else "Critic Notes",
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
