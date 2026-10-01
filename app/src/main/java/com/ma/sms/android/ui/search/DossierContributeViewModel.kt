package com.ma.sms.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.local.PendingUpload
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.repository.DossierRepository
import com.ma.sms.android.ui.detail.FileToOpen
import com.ma.sms.android.ui.detail.PendingPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
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

    private var pendingUploads: List<PendingUpload> = emptyList()

    init {
        loadDocuments()
        viewModelScope.launch {
            repository.observePendingUploads(dossierId).collect { uploads ->
                pendingUploads = uploads
                _uiState.value = _uiState.value.copy(
                    pendingPhotos = uploads.map { PendingPhoto(File(it.localFilePath), it.documentType) }
                )
            }
        }
    }

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

    // Met la photo en file d'attente durable (Room) : upload garanti (SyncWorker) meme si l'app
    // est tuee avant l'upload manuel.
    fun addPendingPhoto(file: File, docType: String) {
        viewModelScope.launch {
            repository.queuePendingUpload(dossierId, file, docType)
        }
    }

    // Supprime une photo en attente (fichier local + ligne Room)
    fun removePendingPhoto(index: Int) {
        val upload = pendingUploads.getOrNull(index) ?: return
        viewModelScope.launch {
            File(upload.localFilePath).delete()
            repository.deletePendingUpload(upload.id)
        }
    }

    // Upload immediat de toutes les photos en attente, en parallele (concurrence limitee) : retour
    // visuel explicite ; SyncWorker reste le filet de securite en arriere-plan en cas d'echec ici.
    fun validateAndUpload() {
        val pending = pendingUploads
        if (pending.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, error = null)
            // Confirme la file d'attente AVANT la tentative immediate : si celle-ci echoue faute
            // de reseau, SyncWorker saura qu'il est autorise a reprendre ces photos tout seul.
            repository.confirmPendingUploads(dossierId)
            val semaphore = Semaphore(MAX_CONCURRENT_PHOTO_UPLOADS)
            val results = pending.map { upload ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        repository.uploadPhoto(dossierId, File(upload.localFilePath), upload.documentType)
                            .onSuccess {
                                File(upload.localFilePath).delete()
                                repository.deletePendingUpload(upload.id)
                            }
                    }
                }
            }.awaitAll()
            val failed = results.filter { it.isFailure }
            // Un echec par simple absence de reseau n'est pas une vraie erreur : la photo reste en
            // file d'attente durable (Room) et SyncWorker la renverra automatiquement des que la
            // connexion revient, sans intervention de l'agent.
            val networkFailed = failed.count { it.exceptionOrNull() is java.io.IOException }
            val otherFailed = failed.size - networkFailed
            when {
                failed.isEmpty() -> _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadSuccess = "${pending.size} photo(s) enregistrée(s) avec succès."
                )
                otherFailed == 0 -> _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadSuccess = "Hors ligne : $networkFailed photo(s) en attente, envoi automatique dès le retour du réseau."
                )
                else -> _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    error = "$otherFailed photo(s) n'ont pas pu être uploadées."
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
