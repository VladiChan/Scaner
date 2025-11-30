package com.example.scaner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CameraApp()
            }
        }
    }
}

private const val GEMINI_API_KEY = "AIzaSyCep-XO6KQoKdxlPGZeYWZYcUpLAd817YY"

private val geminiModel by lazy {
    GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = GEMINI_API_KEY
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lastCapturedUri by remember { mutableStateOf<Uri?>(null) }
    var recognizedText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (!cameraPermissionState.status.isGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Требуется разрешение на камеру", color = Color.White)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    imageCapture = ImageCapture.Builder()
                        .setTargetRotation(previewView.display.rotation)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = lastCapturedUri != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            lastCapturedUri?.let { uri ->
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                    )

                    Image(
                        painter = rememberAsyncImagePainter(uri),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(32.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(4.dp, Color(0xFF00FF00), RoundedCornerShape(24.dp))
                            .align(Alignment.Center)
                    )

                    IconButton(
                        onClick = {
                            lastCapturedUri = null
                            recognizedText = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(56.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Text("×", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    }

                    if (recognizedText == null) {
                        Button(
                            onClick = {
                                isLoading = true
                                scope.launch {
                                    val text = recognizeTextFromUri(uri, context)
                                    recognizedText = text
                                    isLoading = false
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 140.dp)
                                .fillMaxWidth(0.85f)
                                .height(68.dp),
                            shape = RoundedCornerShape(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(16.dp))
                                Text("Распознаём...", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            } else {
                                Text("Распознать текст", color = Color.Black, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    recognizedText?.let { text ->
                        Card(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(top = 100.dp, start = 20.dp, end = 20.dp, bottom = 120.dp)
                                .fillMaxWidth()
                                .fillMaxHeight(0.65f),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.98f)),
                            elevation = CardDefaults.cardElevation(16.dp)
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                Text(
                                    text = "Распознанный текст",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(20.dp)
                                )
                                SelectionContainer {
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.DarkGray,
                                        lineHeight = 32.sp,
                                        modifier = Modifier
                                            .weight(1f)
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 20.dp)
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                val clip = ClipData.newPlainText("OCR", text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Скопировано в буфер!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 40.dp)
                                .fillMaxWidth(0.9f)
                                .height(68.dp),
                            shape = RoundedCornerShape(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                        ) {
                            Text(
                                "Скопировать весь текст",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (lastCapturedUri == null) {
            IconButton(
                onClick = {
                    takePhoto(imageCapture, context) { uri ->
                        lastCapturedUri = uri
                        recognizedText = null
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .size(80.dp)
                    .background(Color.White, CircleShape)
                    .border(6.dp, Color.White, CircleShape)
                    .zIndex(10f)
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(Color.White, CircleShape)
                        .border(4.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
                )
            }
        }
    }
}

private suspend fun recognizeTextFromUri(uri: Uri, context: android.content.Context): String {
    return withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            val inputContent = content {
                image(bitmap)
                text("Извлеки ВЕСЬ текст с изображения максимально точно. Не пропускай ничего. Верни только чистый текст без пояснений.")
            }

            val response = geminiModel.generateContent(inputContent)
            response.text ?: "Текст не найден"
        } catch (e: Exception) {
            "Ошибка: ${e.localizedMessage}"
        }
    }
}

private fun takePhoto(
    imageCapture: ImageCapture?,
    context: android.content.Context,
    onPhotoSaved: (Uri) -> Unit
) {
    if (imageCapture == null) return

    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Scaner")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                output.savedUri?.let { onPhotoSaved(it) }
            }
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    )
}