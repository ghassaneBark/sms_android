package com.ma.sms.android.ui.dossiers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.repository.DossierRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DossierListUiState(
    val dossiers: List<Dossier> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

class DossierListViewModel(private val repository: DossierRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierListUiState())
    val uiState: StateFlow<DossierListUiState> = _uiState

    init {
        loadDossiers()
    }

    fun loadDossiers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getDossiers()
                .onSuccess { dossiers ->
                    _uiState.value = _uiState.value.copy(
                        dossiers = dossiers,
                        isLoading = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Impossible de charger les dossiers : ${it.message}"
                    )
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            repository.getDossiers()
                .onSuccess { dossiers ->
                    _uiState.value = _uiState.value.copy(
                        dossiers = dossiers,
                        isRefreshing = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = "Erreur lors du rafraîchissement."
                    )
                }
        }
    }
}
