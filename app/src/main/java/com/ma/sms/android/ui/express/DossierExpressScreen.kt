package com.ma.sms.android.ui.express

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ma.sms.android.SmsApplication
import com.ma.sms.android.data.repository.DossierRepository
import com.ma.sms.android.ui.detail.CameraCaptureScreen
import com.ma.sms.android.ui.detail.CarDiagramCard
import com.ma.sms.android.ui.detail.ExtraVehiclePhotosCard
import com.ma.sms.android.ui.detail.OtherDocumentsCard
import com.ma.sms.android.ui.detail.VEHICLE_ANGLES
import com.ma.sms.android.ui.detail.allRequiredAnglesDoneForState
import com.ma.sms.android.ui.detail.angleDocTypeForState
import com.ma.sms.android.ui.detail.cameraDisplayLabel
import com.ma.sms.android.ui.detail.extraDocTypesForEtat
import com.ma.sms.android.ui.detail.extraVehiclePhotoDocTypeForState
import com.ma.sms.android.ui.detail.isAngleUploadedForState
import com.ma.sms.android.ui.detail.isExtraVehiclePhotoDocType
import com.ma.sms.android.util.JwtUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DossierExpressScreen(
    repository: DossierRepository,
    onBack: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val agentTerrainUserId = remember {
        val app = context.applicationContext as SmsApplication
        JwtUtils.extractUserId(app.tokenManager.accessToken)
    }
    val vm: DossierExpressViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DossierExpressViewModel(repository, agentTerrainUserId) as T
        }
    })
    val state by vm.uiState.collectAsState()

    LaunchedEffect(Unit) {
        vm.finished.collect { onFinished() }
    }

    // Ouvre le fichier telecharge (piece jointe) dans la visionneuse systeme des qu'un
    // telechargement aboutit — identique a DossierContributeScreen/DossierDetailScreen.
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

    val etat = state.dossier?.etat

    // File d'attente de captures pour l'ecran camera integre, mecanique identique a
    // DossierContributeScreen.launchCamera (elle-meme calquee sur DossierDetailScreen) :
    // enchaine tous les angles vehicule requis manquants dans la meme session, et s'auto-
    // prolonge pour les photos supplementaires libres.
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
        // Si l'angle tape fait partie des angles vehicule requis, on enchaine directement sur
        // tous les angles encore manquants dans la meme session.
        val tappedAngle = VEHICLE_ANGLES.find { angleDocTypeForState(it, etat) == docType }
        val queue = if (tappedAngle != null) {
            val missing = VEHICLE_ANGLES.filter { angle ->
                angle.required
                    && !isAngleUploadedForState(angle, etat, state.documents)
                    && state.pendingPhotos.none { it.docType == angleDocTypeForState(angle, etat) }
            }
            (listOf(tappedAngle) + missing.filter { it != tappedAngle })
                .distinct()
                .map { angleDocTypeForState(it, etat) }
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

    if (showCameraScreen && cameraQueue.isNotEmpty()) {
        CameraCaptureScreen(
            title = cameraDisplayLabel(cameraQueue[cameraQueueIndex], etat),
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
                        cameraQueue = cameraQueue + "${extraVehiclePhotoDocTypeForState(etat)} ${System.currentTimeMillis()}"
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
                title = { Text("Dossier Express") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Retour") }
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
            state.statusMessage?.let { InfoBanner(it) { vm.dismissMessages() } }
            state.forfaitResult?.let { InfoBanner(it) { vm.dismissMessages() } }

            when {
                etat == "FORFAIT_ACCEPTE" -> SuccessSection(reference = state.dossier?.reference, onBack = onFinished)
                etat == "TRAITEMENT_DOSSIER_EXPRESS" && state.showForfaitSection -> ForfaitSection(state = state, vm = vm, onBack = vm::backToDocuments)
                etat == "TRAITEMENT_DOSSIER_EXPRESS" -> PhotosAvantReparationSection(
                    state = state,
                    vm = vm,
                    etat = etat,
                    onTakePhoto = { docType -> launchCamera(docType) }
                )
                else -> AssureVehiculeFormSection(state = state, vm = vm)
            }
        }
    }
}

@Composable
private fun SuccessSection(reference: String?, onBack: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Forfait accepté", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Le dossier ${reference ?: ""} est finalisé.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Button(onClick = onBack) { Text("Retour à la liste") }
        }
    }
}

@Composable
private fun ForfaitSection(state: DossierExpressUiState, vm: DossierExpressViewModel, onBack: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Retour aux documents")
                }
                Spacer(Modifier.width(4.dp))
                SectionTitle("Proposition de forfait")
            }
            Text(
                "Le dossier est prêt pour la proposition de forfait à l'assuré.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.montantForfait,
                onValueChange = vm::updateMontantForfait,
                label = { Text("Montant du forfait (DH)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Réponse de l'assuré", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.selectReponseAssureForfait(true) },
                    colors = if (state.reponseAssureForfait == true)
                        ButtonDefaults.buttonColors()
                    else
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.weight(1f)
                ) { Text("Accepté") }
                Button(
                    onClick = { vm.selectReponseAssureForfait(false) },
                    colors = if (state.reponseAssureForfait == false)
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.weight(1f)
                ) { Text("Refusé") }
            }
            Button(
                onClick = { vm.submitForfait() },
                enabled = !state.isSubmittingForfait,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSubmittingForfait) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Envoi...")
                } else {
                    Text("Fin de mission")
                }
            }
        }
    }
}

// --- Etape A : creation/edition du dossier (assure + vehicule), avant l'affectation terrain ---
@Composable
private fun AssureVehiculeFormSection(
    state: DossierExpressUiState,
    vm: DossierExpressViewModel
) {
    val dossierCreated = state.dossier != null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Assuré")
            OutlinedTextField(value = state.nom, onValueChange = vm::updateNom, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.prenom, onValueChange = vm::updatePrenom, label = { Text("Prénom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.telephone, onValueChange = vm::updateTelephone, label = { Text("Téléphone *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.email, onValueChange = vm::updateEmail, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.intermediaire, onValueChange = vm::updateIntermediaire, label = { Text("Intermédiaire *") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            AssuranceDropdown(state = state, vm = vm)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Véhicule assuré")
            OutlinedTextField(value = state.immatriculation, onValueChange = vm::updateImmatriculation, label = { Text("Immatriculation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.marque, onValueChange = vm::updateMarque, label = { Text("Marque") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.modele, onValueChange = vm::updateModele, label = { Text("Modèle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.numeroChassis, onValueChange = vm::updateNumeroChassis, label = { Text("N° châssis") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }

    Button(
        onClick = { vm.saveProgress() },
        enabled = !state.isSaving,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Enregistrement...")
        } else {
            Text(if (dossierCreated) "Enregistrer" else "Créer le dossier")
        }
    }
}

// --- Etape B : mission terrain "avant reparation", dans l'etat unique TRAITEMENT_DOSSIER_EXPRESS ---
// Reprend a l'identique les cartes de la vraie mission "avant reparation" de DossierDetailScreen
// (CarDiagramCard : les 11 angles requis, ExtraVehiclePhotosCard : photos libres, OtherDocumentsCard :
// carte grise/permis/CIN/attestation/garantie + justificatif), plutot que l'ancienne checklist
// generique a une seule case "Photo(s) vehicule".
@Composable
private fun PhotosAvantReparationSection(
    state: DossierExpressUiState,
    vm: DossierExpressViewModel,
    etat: String?,
    onTakePhoto: (String) -> Unit
) {
    CarDiagramCard(
        etat = etat,
        documents = state.documents,
        pendingPhotos = state.pendingPhotos,
        onTakePhoto = onTakePhoto,
        onViewDocument = { vm.viewDocument(it) },
        onViewPendingPhoto = { vm.viewPendingPhoto(it) }
    )

    ExtraVehiclePhotosCard(
        etat = etat,
        documents = state.documents,
        pendingPhotos = state.pendingPhotos,
        onTakePhoto = onTakePhoto,
        onDeleteDocument = { vm.deleteDocument(it) },
        onRemovePending = { vm.removePendingPhoto(it) },
        onViewDocument = { vm.viewDocument(it) },
        onViewPendingPhoto = { vm.viewPendingPhoto(it) }
    )

    OtherDocumentsCard(
        etat = etat,
        documents = state.documents,
        pendingPhotos = state.pendingPhotos,
        docTypes = extraDocTypesForEtat(etat),
        onTakePhoto = onTakePhoto,
        onDeleteDocument = { vm.deleteDocument(it) },
        onRemovePending = { vm.removePendingPhoto(it) },
        onViewDocument = { vm.viewDocument(it) },
        onViewPendingPhoto = { vm.viewPendingPhoto(it) }
    )

    Button(
        onClick = { vm.saveProgress() },
        enabled = !state.isSaving,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Enregistrement...")
        } else {
            Text("Enregistrer")
        }
    }

    // Mission terrain et forfait partagent le meme etat TRAITEMENT_DOSSIER_EXPRESS : ce bouton
    // (pas un changement d'etat backend) est ce qui fait passer a la section forfait, une fois
    // les pieces requises reellement televersees (pas seulement en attente d'envoi).
    if (requiredExpressDocumentsUploaded(state, etat)) {
        Button(
            onClick = { vm.continueToForfait() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continuer vers le forfait")
        }
    }
}

private fun requiredExpressDocumentsUploaded(state: DossierExpressUiState, etat: String?): Boolean {
    fun uploaded(type: String) = state.documents.any { it.type == type }
    return allRequiredAnglesDoneForState(etat, state.documents) &&
        uploaded(DOC_TYPE_CARTE_GRISE) &&
        uploaded(DOC_TYPE_ATTESTATION_ASSURANCE) &&
        uploaded(DOC_TYPE_GARANTIE) &&
        JUSTIFICATIF_TYPES.any { type -> state.documents.any { it.type == type } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssuranceDropdown(state: DossierExpressUiState, vm: DossierExpressViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = state.assurances.firstOrNull { it.id == state.selectedAssuranceId }?.nom ?: ""

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Assurance *") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (state.isLoadingAssurances) {
                DropdownMenuItem(text = { Text("Chargement...") }, onClick = {})
            }
            state.assurances.forEach { assurance ->
                DropdownMenuItem(
                    text = { Text(assurance.nom ?: "Assurance #${assurance.id}") },
                    onClick = {
                        vm.selectAssurance(assurance.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

// Non appelee depuis la section photos : OtherDocumentsCard integre desormais son propre
// selecteur de sous-type de justificatif (JUSTIFICATIF_SUBTYPES, meme role que ce composable).
// Conservee (avec state.justificatifType/vm.selectJustificatifType inchanges dans le ViewModel)
// au cas ou un autre point d'entree en aurait besoin.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JustificatifDropdown(state: DossierExpressUiState, vm: DossierExpressViewModel) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = state.justificatifType,
            onValueChange = {},
            readOnly = true,
            label = { Text("Type de justificatif") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            JUSTIFICATIF_TYPES.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type) },
                    onClick = {
                        vm.selectJustificatifType(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
