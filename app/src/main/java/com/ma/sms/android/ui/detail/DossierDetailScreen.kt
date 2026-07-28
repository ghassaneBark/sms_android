package com.ma.sms.android.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.repository.DossierRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// --- Angles véhicule ---
data class VehicleAngle(val label: String, val required: Boolean)

val VEHICLE_ANGLES = listOf(
    VehicleAngle("Face avant",      required = true),
    VehicleAngle("Avant droit",     required = true),
    VehicleAngle("Avant gauche",    required = true),
    VehicleAngle("Latérale droite", required = true),
    VehicleAngle("Latérale gauche", required = true),
    VehicleAngle("Face arrière",    required = true),
    VehicleAngle("Arrière droit",   required = true),
    VehicleAngle("Arrière gauche",  required = true),
    VehicleAngle("Tableau de bord", required = true),
    VehicleAngle("N°CHAS",         required = true),
    VehicleAngle("Intérieur",       required = true)
)

const val EXTRA_VEHICLE_PHOTO_PREFIX = "Photos vehicules - Photo supplementaire"

// Type de document angle selon l'état
fun angleDocTypeForState(angle: VehicleAngle, etat: String?): String = when (etat) {
    "ATTENTE_EXPERTISE_SR"        -> "PHOTOS EN COURS DE REPARATION - ${angle.label}"
    "ATTENTE_PHOTO_FIN_REPARATION" -> "PHOTO APRES REPARATION - ${angle.label}"
    else                           -> "Photos vehicules - ${angle.label}"
}

// Préfixe de fichier pour tracking selon l'état
fun angleFileKeyForState(angle: VehicleAngle, etat: String?): String {
    val prefix = when (etat) {
        "ATTENTE_EXPERTISE_SR"        -> "en-cours"
        "ATTENTE_PHOTO_FIN_REPARATION" -> "apres-reparation"
        else                           -> "vehicule"
    }
    val slug = angle.label.lowercase()
        .replace(" ", "-")
        .replace("é", "e").replace("è", "e").replace("ê", "e")
        .replace("à", "a").replace("â", "a")
    return "$prefix-$slug"
}

fun isAngleUploadedForState(angle: VehicleAngle, etat: String?, documents: List<DocumentSinistre>): Boolean =
    documents.any { doc ->
        (doc.originalFileName ?: doc.fileName ?: "").startsWith(angleFileKeyForState(angle, etat))
    }

fun allRequiredAnglesDoneForState(etat: String?, documents: List<DocumentSinistre>): Boolean =
    VEHICLE_ANGLES.filter { it.required }.all { isAngleUploadedForState(it, etat, documents) }

// Garder les anciennes fonctions pour compatibilité CarDiagramCard
fun angleDocType(angle: VehicleAngle) = angleDocTypeForState(angle, null)
fun isAngleUploaded(angle: VehicleAngle, documents: List<DocumentSinistre>) =
    isAngleUploadedForState(angle, null, documents)
fun allRequiredAnglesDone(documents: List<DocumentSinistre>) =
    allRequiredAnglesDoneForState(null, documents)

private val OTHER_DOC_TYPES = listOf(
    "Carte grise",
    "Permis de conduite",
    "CIN",
    "Attestation assurance",
    "Garantie"
)

private val JUSTIFICATIF_SUBTYPES = listOf(
    "Constat amiable",
    "Déclaration sur l'honneur",
    "Déclaration des autorités de sécurité - PV",
    "Déclaration des autorités de sécurité - Récépissé"
)

private val FACTURE_TYPES = listOf("FACTURE PC ORIGINAL", "FACTURE PC RECUPERATION")

private fun extraDocTypesForEtat(etat: String?): List<String> = when (etat) {
    "ATTENTE_EXPERTISE_SR" -> listOf("PHOTOS EN COURS DE REPARATION") + OTHER_DOC_TYPES
    "ATTENTE_FIN_REPARATION" -> FACTURE_TYPES + OTHER_DOC_TYPES
    "ATTENTE_PHOTO_FIN_REPARATION" -> listOf("PHOTO APRES REPARATION") + FACTURE_TYPES + OTHER_DOC_TYPES
    else -> OTHER_DOC_TYPES
}

// Libelle court affiche sur l'ecran de capture pour un docType donne
private fun cameraDisplayLabel(docType: String, etat: String?): String {
    VEHICLE_ANGLES.forEach { angle ->
        if (angleDocTypeForState(angle, etat) == docType) return angle.label
    }
    if (docType.startsWith(EXTRA_VEHICLE_PHOTO_PREFIX)) return "Photo supplémentaire véhicule"
    return docType
}

// --- Écran principal ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DossierDetailScreen(
    dossierId: Long,
    repository: DossierRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val vm: DossierDetailViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DossierDetailViewModel(dossierId, repository) as T
        }
    })
    val state by vm.uiState.collectAsState()
    var showFinMissionDialog by remember { mutableStateOf(false) }
    var showContinueExtraPhotoDialog by remember { mutableStateOf(false) }
    var showReassignDialog by remember { mutableStateOf(false) }

    // File d'attente de captures pour l'ecran camera integre (CameraX) :
    // permet d'enchainer plusieurs photos sans quitter l'ecran de l'appli.
    var showCameraScreen by remember { mutableStateOf(false) }
    var cameraQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var cameraQueueIndex by remember { mutableStateOf(0) }
    var pendingCameraQueue by remember { mutableStateOf<List<String>>(emptyList()) }

    fun openCameraQueue(queue: List<String>) {
        if (queue.isEmpty()) return
        cameraQueue = queue
        cameraQueueIndex = 0
        showCameraScreen = true
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCameraQueue(pendingCameraQueue)
        else vm.showError("Permission caméra refusée.")
    }

    fun launchCamera(docType: String) {
        val currentEtat = state.dossier?.etat
        // Si l'angle tape fait partie des angles vehicule requis, on enchaine
        // directement sur tous les angles encore manquants dans la meme session.
        val tappedAngle = VEHICLE_ANGLES.find { angleDocTypeForState(it, currentEtat) == docType }
        val queue = if (tappedAngle != null) {
            val missing = VEHICLE_ANGLES.filter { angle ->
                angle.required
                    && !isAngleUploadedForState(angle, currentEtat, state.documents)
                    && state.pendingPhotos.none { it.docType == angleDocTypeForState(angle, currentEtat) }
            }
            (listOf(tappedAngle) + missing.filter { it != tappedAngle })
                .distinct()
                .map { angleDocTypeForState(it, currentEtat) }
        } else {
            listOf(docType)
        }

        pendingCameraQueue = queue
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCameraQueue(queue)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    state.uploadSuccess?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(3000); vm.dismissMessages() } }

    LaunchedEffect(Unit) {
        vm.navigateBack.collect { onBack() }
    }

    val etat = state.dossier?.etat
    val isPhotoFinReparationState = etat == "ATTENTE_PHOTO_FIN_REPARATION"
    val requiredAnglesDone = allRequiredAnglesDoneForState(etat, state.documents)
    val hasPending = state.pendingPhotos.isNotEmpty()

    if (showFinMissionDialog) {
        AlertDialog(
            onDismissRequest = { showFinMissionDialog = false },
            title = { Text("Fin de mission") },
            text = { Text("Confirmer la fin de mission ? Le dossier passera à l'état suivant.") },
            confirmButton = {
                TextButton(onClick = { showFinMissionDialog = false; vm.advanceState() }) {
                    Text("Confirmer", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { showFinMissionDialog = false }) { Text("Annuler") } }
        )
    }

    if (showReassignDialog) {
        AlertDialog(
            onDismissRequest = { showReassignDialog = false },
            title = { Text("Réaffecter à un autre agent terrain") },
            text = {
                when {
                    state.isLoadingAgentTerrainUsers -> Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    state.agentTerrainUsers.isEmpty() -> Text("Aucun autre agent terrain disponible.")
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.agentTerrainUsers
                            .filter { it.id != state.dossier?.agentTerrainUserId }
                            .forEach { agent ->
                                TextButton(
                                    onClick = {
                                        showReassignDialog = false
                                        vm.reassignAgentTerrain(agent.id)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        listOfNotNull(agent.prenom, agent.nom).joinToString(" ").ifBlank { agent.username ?: agent.id },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showReassignDialog = false }) { Text("Annuler") }
            }
        )
    }

    if (showContinueExtraPhotoDialog) {
        AlertDialog(
            onDismissRequest = { showContinueExtraPhotoDialog = false },
            title = { Text("Photo ajoutée") },
            text = { Text("Prendre une autre photo supplémentaire ?") },
            confirmButton = {
                TextButton(onClick = {
                    showContinueExtraPhotoDialog = false
                    cameraQueue = cameraQueue + "$EXTRA_VEHICLE_PHOTO_PREFIX ${System.currentTimeMillis()}"
                    cameraQueueIndex = cameraQueue.lastIndex
                    showCameraScreen = true
                }) {
                    Text("Oui", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showContinueExtraPhotoDialog = false; showCameraScreen = false }) { Text("Non") }
            }
        )
    }

    if (showCameraScreen && cameraQueue.isNotEmpty()) {
        CameraCaptureScreen(
            title = cameraDisplayLabel(cameraQueue[cameraQueueIndex], state.dossier?.etat),
            subtitle = if (cameraQueue.size > 1) "${cameraQueueIndex + 1} / ${cameraQueue.size}" else null,
            onCapture = { file ->
                val docType = cameraQueue[cameraQueueIndex]
                vm.addPendingPhoto(file, docType)
                when {
                    cameraQueueIndex < cameraQueue.lastIndex -> cameraQueueIndex++
                    docType.startsWith(EXTRA_VEHICLE_PHOTO_PREFIX) -> showContinueExtraPhotoDialog = true
                    else -> showCameraScreen = false
                }
            },
            onClose = { showCameraScreen = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.dossier?.reference ?: "Dossier") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Retour") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.dossier == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Text(state.error ?: "Erreur de chargement", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { vm.load() }) { Text("Réessayer") }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        state.error?.let { ErrorBanner(it) { vm.dismissMessages() } }
                        state.uploadSuccess?.let { SuccessBanner(it) { vm.dismissMessages() } }

                        DossierInfoCard(dossier = state.dossier!!)
                        AssureCard(dossier = state.dossier!!)
                        VehiculeCard(dossier = state.dossier!!)

                        // En ATTENTE_EXPERTISE_SR : afficher le dernier devis VALIDE (version light)
                        if (etat == "ATTENTE_EXPERTISE_SR" && state.lastValidDevis != null) {
                            DevisLightCard(devis = state.lastValidDevis!!)
                        }

                        CarDiagramCard(
                            etat = etat,
                            documents = state.documents,
                            pendingPhotos = state.pendingPhotos,
                            onTakePhoto = { docType -> launchCamera(docType) }
                        )

                        ExtraVehiclePhotosCard(
                            documents = state.documents,
                            pendingPhotos = state.pendingPhotos,
                            onTakePhoto = { docType -> launchCamera(docType) },
                            onDeleteDocument = { docId -> vm.deleteDocument(docId) },
                            onRemovePending = { index -> vm.removePendingPhoto(index) }
                        )

                        OtherDocumentsCard(
                            documents = state.documents,
                            pendingPhotos = state.pendingPhotos,
                            docTypes = extraDocTypesForEtat(state.dossier!!.etat),
                            onTakePhoto = { docType -> launchCamera(docType) },
                            onDeleteDocument = { docId -> vm.deleteDocument(docId) },
                            onRemovePending = { index -> vm.removePendingPhoto(index) }
                        )

                        // Angles manquants ou photo fin réparation manquante
                        if (!requiredAnglesDone && !hasPending) {
                            val missing = VEHICLE_ANGLES.filter { it.required }
                                .filter { angle -> !isAngleUploadedForState(angle, etat, state.documents) }
                                .joinToString(", ") { it.label }
                            if (missing.isNotEmpty()) WarningBanner("Photos manquantes : $missing")
                        }

                        // Photos en attente → bouton Valider
                        if (hasPending) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Text(
                                            "${state.pendingPhotos.size} photo(s) en attente d'envoi",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    Button(
                                        onClick = { vm.validateAndUpload() },
                                        enabled = !state.isUploading
                                    ) {
                                        if (state.isUploading) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Envoi...")
                                        } else {
                                            Text("Valider")
                                        }
                                    }
                                }
                            }
                        }

                        // Bouton Réaffecter à un autre agent terrain
                        if (etat == "AFFECTATION_AGENT_TERRAIN") {
                            OutlinedButton(
                                onClick = {
                                    vm.loadAgentTerrainUsers()
                                    showReassignDialog = true
                                },
                                enabled = !state.isReassigning,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (state.isReassigning) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Réaffectation...")
                                } else {
                                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Réaffecter à un autre agent terrain")
                                }
                            }
                        }

                        // Bouton Fin mission
                        Button(
                            onClick = { showFinMissionDialog = true },
                            enabled = !state.isAdvancing && requiredAnglesDone && !hasPending,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.isAdvancing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(4.dp))
                                Text("En cours...")
                            } else {
                                Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    when {
                                        hasPending -> "Fin mission (validez les photos d'abord)"
                                        !requiredAnglesDone -> "Fin mission (photos obligatoires manquantes)"
                                        else -> "Fin de mission"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Card photos véhicule ---
@Composable
private fun VehiclePhotosCard(
    documents: List<DocumentSinistre>,
    pendingPhotos: List<PendingPhoto>,
    onTakePhoto: (String) -> Unit,
    onRemovePending: (Int) -> Unit
) {
    val doneCount = VEHICLE_ANGLES.filter { it.required }.count { angle ->
        documents.any { doc -> doc.type == angleDocType(angle) }
    }
    val totalRequired = VEHICLE_ANGLES.count { it.required }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                SectionTitle("Photos véhicule")
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (doneCount == totalRequired) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        "$doneCount / $totalRequired",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (doneCount == totalRequired) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            VEHICLE_ANGLES.forEach { angle ->
                val docType = angleDocType(angle)
                val isUploaded = documents.any { it.type == docType }
                val pendingIndex = pendingPhotos.indexOfFirst { it.docType == docType }
                val isPending = pendingIndex >= 0

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Indicateur état
                    Icon(
                        imageVector = when {
                            isUploaded -> Icons.Default.CheckCircle
                            isPending  -> Icons.Default.CloudUpload
                            else       -> Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = when {
                            isUploaded -> MaterialTheme.colorScheme.primary
                            isPending  -> MaterialTheme.colorScheme.secondary
                            angle.required -> MaterialTheme.colorScheme.error
                            else       -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    // Miniature si en attente
                    if (isPending) {
                        Image(
                            painter = rememberAsyncImagePainter(pendingPhotos[pendingIndex].file),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = angle.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (angle.required) FontWeight.Medium else FontWeight.Normal
                        )
                        Text(
                            text = when {
                                isUploaded -> "Envoyée"
                                isPending  -> "En attente d'envoi"
                                angle.required -> "Obligatoire"
                                else -> "Optionnel"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                isUploaded -> MaterialTheme.colorScheme.primary
                                isPending  -> MaterialTheme.colorScheme.secondary
                                angle.required -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    // Supprimer si en attente
                    if (isPending) {
                        IconButton(onClick = { onRemovePending(pendingIndex) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }

                    // Caméra
                    IconButton(onClick = { onTakePhoto(docType) }) {
                        Icon(
                            imageVector = if (isUploaded || isPending) Icons.Default.AddAPhoto else Icons.Default.CameraAlt,
                            contentDescription = "Prendre photo",
                            tint = if (isUploaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (angle != VEHICLE_ANGLES.last()) HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

// --- Card photos supplémentaires véhicule ---
@Composable
private fun ExtraVehiclePhotosCard(
    documents: List<DocumentSinistre>,
    pendingPhotos: List<PendingPhoto>,
    onTakePhoto: (String) -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onRemovePending: (Int) -> Unit
) {
    val uploadedExtras = documents.filter { it.type?.startsWith(EXTRA_VEHICLE_PHOTO_PREFIX) == true }
    val pendingExtras = pendingPhotos.mapIndexedNotNull { i, p ->
        if (p.docType.startsWith(EXTRA_VEHICLE_PHOTO_PREFIX)) Pair(i, p) else null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionTitle("Photos supplémentaires")
                OutlinedButton(
                    onClick = { onTakePhoto("$EXTRA_VEHICLE_PHOTO_PREFIX ${System.currentTimeMillis()}") },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ajouter", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (uploadedExtras.isEmpty() && pendingExtras.isEmpty()) {
                Text(
                    "Appuyez sur Ajouter pour prendre des photos supplémentaires (optionnel).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            pendingExtras.forEachIndexed { i, (index, pending) ->
                if (i > 0 || uploadedExtras.isNotEmpty()) HorizontalDivider(thickness = 0.5.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(pending.file),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Photo supplémentaire ${uploadedExtras.size + i + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text("En attente d'envoi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = { onRemovePending(index) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            uploadedExtras.forEachIndexed { i, doc ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp)
                var showConfirm by remember { mutableStateOf(false) }
                if (showConfirm) {
                    AlertDialog(
                        onDismissRequest = { showConfirm = false },
                        title = { Text("Supprimer") },
                        text = { Text("Supprimer la photo supplémentaire ${i + 1} ?") },
                        confirmButton = {
                            TextButton(onClick = { showConfirm = false; doc.id?.let { onDeleteDocument(it) } }) {
                                Text("Supprimer", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Annuler") } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Photo supplémentaire ${i + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        doc.originalFileName?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// --- Card autres documents ---
@Composable
private fun OtherDocumentsCard(
    documents: List<DocumentSinistre>,
    pendingPhotos: List<PendingPhoto>,
    docTypes: List<String>,
    onTakePhoto: (String) -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onRemovePending: (Int) -> Unit
) {
    val uploadedOtherDocs = documents.filter { doc ->
        VEHICLE_ANGLES.none { angle -> doc.type == angleDocType(angle) } &&
        doc.type?.startsWith(EXTRA_VEHICLE_PHOTO_PREFIX) != true
    }
    val pendingOtherPhotos = pendingPhotos.mapIndexedNotNull { index, p ->
        if (VEHICLE_ANGLES.none { angle -> p.docType == angleDocType(angle) } &&
            !p.docType.startsWith(EXTRA_VEHICLE_PHOTO_PREFIX)) Pair(index, p) else null
    }
    var showJustificatifDialog by remember { mutableStateOf(false) }

    if (showJustificatifDialog) {
        AlertDialog(
            onDismissRequest = { showJustificatifDialog = false },
            title = { Text("Type de justificatif") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JUSTIFICATIF_SUBTYPES.forEach { subtype ->
                        TextButton(
                            onClick = { showJustificatifDialog = false; onTakePhoto(subtype) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(subtype, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showJustificatifDialog = false }) { Text("Annuler") }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Autres documents")

            docTypes.forEach { docType ->
                OutlinedButton(
                    onClick = { onTakePhoto(docType) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(docType, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Bouton Justificatif avec sélection de sous-type
            OutlinedButton(
                onClick = { showJustificatifDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Justificatif", style = MaterialTheme.typography.bodySmall)
            }

            // Photos en attente (autres docs)
            if (pendingOtherPhotos.isNotEmpty()) {
                HorizontalDivider()
                Text("En attente d'envoi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                pendingOtherPhotos.forEach { (index, pending) ->
                    PendingPhotoRow(pending = pending, onRemove = { onRemovePending(index) })
                }
            }

            // Documents déjà uploadés
            if (uploadedOtherDocs.isNotEmpty()) {
                HorizontalDivider()
                uploadedOtherDocs.forEach { doc ->
                    DocumentRow(doc = doc, onDelete = { doc.id?.let { onDeleteDocument(it) } })
                }
            }
        }
    }
}

@Composable
private fun PendingPhotoRow(pending: PendingPhoto, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = rememberAsyncImagePainter(pending.file),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(pending.docType, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text("En attente d'envoi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DossierInfoCard(dossier: Dossier) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Informations dossier")
            InfoRow("Référence", dossier.reference)
            InfoRow("Référence assurance", dossier.referenceAssurance)
            InfoRow("État", formatEtat(dossier.etat))
            InfoRow("Type mission", dossier.typeMission)
            InfoRow("Date sinistre", dossier.dateSurvenance)
            InfoRow("Ville expertise", dossier.villeExpertise)
            InfoRow("Ville sinistre", dossier.villeSinistre)
            InfoRow("Assurance", dossier.assurance?.nom)
        }
    }
}

@Composable
private fun AssureCard(dossier: Dossier) {
    val assure = dossier.assure ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Assuré")
            InfoRow("Nom", listOfNotNull(assure.nom, assure.prenom).joinToString(" ").ifBlank { null })
            InfoRow("Téléphone", assure.telephone)
            InfoRow("Email", assure.email)
            InfoRow("Adresse", assure.adresse)
        }
    }
}

@Composable
private fun VehiculeCard(dossier: Dossier) {
    val v = dossier.vehiculeAssure ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Véhicule assuré")
            InfoRow("Immatriculation", v.immatriculation)
            InfoRow("Marque", v.marque)
            InfoRow("Modèle", v.modele)
            InfoRow("Usage", v.usage)
            InfoRow("Adresse véhicule", v.adresse)
        }
    }
}

@Composable
private fun DocumentRow(doc: DocumentSinistre, onDelete: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Supprimer") },
            text = { Text("Supprimer « ${doc.originalFileName ?: doc.fileName} » ?") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onDelete() }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Annuler") } }
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (doc.contentType?.startsWith("image") == true) Icons.Default.Image else Icons.Default.AttachFile,
            contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(doc.originalFileName ?: doc.fileName ?: "-", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            doc.type?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun WarningBanner(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.5f))
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Fermer", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun SuccessBanner(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Fermer", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

private fun formatEtat(etat: String?): String = when (etat) {
    "AFFECTATION_AGENT_TERRAIN" -> "Mission terrain"
    "ATTENTE_EXPERTISE_SR" -> "Expertise SR"
    "ATTENTE_PHOTO_FIN_REPARATION" -> "Photos fin réparation"
    "FACTURE_MANQUANTE" -> "Facture manquante"
    else -> etat ?: "-"
}
