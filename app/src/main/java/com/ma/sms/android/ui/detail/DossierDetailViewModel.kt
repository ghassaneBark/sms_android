package com.ma.sms.android.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.AgentTerrainUser
import com.ma.sms.android.data.model.Devis
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.repository.DossierRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

private const val MAX_CONCURRENT_PHOTO_UPLOADS = 4

data class PendingPhoto(
    val file: File,
    val docType: String
)

data class DossierDetailUiState(
    val dossier: Dossier? = null,
    val documents: List<DocumentSinistre> = emptyList(),
    val pendingPhotos: List<PendingPhoto> = emptyList(),
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val isAdvancing: Boolean = false,
    val error: String? = null,
    val uploadSuccess: String? = null,
    val lastValidDevis: Devis? = null,
    val agentTerrainUsers: List<AgentTerrainUser> = emptyList(),
    val isLoadingAgentTerrainUsers: Boolean = false,
    val isReassigning: Boolean = false,
    val isDownloading: Boolean = false,
    val fileToOpen: FileToOpen? = null
)

data class FileToOpen(val uri: Uri, val mimeType: String)

class DossierDetailViewModel(
    private val dossierId: Long,
    private val repository: DossierRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierDetailUiState())
    val uiState: StateFlow<DossierDetailUiState> = _uiState

    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getDossier(dossierId)
                .onSuccess { dossier ->
                    _uiState.value = _uiState.value.copy(dossier = dossier, isLoading = false)
                    loadDocuments()
                    // En cours/après réparation : charger le dernier devis VALIDE pour l'afficher en mode light
                    if (dossier.etat == "ATTENTE_EXPERTISE_SR" || dossier.etat == "ATTENTE_PHOTO_FIN_REPARATION") {
                        loadLastValidDevis()
                    }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Impossible de charger le dossier.")
                }
        }
    }

    fun loadDocuments() {
        viewModelScope.launch {
            repository.getDocuments(dossierId)
                .onSuccess { docs -> _uiState.value = _uiState.value.copy(documents = docs) }
        }
    }

    fun loadLastValidDevis() {
        viewModelScope.launch {
            repository.getLastValidDevis(dossierId)
                .onSuccess { devis -> _uiState.value = _uiState.value.copy(lastValidDevis = devis) }
        }
    }

    // Ajoute une photo à la liste locale (pas encore uploadée)
    fun addPendingPhoto(file: File, docType: String) {
        val current = _uiState.value.pendingPhotos.toMutableList()
        current.add(PendingPhoto(file, docType))
        _uiState.value = _uiState.value.copy(pendingPhotos = current)
    }

    // Supprime une photo en attente
    fun removePendingPhoto(index: Int) {
        val current = _uiState.value.pendingPhotos.toMutableList()
        current.getOrNull(index)?.file?.delete()
        current.removeAt(index)
        _uiState.value = _uiState.value.copy(pendingPhotos = current)
    }

    // Upload de toutes les photos en attente, en parallele (concurrence limitee)
    fun validateAndUpload() {
        val pending = _uiState.value.pendingPhotos
        if (pending.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, error = null)
            val semaphore = Semaphore(MAX_CONCURRENT_PHOTO_UPLOADS)
            val results = pending.map { photo ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        repository.uploadPhoto(dossierId, photo.file, photo.docType)
                            .onSuccess { photo.file.delete() }
                    }
                }
            }.awaitAll()
            val failed = results.count { it.isFailure }
            if (failed == 0) {
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    pendingPhotos = emptyList(),
                    uploadSuccess = "${pending.size} photo(s) enregistrée(s) avec succès."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    error = "$failed photo(s) n'ont pas pu être uploadées."
                )
            }
            loadDocuments()
        }
    }

    fun deleteDocument(documentId: Long) {
        viewModelScope.launch {
            repository.deleteDocument(dossierId, documentId)
                .onSuccess { loadDocuments() }
                .onFailure { _uiState.value = _uiState.value.copy(error = "Impossible de supprimer le document.") }
        }
    }

    fun advanceState() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAdvancing = true, error = null)
            repository.advanceState(dossierId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isAdvancing = false)
                    _navigateBack.emit(Unit)
                }
                .onFailure {
                    val msg = it.message?.takeIf { m -> m.isNotBlank() }
                        ?: "Impossible de terminer la mission."
                    _uiState.value = _uiState.value.copy(
                        isAdvancing = false,
                        error = msg
                    )
                }
        }
    }

    // Charge la liste des agents terrain de l'antenne du dossier (pour le selecteur de reaffectation)
    fun loadAgentTerrainUsers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingAgentTerrainUsers = true)
            repository.getAgentTerrainUsers(_uiState.value.dossier?.antenne?.id)
                .onSuccess { users ->
                    _uiState.value = _uiState.value.copy(
                        agentTerrainUsers = users,
                        isLoadingAgentTerrainUsers = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoadingAgentTerrainUsers = false,
                        error = "Impossible de charger la liste des agents terrain."
                    )
                }
        }
    }

    fun reassignAgentTerrain(newAgentTerrainUserId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isReassigning = true, error = null)
            repository.reassignAgentTerrain(dossierId, newAgentTerrainUserId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isReassigning = false)
                    _navigateBack.emit(Unit)
                }
                .onFailure {
                    val msg = it.message?.takeIf { m -> m.isNotBlank() }
                        ?: "Impossible de réaffecter le dossier."
                    _uiState.value = _uiState.value.copy(isReassigning = false, error = msg)
                }
        }
    }

    // Telecharge une piece jointe et demande son ouverture (visionneuse image/PDF du systeme).
    fun viewDocument(doc: DocumentSinistre) {
        val documentId = doc.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true, error = null)
            val fileName = doc.originalFileName ?: doc.fileName ?: "document-$documentId"
            repository.downloadDocument(dossierId, documentId, fileName)
                .onSuccess { uri ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        fileToOpen = FileToOpen(uri, doc.contentType ?: "*/*")
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        error = "Impossible d'ouvrir le document."
                    )
                }
        }
    }

    // Telecharge le PDF "Accord sur devis" et demande son ouverture.
    fun viewDevisPdf(devis: Devis) {
        val devisId = devis.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true, error = null)
            repository.downloadDevisPdf(devisId)
                .onSuccess { uri ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        fileToOpen = FileToOpen(uri, "application/pdf")
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        error = "Impossible d'ouvrir l'accord sur devis."
                    )
                }
        }
    }

    // Ouvre une photo pas encore envoyee (fichier local) pour verification avant upload.
    fun viewPendingPhoto(file: File) {
        _uiState.value = _uiState.value.copy(
            fileToOpen = FileToOpen(repository.uriForLocalFile(file), "image/jpeg")
        )
    }

    fun fileOpenHandled() {
        _uiState.value = _uiState.value.copy(fileToOpen = null)
    }

    fun showError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun dismissMessages() {
        _uiState.value = _uiState.value.copy(error = null, uploadSuccess = null)
    }
}
