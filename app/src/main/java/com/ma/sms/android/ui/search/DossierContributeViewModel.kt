package com.ma.sms.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.repository.DossierRepository
import com.ma.sms.android.ui.detail.FileToOpen
import com.ma.sms.android.ui.detail.PendingPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

// Meme limite de concurrence que DossierDetailViewModel (MAX_CONCURRENT_PHOTO_UPLOADS, prive a ce
// fichier) : uploads par lot identiques, juste scopes a un agent non assigne au dossier.
private const val MAX_CONCURRENT_PHOTO_UPLOADS = 4

data class DossierContributeUiState(
    val documents: List<DocumentSinistre> = emptyList(),
    val pendingPhotos: List<PendingPhoto> = emptyList(),
    val isLoadingDocuments: Boolean = false,
    val isUploading: Boolean = false,
    val isDownloading: Boolean = false,
    val error: String? = null,
    val uploadSuccess: String? = null,
    val fileToOpen: FileToOpen? = null
)

/**
 * Ecran "contribution" : un agent non assigne au dossier prend des photos pour une phase choisie
 * explicitement dans l'UI (en cours / apres reparation), sans prendre possession du dossier ni
 * faire avancer son etat (aucun appel a advanceState/reassignAgentTerrain ici). Reprend la meme
 * mecanique de photos en attente + upload par lot que DossierDetailViewModel (PendingPhoto,
 * Semaphore) pour un comportement identique a l'ecran de detail, juste borne aux cartes
 * CarDiagramCard / ExtraVehiclePhotosCard.
 */
class DossierContributeViewModel(
    private val dossierId: Long,
    private val repository: DossierRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierContributeUiState())
    val uiState: StateFlow<DossierContributeUiState> = _uiState

    init { loadDocuments() }

    fun loadDocuments() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDocuments = true)
            repository.getDocuments(dossierId)
                .onSuccess { docs -> _uiState.value = _uiState.value.copy(documents = docs, isLoadingDocuments = false) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoadingDocuments = false,
                        error = "Impossible de charger les documents."
                    )
                }
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
