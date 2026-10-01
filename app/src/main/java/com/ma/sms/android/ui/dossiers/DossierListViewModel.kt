package com.ma.sms.android.ui.dossiers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.repository.DossierRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class DossierListUiState(
    val dossiers: List<Dossier> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val lastSyncedAt: Long? = null
)

class DossierListViewModel(private val repository: DossierRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierListUiState())
    val uiState: StateFlow<DossierListUiState> = _uiState

    init {
        // Affiche immediatement le cache local (y compris 100% hors ligne, ou pendant le premier
        // chargement reseau), puis se tient a jour a chaque ecriture du cache (cacheDossierList,
        // appelee ci-dessous apres chaque fetch reseau reussi).
        viewModelScope.launch {
            repository.observeCachedDossierList().collect { cached ->
                _uiState.value = _uiState.value.copy(dossiers = cached)
            }
        }
        loadDossiers()
    }

    fun loadDossiers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getDossiers()
                .onSuccess { dossiers ->
                    repository.cacheDossierList(dossiers)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        lastSyncedAt = repository.getLastSyncedAt()
                    )
                }
                .onFailure {
                    // Pas d'ecran d'erreur bloquant si on a deja quelque chose en cache a montrer :
                    // l'agent terrain doit pouvoir continuer a travailler hors connexion.
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        lastSyncedAt = repository.getLastSyncedAt(),
                        error = if (_uiState.value.dossiers.isEmpty())
                            "Impossible de charger les dossiers : ${it.message}"
                        else null
                    )
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            repository.getDossiers()
                .onSuccess { dossiers ->
                    repository.cacheDossierList(dossiers)
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        lastSyncedAt = repository.getLastSyncedAt()
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = if (_uiState.value.dossiers.isEmpty()) "Erreur lors du rafraîchissement." else null
                    )
                }
        }
    }
}
