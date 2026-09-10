package com.ma.sms.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.repository.DossierRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

// Etats agent terrain pour lesquels un agent non assigne peut consulter et contribuer des photos
// (cf. contexte : dossier "en cours de reparation" ou "apres reparation" repere par un autre agent).
val CONTRIBUTABLE_STATES = setOf("ATTENTE_EXPERTISE_SR", "ATTENTE_PHOTO_FIN_REPARATION")

fun contributableStateLabel(etat: String?): String = when (etat) {
    "ATTENTE_EXPERTISE_SR" -> "En cours de réparation"
    "ATTENTE_PHOTO_FIN_REPARATION" -> "Après réparation"
    else -> etat ?: "-"
}

/** Filtre client-side (immatriculation ou reference) sur une liste deja restreinte aux etats eligibles. */
fun filterDossiers(dossiers: List<Dossier>, query: String): List<Dossier> {
    val q = query.trim()
    if (q.isBlank()) return dossiers
    return dossiers.filter { dossier ->
        dossier.reference?.contains(q, ignoreCase = true) == true ||
            dossier.vehiculeAssure?.immatriculation?.contains(q, ignoreCase = true) == true
    }
}

data class DossierSearchUiState(
    val query: String = "",
    val eligibleDossiers: List<Dossier> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedDossier: Dossier? = null
)

class DossierSearchViewModel(private val repository: DossierRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierSearchUiState())
    val uiState: StateFlow<DossierSearchUiState> = _uiState

    init {
        loadDossiers()
    }

    fun loadDossiers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.searchAllDossiersInAntenne()
                .onSuccess { dossiers ->
                    val eligible = dossiers.filter { it.etat in CONTRIBUTABLE_STATES }
                    _uiState.value = _uiState.value.copy(eligibleDossiers = eligible, isLoading = false)
                }
                .onFailure { throwable ->
                    // La permission dossiers.view_all_readonly peut ne pas encore etre accordee au
                    // role AGENT_TERRAIN : message dedie plutot que l'erreur HTTP brute.
                    val message = if (throwable is HttpException && throwable.code() == 403) {
                        "Vous n'avez pas la permission de rechercher les dossiers d'un autre agent. Contactez un administrateur."
                    } else {
                        throwable.message?.takeIf { it.isNotBlank() } ?: "Impossible de charger les dossiers."
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, error = message)
                }
        }
    }

    fun updateQuery(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }

    fun selectDossier(dossier: Dossier) {
        _uiState.value = _uiState.value.copy(selectedDossier = dossier)
    }
}
