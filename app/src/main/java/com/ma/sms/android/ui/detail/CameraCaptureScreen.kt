package com.ma.sms.android.ui.detail

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

private const val TAG = "CameraCaptureScreen"

/**
 * Ecran de capture photo avec apercu camera integre (CameraX), pour eviter
 * l'aller-retour vers l'application Camera externe entre chaque photo.
 */
@OptIn(ExperimentalCamera2Interop::class)
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

    // Rotation cible de la capture, suivie en continu independamment de l'orientation de l'UI
    // (qui reste fixe en portrait) : sans cela, une photo prise telephone a l'horizontale est
    // enregistree avec la meme orientation qu'une photo verticale, et s'affiche mal ensuite.
    var targetRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                targetRotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    LaunchedEffect(targetRotation) {
        imageCapture?.targetRotation = targetRotation
    }

    // Objectif ultra grand-angle, non accessible en dessous de 1x sur l'objectif principal (voir
    // findUltraWideCamera). Deux cas de figure existent selon le materiel : soit une CameraInfo
    // arriere totalement distincte, soit (le plus courant sur les telephones recents) un
    // sous-capteur physique cache a l'interieur d'une seule camera "logique" multi-objectifs,
    // uniquement accessible via Camera2Interop.setPhysicalCameraId. Reste a null (bouton masque)
    // sur les appareils qui n'ont vraiment aucun ultra grand-angle exploitable.
    var ultraWideCamera by remember { mutableStateOf<UltraWideCamera?>(null) }
    var usingUltraWide by remember { mutableStateOf(false) }
    // Facteur approximatif (focale principale / focale grand-angle, ex. 0.6) utilise a la fois
    // pour le libelle du bouton bascule et pour afficher un zoom "effectif" continu avec
    // l'objectif principal quand on pince sur l'ultra grand-angle (voir pinchZoomRatio).
    var ultraWideZoomFactor by remember { mutableStateOf(0.6f) }

    // Valeur de zoom affichee pendant le pincement, comme sur un appareil photo de telephone :
    // remise a null (masquee) apres un court delai d'inactivite, voir le LaunchedEffect associe.
    var pinchZoomRatio by remember { mutableStateOf<Float?>(null) }

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

    // Masque l'indicateur de zoom apres un court delai d'inactivite : redemarre a chaque
    // changement de pinchZoomRatio (donc a chaque mouvement de pincement), comme sur un appareil
    // photo de telephone.
    LaunchedEffect(pinchZoomRatio) {
        if (pinchZoomRatio != null) {
            delay(1200)
            pinchZoomRatio = null
        }
    }

    // Recupere le fournisseur de cameras une seule fois, puis detecte un eventuel objectif ultra
    // grand-angle (voir findUltraWideCamera).
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            val mainCameraInfo = CameraSelector.DEFAULT_BACK_CAMERA
                .filter(provider.availableCameraInfos)
                .firstOrNull()
            if (mainCameraInfo != null) {
                val found = findUltraWideCamera(provider, mainCameraInfo)
                ultraWideCamera = found
                if (found != null) {
                    ultraWideZoomFactor = found.zoomFactor
                }
                Log.i(TAG, "Detection ultra grand-angle : ${if (found != null) "trouve (physicalCameraId=${found.physicalCameraId}, facteur=${found.zoomFactor})" else "aucun"}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // (Re)lie la preview et la capture a l'objectif selectionne : execute au premier chargement
    // du fournisseur de cameras, puis a chaque bascule 1x <-> ultra grand-angle.
    LaunchedEffect(cameraProvider, usingUltraWide) {
        val provider = cameraProvider ?: return@LaunchedEffect
        // Meme strategie de ratio d'aspect pour Preview et ImageCapture : sans ca, CameraX choisit
        // independamment une resolution proche du PreviewView (ecran, souvent tres "haut", ex.
        // ~19.5:9) pour l'apercu et une resolution proche de 4:3 pour la capture. La PreviewView,
        // en mode FILL_CENTER (recadrage pour remplir l'ecran), rogne alors fortement l'apercu
        // affiche pour compenser cet ecart de ratio, ce qui donne une impression de zoom a l'usage
        // meme si le zoom reel est a 1.0x -- la photo enregistree, elle, garde le cadrage complet.
        // En forcant le meme ratio (16:9) des les deux flux, l'apercu montre fidelement ce qui sera
        // capture.
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .build()
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
        val captureBuilder = ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .setTargetRotation(targetRotation)

        val target = ultraWideCamera
        val selector = if (usingUltraWide && target != null) {
            val physicalId = target.physicalCameraId
            if (physicalId != null) {
                // Meme camera "logique" que l'objectif principal, mais on force les flux preview
                // et capture a venir du sous-capteur ultra grand-angle (voir UltraWideCamera).
                Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalId)
                Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physicalId)
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                val info = target.cameraInfo
                CameraSelector.Builder()
                    .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == Camera2CameraInfo.from(info).cameraId } }
                    .build()
            }
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = captureBuilder.build()
        imageCapture = capture

        try {
            provider.unbindAll()
            val boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            camera = boundCamera
            hasFlash = boundCamera.cameraInfo.hasFlashUnit()
            Log.i(TAG, "Camera liee : usingUltraWide=$usingUltraWide, physicalCameraId=${target?.physicalCameraId}")
        } catch (e: Exception) {
            Log.e(TAG, "Echec liaison camera (usingUltraWide=$usingUltraWide, physicalCameraId=${target?.physicalCameraId})", e)
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
                        // Valeur affichee "effective", continue avec l'objectif principal : sur
                        // l'ultra grand-angle, son propre zoomRatio 1x correspond a ultraWideZoomFactor
                        // (ex. 0.6x) sur cette echelle, comme sur un appareil photo de telephone.
                        val effectiveRatio = if (usingUltraWide) newRatio * ultraWideZoomFactor else newRatio
                        pinchZoomRatio = effectiveRatio
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
        // nettement plus large que l'objectif principal (voir findUltraWideCamera).
        if (ultraWideCamera != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 116.dp)
                    .clickable { usingUltraWide = !usingUltraWide }
            ) {
                Text(
                    text = if (usingUltraWide) "1x" else String.format(Locale.US, "%.1fx", ultraWideZoomFactor),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Valeur de zoom affichee pendant le pincement (comme sur un appareil photo de telephone) :
        // apparait a chaque mouvement, disparait en fondu apres une courte inactivite (voir le
        // LaunchedEffect(pinchZoomRatio) qui remet pinchZoomRatio a null).
        AnimatedVisibility(
            visible = pinchZoomRatio != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fx", pinchZoomRatio ?: 1f),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
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

// Ultra grand-angle detecte : soit une CameraInfo arriere distincte (materiel a objectifs
// separes), soit un sous-capteur physique cache dans la camera "logique" principale (materiel a
// camera logique multi-objectifs, le cas le plus courant sur les telephones recents). Dans ce
// second cas, cameraInfo reste celui de l'objectif principal : c'est physicalCameraId qui indique
// a Camera2Interop.setPhysicalCameraId() quel sous-capteur streamer.
private data class UltraWideCamera(
    val cameraInfo: CameraInfo,
    val physicalCameraId: String?,
    val zoomFactor: Float
)

// Seuil de securite (degres) pour qu'un objectif soit considere comme "nettement plus large" que
// le principal, et ecarter les fausses detections (capteur macro/profondeur, pas plus large).
private const val ULTRA_WIDE_FOV_THRESHOLD_DEGREES = 15.0

private fun findUltraWideCamera(provider: ProcessCameraProvider, mainCameraInfo: CameraInfo): UltraWideCamera? {
    findSeparateUltraWideCameraInfo(provider, mainCameraInfo)?.let { separate ->
        val factor = computeZoomFactor(focalLength(mainCameraInfo), focalLength(separate))
        return UltraWideCamera(separate, null, factor)
    }
    return findUltraWidePhysicalCamera(mainCameraInfo)
}

// Cas 1 (materiel plus ancien/simple) : un objectif ultra grand-angle expose comme CameraInfo
// arriere totalement independante de l'objectif principal.
private fun findSeparateUltraWideCameraInfo(provider: ProcessCameraProvider, mainCameraInfo: CameraInfo): CameraInfo? {
    val mainId = Camera2CameraInfo.from(mainCameraInfo).cameraId
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
        if (Camera2CameraInfo.from(info).cameraId == mainId) continue
        val fov = horizontalFovDegrees(info) ?: continue
        if (fov > widestFov + ULTRA_WIDE_FOV_THRESHOLD_DEGREES) {
            widestFov = fov
            widest = info
        }
    }
    return widest
}

// Cas 2 (le plus courant) : l'objectif principal est en realite une camera "logique" qui fusionne
// plusieurs sous-capteurs physiques (grand-angle + ultra grand-angle + parfois teleobjectif),
// invisibles individuellement dans ProcessCameraProvider.availableCameraInfos. On les retrouve via
// Camera2CameraInfo.getCameraCharacteristicsMap() (id physique -> CameraCharacteristics propres a
// ce capteur), et on y applique le meme calcul de champ de vision que pour le cas 1.
private data class PhysicalCandidate(val id: String, val fov: Double, val zoomFactor: Float)

private fun findUltraWidePhysicalCamera(mainCameraInfo: CameraInfo): UltraWideCamera? {
    return try {
        val camera2Info = Camera2CameraInfo.from(mainCameraInfo)
        val characteristicsMap = camera2Info.cameraCharacteristicsMap
        if (characteristicsMap.size <= 1) {
            // Pas de camera logique multi-objectifs detectee pour ce capteur.
            return null
        }
        val mainId = camera2Info.cameraId
        val mainFov = characteristicsMap[mainId]?.let { fovFromCharacteristics(it) }
            ?: horizontalFovDegrees(mainCameraInfo)
            ?: return null
        val mainFocal = characteristicsMap[mainId]?.let { focalLengthFromCharacteristics(it) }
            ?: focalLength(mainCameraInfo)

        var best: PhysicalCandidate? = null
        for ((id, characteristics) in characteristicsMap) {
            if (id == mainId) continue
            val fov = fovFromCharacteristics(characteristics) ?: continue
            if (fov > mainFov + ULTRA_WIDE_FOV_THRESHOLD_DEGREES && (best == null || fov > best!!.fov)) {
                val wideFocal = focalLengthFromCharacteristics(characteristics)
                val factor = computeZoomFactor(mainFocal, wideFocal)
                best = PhysicalCandidate(id, fov, factor)
            }
        }
        best?.let { UltraWideCamera(mainCameraInfo, it.id, it.zoomFactor) }
    } catch (e: Exception) {
        Log.w(TAG, "Echec detection ultra grand-angle (camera logique)", e)
        null
    }
}

// Champ de vision horizontal approximatif (degres) d'une CameraInfo CameraX, calcule depuis la
// focale et la largeur physique du capteur : 2 * atan(largeurCapteur / (2 * focale)).
private fun horizontalFovDegrees(cameraInfo: CameraInfo): Double? {
    return try {
        fovFromCharacteristics(Camera2CameraInfo.extractCameraCharacteristics(cameraInfo))
    } catch (e: Exception) {
        null
    }
}

private fun focalLength(cameraInfo: CameraInfo): Float? {
    return try {
        Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
    } catch (e: Exception) {
        null
    }
}

private fun fovFromCharacteristics(characteristics: CameraCharacteristics): Double? {
    val focalLength = focalLengthFromCharacteristics(characteristics) ?: return null
    val sensorWidth = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
    if (focalLength <= 0f || sensorWidth == null || sensorWidth <= 0f) return null
    return 2.0 * Math.toDegrees(Math.atan((sensorWidth / (2.0 * focalLength)).toDouble()))
}

private fun focalLengthFromCharacteristics(characteristics: CameraCharacteristics): Float? {
    return characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
}

// Facteur de zoom approximatif (ex. 0.6) de l'ultra grand-angle par rapport a l'objectif
// principal, base sur le ratio des focales (approximation courante, memes limites qu'un appareil
// photo grand public). Sert au libelle du bouton bascule et a l'affichage du zoom "effectif"
// pendant le pincement. Valeur par defaut si les focales sont indisponibles.
private fun computeZoomFactor(mainFocal: Float?, wideFocal: Float?): Float {
    if (mainFocal != null && wideFocal != null && mainFocal > 0f) {
        return wideFocal / mainFocal
    }
    return 0.6f
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
