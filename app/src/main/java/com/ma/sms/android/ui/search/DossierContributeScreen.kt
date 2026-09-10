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
import com.ma.sms.android.ui.detail.VEHICLE_ANGLES
import com.ma.sms.android.ui.detail.VehicleAngle
import com.ma.sms.android.ui.detail.angleDocTypeForState
import com.ma.sms.android.ui.detail.extraVehiclePhotoDocTypeForState

private const val FREE_PHOTO_LABEL = "Photo libre"

/**
 * Ecran de contribution photo, deliberement etroit : consultation en lecture seule du dossier
 * (reference, assure, vehicule, etat) + prise de photo(s) supplementaires, rien d'autre (pas de
 * reassignation, pas d'autres documents, pas de fin de mission, pas de donnees financieres).
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
    val etat = dossier.etat

    // null = "Photo libre" (valeur par defaut). Reste selectionne d'une photo a l'autre : l'agent
    // n'a pas a re-choisir le type a chaque prise, seulement a le changer s'il le souhaite.
    var selectedAngle by remember { mutableStateOf<VehicleAngle?>(null) }
    var showCameraScreen by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            showCameraScreen = true
        }
    }

    fun launchCamera() {
        val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission && hasLocationPermission) {
            showCameraScreen = true
        } else {
            cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    if (showCameraScreen) {
        val label = selectedAngle?.label ?: FREE_PHOTO_LABEL
        CameraCaptureScreen(
            title = "Photo supplémentaire",
            subtitle = "${dossier.reference ?: ""} — $label",
            onCapture = { file ->
                val docType = selectedAngle?.let { angleDocTypeForState(it, etat) }
                    ?: extraVehiclePhotoDocTypeForState(etat)
                vm.uploadPhoto(file, docType, label)
                showCameraScreen = false
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
                    IconButton(onClick = onDone) {
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
            state.lastResult?.let { result ->
                if (result.success) {
                    InfoBanner("Photo « ${result.label} » envoyée.") { vm.dismissResult() }
                } else {
                    ErrorBanner("Échec de l'envoi (${result.label}) : ${result.message ?: ""}") { vm.dismissResult() }
                }
            }

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
                                contributableStateLabel(etat),
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ajouter une photo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    PhotoTypeDropdown(selected = selectedAngle, onSelect = { selectedAngle = it })
                    Button(
                        onClick = { launchCamera() },
                        enabled = !state.isUploading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("Envoi...")
                        } else {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Prendre une photo")
                        }
                    }
                }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Terminé")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoTypeDropdown(selected: VehicleAngle?, onSelect: (VehicleAngle?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = selected?.label ?: FREE_PHOTO_LABEL

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Type de photo") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(FREE_PHOTO_LABEL) }, onClick = { onSelect(null); expanded = false })
            VEHICLE_ANGLES.forEach { angle ->
                DropdownMenuItem(text = { Text(angle.label) }, onClick = { onSelect(angle); expanded = false })
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
