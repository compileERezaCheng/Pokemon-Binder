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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
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
        onResult = { granted ->
            hasCameraPermission = granted
        }
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

    LaunchedEffect(Unit) {
        repository.prefilledPage = null
        repository.prefilledSlot = null
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
                                // Capture photo
                                val bitmap = takePhoto(context, imageCapture)
                                if (bitmap != null) {
                                    if (scanSide == ScanSide.FRONT) {
                                        frontBitmap = bitmap
                                        scanSide = ScanSide.BACK
                                    } else {
                                        backBitmap = bitmap
                                        // Both sides captured — enter grading
                                        gradingStatus = "Analyzing Centering, Corners & Surface..."
                                        scanState = ScanState.GRADING
                                        
                                        // Real Gemini Integration with retry logic
                                        try {
                                            val result = processWithGemini(
                                                frontBitmap!!, 
                                                backBitmap!!,
                                                onRetry = { attempt, delayMs -> 
                                                    gradingStatus = "Rate limit reached. Retrying in ${delayMs/1000}s... (Attempt $attempt)"
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
                                            resolvedCritique = if (fullMsg.contains("MissingFieldException")) {
                                                "AI Error: Connection failed (SDK Bug). Please try again in 30 seconds."
                                            } else if (fullMsg.contains("404")) {
                                                "AI Error: Model not found. Updating configuration..."
                                            } else if (fullMsg.contains("429") || fullMsg.contains("quota", ignoreCase = true)) {
                                                "AI Error: Daily Quota reached. Please try scanning again in a few minutes or tomorrow."
                                            } else {
                                                "AI Error: ${e.message}"
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
                        onNameChange = { 
                            resolvedName = it
                            // Try to update Dex number live if user is typing
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
                                    dateAdded = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
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
                
                // Rotation handling
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
                // Step indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))        
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (side == ScanSide.BACK) Color.White else Color(0x66FFFFFF))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (side == ScanSide.FRONT) "Scan Front Side" else "Scan Back Side",  
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Frame
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

        Text(
            "Gemini Card Critic",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            status,
            color = Color.Gray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

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

        Spacer(modifier = Modifier.height(24.dp))

        if (!isManual) {
            // Grade Highlight
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))  
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CONDITION GRADE", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(grade.toString(), color = Color(0xFF10B981), fontSize = 48.sp, fontWeight = FontWeight.Black)
                    Text("Near Mint (AI Evaluated)", color = Color.LightGray, fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Editable Fields
        OutlinedTextField(
            value = if (isManual) name else name.capitalize(),
            onValueChange = onNameChange,
            label = { Text("Pokémon Name", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFEF4444)
            ),
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
                focusedBorderColor = Color(0xFFEF4444)
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
                    focusedBorderColor = Color(0xFFEF4444)
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
                    trailingIcon = { IconButton(onClick = { expandedRarity = true }) { Icon(Icons.Default.ArrowDropDown, null, tint = Color.Gray) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFEF4444)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(expanded = expandedRarity, onDismissRequest = { expandedRarity = false }, containerColor = Color(0xFF0F172A)) {
                    listOf("Normal", "Rare", "Holofoil Rare", "Reverse Holo", "Ultra Rare", "Illustration Rare", "Special Illustration Rare", "Secret Rare", "Hyper Rare").forEach { r ->
                        DropdownMenuItem(text = { Text(r, color = Color.White) }, onClick = { onRarityChange(r); expandedRarity = false })
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row {
            OutlinedTextField(
                value = page,
                onValueChange = onPageChange,
                label = { Text("Page", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFEF4444)
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = slot,
                onValueChange = onSlotChange,
                label = { Text("Slot", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFEF4444)
                ),
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
                    focusedBorderColor = Color(0xFFEF4444)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(if (isManual) "Notes" else "Critic Notes", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = critique,
            onValueChange = onCritiqueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFEF4444)
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
    }
}
