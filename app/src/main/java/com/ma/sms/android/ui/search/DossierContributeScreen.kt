package com.ma.sms.android.ui.search

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.repository.DossierRepository
import com.ma.sms.android.ui.detail.CameraCaptureScreen
import com.ma.sms.android.ui.detail.CarDiagramCard
import com.ma.sms.android.ui.detail.ExtraVehiclePhotosCard
import com.ma.sms.android.ui.detail.VEHICLE_ANGLES
import com.ma.sms.android.ui.detail.angleDocTypeForState
import com.ma.sms.android.ui.detail.cameraDisplayLabel
import com.ma.sms.android.ui.detail.extraVehiclePhotoDocTypeForState
import com.ma.sms.android.ui.detail.isAngleUploadedForState
import com.ma.sms.android.ui.detail.isExtraVehiclePhotoDocType

// Choix explicite de la phase par l'agent, plutot que deduit de Dossier.etat : la recherche portant
// desormais sur tous les etats, l'etat formel du dossier ne reflete pas forcement fidelement la
// situation reelle constatee sur place (ex. reparation deja terminee alors que le dossier n'a pas
// encore avance). Ces deux valeurs sont des etats "virtuels" : ce sont les memes constantes que les
// vrais etats agent terrain (ATTENTE_EXPERTISE_SR / ATTENTE_PHOTO_FIN_REPARATION), ce qui permet de
// reutiliser telle quelle toute la logique de capture photo de DossierDetailScreen (CarDiagramCard,
// ExtraVehiclePhotosCard, cameraDisplayLabel...) qui se base sur un etat. Le vrai Dossier.etat n'est
// en revanche jamais modifie par cet ecran (aucun appel a advanceState ici).
private enum class ContributePhase(val label: String, val etat: String) {
    EN_COURS("En cours de réparation", "ATTENTE_EXPERTISE_SR"),
    APRES("Après réparation", "ATTENTE_PHOTO_FIN_REPARATION")
}

/**
 * Ecran de contribution photo, en deux etapes internes (pas de nouvelle route de navigation) :
 *   A. selection de la phase (lecture seule du resume dossier + choix en cours/apres reparation)
 *   B. capture photo, reutilisant a l'identique les cartes de DossierDetailScreen pour l'etat
 *      "virtuel" choisi (CarDiagramCard + ExtraVehiclePhotosCard, ou seulement la seconde en
 *      "en cours de reparation" puisque le croquis vehicule n'a plus de sens pour cette phase).
 * Deliberement etroit : pas de reassignation, pas d'autres documents, pas de fin de mission, pas
 * de donnees financieres — uniquement creation/upload de documents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DossierContributeScreen(
    dossier: Dossier,
    repository: DossierRepository,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val vm: DossierContributeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DossierContributeViewModel(dossier.id, repository) as T
        }
    })
    val state by vm.uiState.collectAsState()

    // Ouvre le fichier telecharge (piece jointe) dans la visionneuse systeme des qu'un
    // telechargement aboutit — identique a DossierDetailScreen.
    LaunchedEffect(state.fileToOpen) {
        val fileToOpen = state.fileToOpen ?: return@LaunchedEffect
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(fileToOpen.uri, fileToOpen.mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            vm.showError("Aucune application disponible pour ouvrir ce fichier.")
        }
        vm.fileOpenHandled()
    }

    // Etape A tant que null (phase pas encore validee), etape B une fois une phase validee.
    var selectedPhase by remember { mutableStateOf<ContributePhase?>(null) }
    var activeEtat by remember { mutableStateOf<String?>(null) }

    // File d'attente de captures pour l'ecran camera integre — logique identique a
    // DossierDetailScreen.launchCamera, adaptee a activeEtat et aux documents/pendingPhotos
    // locaux a cet ecran.
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

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) openCameraQueue(pendingCameraQueue)
        else vm.showError("Permission caméra refusée.")
        // La position est facultative (incrustee sur la photo si disponible) : son refus
        // ne bloque jamais l'ouverture de la camera.
    }

    fun launchCamera(docType: String) {
        val currentEtat = activeEtat
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
        val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission && hasLocationPermission) {
            openCameraQueue(queue)
        } else {
            cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    state.uploadSuccess?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(3000); vm.dismissMessages() } }

    if (showCameraScreen && cameraQueue.isNotEmpty()) {
        CameraCaptureScreen(
            title = cameraDisplayLabel(cameraQueue[cameraQueueIndex], activeEtat),
            subtitle = if (isExtraVehiclePhotoDocType(cameraQueue[cameraQueueIndex]))
                "Photo ${cameraQueueIndex + 1} — X pour terminer"
            else if (cameraQueue.size > 1) "${cameraQueueIndex + 1} / ${cameraQueue.size}" else null,
            onCapture = { file ->
                val docType = cameraQueue[cameraQueueIndex]
                vm.addPendingPhoto(file, docType)
                when {
                    cameraQueueIndex < cameraQueue.lastIndex -> cameraQueueIndex++
                    isExtraVehiclePhotoDocType(docType) -> {
                        // Photos supplementaires en nombre libre : on enchaine directement sur
                        // une nouvelle prise sans demander de confirmation a chaque photo ;
                        // l'agent ferme l'ecran (bouton X) quand il a termine.
                        cameraQueue = cameraQueue + "${extraVehiclePhotoDocTypeForState(activeEtat)} ${System.currentTimeMillis()}"
                        cameraQueueIndex = cameraQueue.lastIndex
                    }
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
                title = { Text("Contribuer des photos") },
                navigationIcon = {
                    IconButton(onClick = {
                        // Depuis l'etape B (capture), le retour revient a l'etape A (selection de
                        // phase) plutot que de quitter tout l'ecran ; seul un retour depuis
                        // l'etape A quitte reellement (onDone).
                        if (activeEtat != null) activeEtat = null else onDone()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.error?.let { ErrorBanner(it) { vm.dismissMessages() } }
            state.uploadSuccess?.let { InfoBanner(it) { vm.dismissMessages() } }

            // Resume dossier en lecture seule (reference, assure, vehicule) — inchange.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(dossier.reference ?: "Sans référence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.secondary
                        ) {
                            Text(
                                contributableStateLabel(dossier.etat),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    HorizontalDivider()
                    dossier.assure?.let { assure ->
                        val nom = listOfNotNull(assure.nom, assure.prenom).joinToString(" ").ifBlank { "-" }
                        SummaryRow(icon = Icons.Default.Person, text = nom)
                    }
                    dossier.vehiculeAssure?.let { v ->
                        val vehicule = listOfNotNull(v.marque, v.modele, v.immatriculation).joinToString(" — ")
                        if (vehicule.isNotBlank()) SummaryRow(icon = Icons.Default.DirectionsCar, text = vehicule)
                    }
                }
            }

            if (activeEtat == null) {
                // --- Etape A : selection de la phase ---
                PhaseSelectionCard(
                    selected = selectedPhase,
                    onSelect = { selectedPhase = it },
                    onValidate = { selectedPhase?.let { activeEtat = it.etat } }
                )
            } else {
                // --- Etape B : capture photo, miroir exact de la section correspondante de
                // DossierDetailScreen pour l'etat "virtuel" choisi ---
                val etat = activeEtat

                // "En cours de reparation" (ATTENTE_EXPERTISE_SR) : plus de croquis/angles
                // impose, seulement des photos libres (voir ExtraVehiclePhotosCard ci-dessous).
                if (etat != "ATTENTE_EXPERTISE_SR") {
                    CarDiagramCard(
                        etat = etat,
                        documents = state.documents,
                        pendingPhotos = state.pendingPhotos,
                        onTakePhoto = { docType -> launchCamera(docType) },
                        onViewDocument = { doc -> vm.viewDocument(doc) },
                        onViewPendingPhoto = { file -> vm.viewPendingPhoto(file) }
                    )
                }

                ExtraVehiclePhotosCard(
                    etat = etat,
                    documents = state.documents,
                    pendingPhotos = state.pendingPhotos,
                    onTakePhoto = { docType -> launchCamera(docType) },
                    onDeleteDocument = { docId -> vm.deleteDocument(docId) },
                    onRemovePending = { index -> vm.removePendingPhoto(index) },
                    onViewDocument = { doc -> vm.viewDocument(doc) },
                    onViewPendingPhoto = { file -> vm.viewPendingPhoto(file) }
                )

                // Photos en attente → bouton Valider l'envoi
                if (state.pendingPhotos.isNotEmpty()) {
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
                                    Text("Valider l'envoi")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Pas de bouton "Fin de mission"/reassignation sur cet ecran, volontairement : seule la fleche
// retour de la barre du haut permet de revenir a l'etape A puis de quitter, pour eviter toute
// confusion avec l'action "Fin de mission" qui fait avancer l'etat du dossier ailleurs dans l'app
// (celle-ci ne fait jamais avancer d'etat, uniquement creer/uploader des documents).
@Composable
private fun PhaseSelectionCard(
    selected: ContributePhase?,
    onSelect: (ContributePhase) -> Unit,
    onValidate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Phase de la mission", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Choisissez la phase correspondant à la situation constatée sur place.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ContributePhase.values().forEach { phase ->
                val isSelected = selected == phase
                Surface(
                    onClick = { onSelect(phase) },
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(selected = isSelected, onClick = { onSelect(phase) })
                        Text(
                            phase.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Button(
                onClick = onValidate,
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Valider")
            }
        }
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
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
private fun InfoBanner(message: String, onDismiss: () -> Unit) {
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
