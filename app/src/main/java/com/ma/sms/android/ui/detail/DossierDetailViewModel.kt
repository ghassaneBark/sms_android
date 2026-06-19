package com.ma.sms.android.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.Devis
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.repository.DossierRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

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
    val lastValidDevis: Devis? = null
)

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
                    // En ATTENTE_EXPERTISE_SR : charger le dernier devis VALIDE pour l'afficher en mode light
                    if (dossier.etat == "ATTENTE_EXPERTISE_SR") {
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

    // Upload de toutes les photos en attente
    fun validateAndUpload() {
        val pending = _uiState.value.pendingPhotos
        if (pending.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, error = null)
            var failed = 0
            pending.forEach { photo ->
                repository.uploadPhoto(dossierId, photo.file, photo.docType)
                    .onSuccess { photo.file.delete() }
                    .onFailure { failed++ }
            }
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

    fun showError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun dismissMessages() {
        _uiState.value = _uiState.value.copy(error = null, uploadSuccess = null)
    }
}
