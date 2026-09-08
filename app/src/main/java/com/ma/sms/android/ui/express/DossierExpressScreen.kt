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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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

    // Camera : un seul type de document a la fois, l'agent rouvre la camera pour chaque piece.
    var showCameraScreen by remember { mutableStateOf(false) }
    var pendingDocType by remember { mutableStateOf<String?>(null) }
    var cameraTitle by remember { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            showCameraScreen = true
        } else {
            vm.dismissMessages()
        }
    }

    fun launchCamera(docType: String, title: String) {
        pendingDocType = docType
        cameraTitle = title
        val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission && hasLocationPermission) {
            showCameraScreen = true
        } else {
            cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    if (showCameraScreen && pendingDocType != null) {
        CameraCaptureScreen(
            title = cameraTitle,
            subtitle = null,
            onCapture = { file ->
                vm.addPendingPhoto(file, pendingDocType!!)
                showCameraScreen = false
            },
            onClose = { showCameraScreen = false }
        )
        return
    }

    val etat = state.dossier?.etat

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
                etat == "EN_ATTENTE_ACCORD_FORFAIT" -> ForfaitSection(state = state, vm = vm)
                else -> FormAndDocumentsSection(
                    state = state,
                    vm = vm,
                    onTakePhoto = { docType, title -> launchCamera(docType, title) }
                )
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
private fun ForfaitSection(state: DossierExpressUiState, vm: DossierExpressViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Proposition de forfait")
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

@Composable
private fun FormAndDocumentsSection(
    state: DossierExpressUiState,
    vm: DossierExpressViewModel,
    onTakePhoto: (String, String) -> Unit
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Documents obligatoires")
            Spacer(Modifier.height(4.dp))

            DocTypeRow(
                label = "Photo(s) véhicule",
                count = documentCount(state, DOC_TYPE_PHOTO_VEHICULE),
                onTakePhoto = { onTakePhoto(DOC_TYPE_PHOTO_VEHICULE, "Photo véhicule") }
            )
            DocTypeRow(
                label = "Carte grise",
                count = documentCount(state, DOC_TYPE_CARTE_GRISE),
                onTakePhoto = { onTakePhoto(DOC_TYPE_CARTE_GRISE, "Carte grise") }
            )
            DocTypeRow(
                label = "Attestation assurance",
                count = documentCount(state, DOC_TYPE_ATTESTATION_ASSURANCE),
                onTakePhoto = { onTakePhoto(DOC_TYPE_ATTESTATION_ASSURANCE, "Attestation assurance") }
            )
            DocTypeRow(
                label = "Garantie",
                count = documentCount(state, DOC_TYPE_GARANTIE),
                onTakePhoto = { onTakePhoto(DOC_TYPE_GARANTIE, "Garantie") }
            )

            HorizontalDivider()
            JustificatifDropdown(state = state, vm = vm)
            DocTypeRow(
                label = state.justificatifType,
                count = documentCount(state, state.justificatifType),
                onTakePhoto = { onTakePhoto(state.justificatifType, state.justificatifType) }
            )
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

private fun documentCount(state: DossierExpressUiState, docType: String): Int {
    val uploaded = state.documents.count { it.type == docType }
    val pending = state.pendingPhotos.count { it.docType == docType }
    return uploaded + pending
}

@Composable
private fun DocTypeRow(label: String, count: Int, onTakePhoto: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (count > 0) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                if (count > 0) "$count fichier(s)" else "Obligatoire",
                style = MaterialTheme.typography.labelSmall,
                color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        IconButton(onClick = onTakePhoto) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Prendre photo")
        }
    }
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
