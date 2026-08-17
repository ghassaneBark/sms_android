package com.ma.sms.android.ui.detail

import android.util.Log
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ma.sms.android.SmsApplication
import com.ma.sms.android.util.JwtUtils
import com.ma.sms.android.util.LocationHelper
import com.ma.sms.android.util.PhotoLocation
import com.ma.sms.android.util.PhotoWatermark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/**
 * Ecran de capture photo avec apercu camera integre (CameraX), pour eviter
 * l'aller-retour vers l'application Camera externe entre chaque photo.
 */
@Composable
fun CameraCaptureScreen(
    title: String,
    subtitle: String?,
    onCapture: (File) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    // Flash au moment de la photo (ImageCapture.FLASH_MODE_ON), pas une torche allumee en
    // continu : comportement d'un appareil photo classique.
    var flashEnabled by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }

    // Nom de l'agent terrain (claims du token Keycloak) et position courante, recuperes des
    // l'ouverture de l'ecran pour etre prets au moment ou l'utilisateur declenche la capture.
    val agentName = remember {
        val app = context.applicationContext as SmsApplication
        JwtUtils.extractDisplayName(app.tokenManager.accessToken) ?: "Agent terrain"
    }
    var currentLocation by remember { mutableStateOf<PhotoLocation?>(null) }
    LaunchedEffect(Unit) {
        currentLocation = LocationHelper.getCurrentLocation(context)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    // Pincer pour zoomer : ratio relatif applique au zoom courant, borne aux
                    // limites materielles du capteur (zoomState.min/maxZoomRatio).
                    detectTransformGestures { _, _, zoomChange, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val zoomState = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val newRatio = (zoomState.zoomRatio * zoomChange)
                            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                        cam.cameraControl.setZoomRatio(newRatio)
                    }
                },
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                        .build()
                    imageCapture = capture

                    try {
                        cameraProvider.unbindAll()
                        val boundCamera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                        camera = boundCamera
                        hasFlash = boundCamera.cameraInfo.hasFlashUnit()
                    } catch (e: Exception) {
                        Log.e("CameraCaptureScreen", "Echec liaison camera", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // Mini-carte "vue de dessus" (coin bas-droit) indiquant le cote/zone a photographier : ne
        // recouvre pas l'apercu camera, contrairement a une silhouette en plein ecran. N'affiche
        // rien pour "Tableau de bord" (aucune zone dediee pertinente) ni pour les photos
        // supplementaires.
        VehicleAngleGuideOverlay(
            angleLabel = title,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 120.dp)
        )

        // Bandeau haut : titre + fermeture
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasFlash) {
                    IconButton(onClick = {
                        val capture = imageCapture ?: return@IconButton
                        val next = !flashEnabled
                        capture.flashMode = if (next) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                        flashEnabled = next
                    }) {
                        Icon(
                            if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (flashEnabled) "Désactiver le flash" else "Activer le flash",
                            tint = Color.White
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
                }
            }
        }

        // Bouton de capture
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    if (isCapturing) return@FloatingActionButton
                    val capture = imageCapture ?: return@FloatingActionButton
                    isCapturing = true
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
                    val file = File(context.cacheDir, "photo_${timestamp}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                    capture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val lines = buildWatermarkLines(agentName, currentLocation)
                                coroutineScope.launch {
                                    withContext(Dispatchers.Default) {
                                        PhotoWatermark.apply(file, lines)
                                    }
                                    isCapturing = false
                                    onCapture(file)
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                isCapturing = false
                                Log.e("CameraCaptureScreen", "Echec capture photo", exception)
                                Toast.makeText(context, "Erreur lors de la prise de photo", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                },
                containerColor = Color.White,
                modifier = Modifier.size(72.dp)
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Default.Camera, contentDescription = "Prendre la photo", tint = Color.Black, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

// Lignes incrustees en haut a gauche de chaque photo : date/heure, agent terrain, position GPS
// et adresse (voir PhotoWatermark). La position reste "best effort" : si le GPS n'a pas repondu
// a temps ou que la permission est refusee, on l'indique plutot que de bloquer la capture.
private fun buildWatermarkLines(agentName: String, location: PhotoLocation?): List<String> {
    val dateTime = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
    val lines = mutableListOf(
        dateTime,
        "Par: $agentName | SAGEXPERT"
    )
    if (location != null) {
        lines.add("Position: ${String.format(Locale.US, "%.6f", location.latitude)}, ${String.format(Locale.US, "%.6f", location.longitude)}")
        location.address?.let { lines.add("Adresse: $it") }
    } else {
        lines.add("Position: indisponible")
    }
    return lines
}
