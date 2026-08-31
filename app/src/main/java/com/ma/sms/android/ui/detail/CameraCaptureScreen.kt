package com.ma.sms.android.ui.detail

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val previewView = remember { PreviewView(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    // Flash au moment de la photo (ImageCapture.FLASH_MODE_ON), pas une torche allumee en
    // continu : comportement d'un appareil photo classique.
    var flashEnabled by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }

    // Objectif ultra grand-angle : cameras physique separee sur la plupart des telephones, non
    // accessible en dessous de 1x sur l'objectif principal (voir findUltraWideCameraInfo). Reste
    // a null (bouton masque) sur les appareils qui n'en ont pas, comportement inchangé pour eux.
    var ultraWideCameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var usingUltraWide by remember { mutableStateOf(false) }
    var ultraWideZoomLabel by remember { mutableStateOf("0.6x") }

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

    // Recupere le fournisseur de cameras une seule fois, puis detecte un eventuel objectif ultra
    // grand-angle distinct de l'objectif principal (voir findUltraWideCameraInfo).
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            val mainCameraInfo = CameraSelector.DEFAULT_BACK_CAMERA
                .filter(provider.availableCameraInfos)
                .firstOrNull()
            if (mainCameraInfo != null) {
                val ultraWide = findUltraWideCameraInfo(provider, mainCameraInfo)
                ultraWideCameraInfo = ultraWide
                if (ultraWide != null) {
                    ultraWideZoomLabel = computeZoomLabel(mainCameraInfo, ultraWide)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // (Re)lie la preview et la capture a l'objectif selectionne : execute au premier chargement
    // du fournisseur de cameras, puis a chaque bascule 1x <-> ultra grand-angle.
    LaunchedEffect(cameraProvider, usingUltraWide) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()
        imageCapture = capture

        val targetUltraWide = ultraWideCameraInfo
        val selector = if (usingUltraWide && targetUltraWide != null) {
            CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { it == targetUltraWide } }
                .build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            val boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            camera = boundCamera
            hasFlash = boundCamera.cameraInfo.hasFlashUnit()
        } catch (e: Exception) {
            Log.e("CameraCaptureScreen", "Echec liaison camera", e)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    // Pincer pour zoomer : ratio relatif applique au zoom courant, borne aux
                    // limites materielles du capteur (zoomState.min/maxZoomRatio) de l'objectif
                    // actuellement lie (principal ou ultra grand-angle).
                    detectTransformGestures { _, _, zoomChange, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val zoomState = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val newRatio = (zoomState.zoomRatio * zoomChange)
                            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                        cam.cameraControl.setZoomRatio(newRatio)
                    }
                },
            factory = { previewView }
        )

        // Mini-carte "vue de dessus" (coin bas-droit) indiquant le cote/zone a photographier : ne
        // recouvre pas l'apercu camera, contrairement a une silhouette en plein ecran. N'affiche
        // rien pour les photos supplementaires (angle non predefini).
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

        // Bascule 1x <-> ultra grand-angle : visible seulement si l'appareil expose un objectif
        // distinct nettement plus large que l'objectif principal (voir findUltraWideCameraInfo).
        if (ultraWideCameraInfo != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 116.dp)
                    .clickable { usingUltraWide = !usingUltraWide }
            ) {
                Text(
                    text = if (usingUltraWide) "1x" else ultraWideZoomLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
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

// Cherche, parmi les cameras arriere, un objectif distinct du principal dont le champ de vision
// horizontal est nettement plus large (seuil de securite pour ecarter un capteur macro/profondeur
// qui ne serait pas plus large). Retourne null si l'appareil n'a pas d'ultra grand-angle exploitable.
private const val ULTRA_WIDE_FOV_THRESHOLD_DEGREES = 15.0

private fun findUltraWideCameraInfo(provider: ProcessCameraProvider, mainCameraInfo: CameraInfo): CameraInfo? {
    val backCameras = try {
        CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
            .filter(provider.availableCameraInfos)
    } catch (e: Exception) {
        return null
    }
    val mainFov = horizontalFovDegrees(mainCameraInfo) ?: return null

    var widest: CameraInfo? = null
    var widestFov = mainFov
    for (info in backCameras) {
        if (info == mainCameraInfo) continue
        val fov = horizontalFovDegrees(info) ?: continue
        if (fov > widestFov + ULTRA_WIDE_FOV_THRESHOLD_DEGREES) {
            widestFov = fov
            widest = info
        }
    }
    return widest
}

// Champ de vision horizontal approximatif (degres), calcule depuis la focale et la largeur
// physique du capteur : 2 * atan(largeurCapteur / (2 * focale)).
private fun horizontalFovDegrees(cameraInfo: CameraInfo): Double? {
    return try {
        val characteristics = Camera2CameraInfo.from(cameraInfo)
        val focalLength = characteristics.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
        val sensorWidth = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.width
        if (focalLength == null || focalLength <= 0f || sensorWidth == null || sensorWidth <= 0f) {
            return null
        }
        2.0 * Math.toDegrees(Math.atan((sensorWidth / (2.0 * focalLength)).toDouble()))
    } catch (e: Exception) {
        null
    }
}

// Facteur de zoom approximatif affiche sur le bouton bascule ("0.6x"...), base sur le ratio des
// focales des deux objectifs (approximation courante, memes limites qu'un appareil photo grand public).
private fun computeZoomLabel(mainCameraInfo: CameraInfo, ultraWideCameraInfo: CameraInfo): String {
    val mainFocal = Camera2CameraInfo.from(mainCameraInfo)
        .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val wideFocal = Camera2CameraInfo.from(ultraWideCameraInfo)
        .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    if (mainFocal == null || wideFocal == null || mainFocal <= 0f) {
        return "0.6x"
    }
    return String.format(Locale.US, "%.1fx", wideFocal / mainFocal)
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
