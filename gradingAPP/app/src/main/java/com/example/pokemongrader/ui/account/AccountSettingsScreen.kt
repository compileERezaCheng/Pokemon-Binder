package com.example.pokemongrader.ui.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pokemongrader.data.DataRepository
import com.example.pokemongrader.ui.main.ProfileImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    repository: DataRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profilePicSource by repository.profilePicSource.collectAsStateWithLifecycle()
    val profileFeaturedDex by repository.profileFeaturedDex.collectAsStateWithLifecycle()
    val profileImageUrl by repository.profileImageUrl.collectAsStateWithLifecycle()
    val profileImageBase64 by repository.profileImageBase64.collectAsStateWithLifecycle()
    val username by repository.username.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Editable State
    var editDex by remember { mutableStateOf(profileFeaturedDex.toString()) }
    var editUrl by remember { mutableStateOf(profileImageUrl) }
    var editBase64 by remember { mutableStateOf(profileImageBase64) }
    var editSource by remember { mutableStateOf(profilePicSource) }

    // Cropping State
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var isCropping by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            isCropping = true
        }
    }

    val hasChanges = editDex != profileFeaturedDex.toString() || 
                     editUrl != profileImageUrl || 
                     editBase64 != profileImageBase64 ||
                     editSource != profilePicSource

    if (isCropping && selectedUri != null) {
        ImageCropper(
            uri = selectedUri!!,
            onCropCancelled = { isCropping = false },
            onCropSuccess = { base64 ->
                editBase64 = base64
                editSource = "base64"
                editUrl = "" // Clear URL if using local image
                isCropping = false
            }
        )
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Edit Profile Picture", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        if (hasChanges) {
                            TextButton(onClick = {
                                coroutineScope.launch {
                                    repository.updateProfile(
                                        username,
                                        editSource,
                                        editDex.toIntOrNull() ?: 25,
                                        editUrl,
                                        editBase64
                                    )
                                    onNavigateBack()
                                }
                            }) {
                                Text("SAVE", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF020617))
                )
            },
            containerColor = Color(0xFF020617)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Preview
                ProfileImage(
                    source = editSource,
                    dex = editDex.toIntOrNull() ?: 25,
                    url = editUrl,
                    base64Str = editBase64,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color(0xFF3B82F6), CircleShape)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Gallery Button
                Button(
                    onClick = { pickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("CHOOSE FROM GALLERY", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(24.dp))

                HorizontalDivider(color = Color.DarkGray, thickness = 1.dp)

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "Alternative Options",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = editDex,
                    onValueChange = { 
                        editDex = it
                        editSource = "pokemon"
                        editUrl = ""
                        editBase64 = ""
                    },
                    label = { Text("Pokémon Dex Number", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFEF4444)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = editUrl,
                    onValueChange = { 
                        editUrl = it
                        if (it.isNotEmpty()) {
                            editSource = "url"
                            editBase64 = ""
                        } else {
                            editSource = "pokemon"
                        }
                    },
                    label = { Text("Custom Image URL", color = Color.Gray) },
                    placeholder = { Text("https://...", color = Color.DarkGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFEF4444)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ImageCropper(
    uri: Uri,
    onCropCancelled: () -> Unit,
    onCropSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Transformation state
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    
    // View dimensions
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val raw = BitmapFactory.decodeStream(inputStream)
                
                // Fix orientation issues
                val orientationStream: InputStream? = context.contentResolver.openInputStream(uri)
                val exif = androidx.exifinterface.media.ExifInterface(orientationStream!!)
                val rotation = when (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
                
                if (rotation != 0) {
                    val matrix = Matrix()
                    matrix.postRotate(rotation.toFloat())
                    bitmap = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                } else {
                    bitmap = raw
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp).safeDrawingPadding()) {
                IconButton(onClick = onCropCancelled, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                }
                Text("Crop Photo", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                IconButton(
                    onClick = {
                        if (bitmap != null && viewSize != IntSize.Zero) {
                            coroutineScope.launch(Dispatchers.IO) {
                                val cropped = performCrop(bitmap!!, scale, offset, viewSize)
                                if (cropped != null) {
                                    val base64 = bitmapToBase64(cropped)
                                    withContext(Dispatchers.Main) { onCropSuccess(base64) }
                                }
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Done", tint = Color(0xFFEF4444))
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
                .onGloballyPositioned { viewSize = it.size },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale *= zoom
                                offset += pan
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit
                )

                // Square Cropping Mask
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .background(Color.Transparent)
                )
                
                Text(
                    "Pinch to zoom, drag to move",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                )
            } else {
                CircularProgressIndicator(color = Color(0xFFEF4444))
            }
        }
    }
}

private fun performCrop(
    original: Bitmap, 
    scale: Float, 
    offset: androidx.compose.ui.geometry.Offset,
    viewSize: IntSize
): Bitmap? {
    try {
        val targetSize = 400
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // 1. Calculate how the bitmap was scaled to fit the view (ContentScale.Fit)
        val viewAspectRatio = viewSize.width.toFloat() / viewSize.height
        val bitmapAspectRatio = original.width.toFloat() / original.height
        
        val fitScale = if (bitmapAspectRatio > viewAspectRatio) {
            viewSize.width.toFloat() / original.width
        } else {
            viewSize.height.toFloat() / original.height
        }
        
        val displayedWidth = original.width * fitScale
        val displayedHeight = original.height * fitScale
        
        // 2. The total scale is fitScale * userScale
        val totalScale = fitScale * scale
        
        // 3. Center of the view in bitmap coordinates
        // The mask is 300dp wide in view. We want to sample the area under the mask.
        // We calculate the inverse transformation to find what part of the bitmap is in the mask.
        val matrix = Matrix()
        
        // Matrix transformations in reverse order of UI
        // Move to center of view
        matrix.postTranslate(-original.width / 2f, -original.height / 2f)
        // Apply user scale
        matrix.postScale(scale, scale)
        // Apply user offset (corrected for scale)
        matrix.postTranslate(offset.x / fitScale, offset.y / fitScale)
        // Move back to view center
        matrix.postTranslate(original.width / 2f, original.height / 2f)
        
        // Better approach: Just use a Matrix to draw the bitmap onto the result canvas
        val drawMatrix = Matrix()
        // Translate to match UI center
        drawMatrix.postTranslate(-original.width / 2f, -original.height / 2f)
        // Scale to match UI
        val scaleFactor = (targetSize / (300f * (viewSize.width / 1080f))) // Rough DP to PX mapping
        // Let's use a simpler coordinate mapping logic:
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        
        // Actual logic: Map the 300dp UI square back to bitmap space
        // We know the UI center is (viewSize.width/2, viewSize.height/2)
        // The user offset and scale move the image relative to that center.
        
        // Simplest robust method: Use a Matrix that mimics the graphicsLayer
        val finalMatrix = Matrix()
        // Center the bitmap
        finalMatrix.postTranslate(-original.width / 2f, -original.height / 2f)
        // User Scale
        finalMatrix.postScale(scale, scale)
        // User Offset (in view pixels, needs to be normalized to bitmap scale)
        finalMatrix.postTranslate(offset.x / fitScale, offset.y / fitScale)
        // Re-scale to fit the output 400x400 based on the 300dp UI mask
        // mask in px = 300 * density. Let's assume average density or just use proportions.
        val maskSizeInViewPixels = 300f * (viewSize.width / 392f) // 392 is typical DP width
        val outputScale = targetSize / (maskSizeInViewPixels / fitScale)
        finalMatrix.postScale(outputScale, outputScale)
        // Move to center of result bitmap
        finalMatrix.postTranslate(targetSize / 2f, targetSize / 2f)
        
        canvas.drawBitmap(original, finalMatrix, paint)
        
        return result
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

private fun bitmapToBase64(bitmap: Bitmap): String {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
    val bytes = outputStream.toByteArray()
    return Base64.encodeToString(bytes, Base64.DEFAULT)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    repository: DataRepository,
    onNavigateToProfileEdit: () -> Unit,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val username by repository.username.collectAsStateWithLifecycle()
    val cards by repository.cards.collectAsStateWithLifecycle()
    val profilePicSource by repository.profilePicSource.collectAsStateWithLifecycle()
    val profileFeaturedDex by repository.profileFeaturedDex.collectAsStateWithLifecycle()
    val profileImageUrl by repository.profileImageUrl.collectAsStateWithLifecycle()
    val profileImageBase64 by repository.profileImageBase64.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // Editable State
    var editUsername by remember { mutableStateOf(username) }
    val hasChanges = editUsername != username

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Account Settings", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (hasChanges) {
                        TextButton(onClick = {
                            coroutineScope.launch {
                                repository.updateProfile(
                                    editUsername,
                                    profilePicSource,
                                    profileFeaturedDex,
                                    profileImageUrl,
                                    profileImageBase64
                                )
                            }
                        }) {
                            Text("SAVE", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF020617))
            )
        },
        containerColor = Color(0xFF020617)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                ProfileImage(
                    source = profilePicSource,
                    dex = profileFeaturedDex,
                    url = profileImageUrl,
                    base64Str = profileImageBase64,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color(0xFF3B82F6), CircleShape)
                        .clickable { onNavigateToProfileEdit() }
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                        .border(2.dp, Color(0xFF020617), CircleShape)
                        .clickable { onNavigateToProfileEdit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Profile Pic", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Edit Username
            OutlinedTextField(
                value = editUsername,
                onValueChange = { editUsername = it },
                label = { Text("Trainer Name", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFEF4444)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Stats Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F172A))
                    .padding(16.dp)
            ) {
                Text("Collection Stats", color = Color(0xFF94A3B8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Cards", color = Color.LightGray)
                    Text(cards.size.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                val uniqueSpecies = cards.filter { it.dexNumber > 0 }.map { it.dexNumber }.distinct().size
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Unique Species", color = Color.LightGray)
                    Text(uniqueSpecies.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = { showLogoutConfirm = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("LOGOUT", color = Color.Red, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Logout") },
            text = { Text("Are you sure you want to logout? You will need to sign in again to sync your collection.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("LOGOUT")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("CANCEL")
                }
            },
            containerColor = Color(0xFF0F172A),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}
