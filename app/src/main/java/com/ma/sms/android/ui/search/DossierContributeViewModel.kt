package com.ma.sms.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.repository.DossierRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Resultat d'un envoi de photo, affiche brievement a l'agent (succes ou echec). */
data class ContributedPhotoResult(
    val label: String,
    val success: Boolean,
    val message: String? = null
)

data class DossierContributeUiState(
    val isUploading: Boolean = false,
    val lastResult: ContributedPhotoResult? = null
)

/**
 * Ecran "contribution" : un agent non assigne au dossier envoie des photos supplementaires
 * (angle predefini ou "photo libre") pendant les phases en cours/apres reparation, sans prendre
 * possession du dossier. Envoi sequentiel, une photo a la fois (usage occasionnel : pas besoin de
 * la machinerie PendingPhoto/semaphore utilisee pour les envois par lot ailleurs dans l'app).
 */
class DossierContributeViewModel(
    private val dossierId: Long,
    private val repository: DossierRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierContributeUiState())
    val uiState: StateFlow<DossierContributeUiState> = _uiState

    fun uploadPhoto(file: File, documentType: String, label: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, lastResult = null)
            repository.uploadPhoto(dossierId, file, documentType)
                .onSuccess {
                    file.delete()
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        lastResult = ContributedPhotoResult(label = label, success = true)
                    )
                }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        lastResult = ContributedPhotoResult(
                            label = label,
                            success = false,
                            message = throwable.message?.takeIf { it.isNotBlank() } ?: "Échec de l'envoi de la photo."
                        )
                    )
                }
        }
    }

    fun dismissResult() {
        _uiState.value = _uiState.value.copy(lastResult = null)
    }
}
