package com.ma.sms.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.repository.DossierRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import retrofit2.HttpException

// Etats a exclure de la recherche cote client. Le filtrage principal (exclure les dossiers deja
// clos) se fait desormais cote backend (scope "all_active_readonly", cf. DossierRepository) — ce
// filtre reste ici en filet de securite au cas ou une regle plus fine soit necessaire plus tard.
val EXCLUDED_STATES = emptySet<String>()

fun contributableStateLabel(etat: String?): String = when (etat) {
    "ATTENTE_EXPERTISE_SR" -> "En cours de réparation"
    "ATTENTE_PHOTO_FIN_REPARATION" -> "Après réparation"
    else -> etat ?: "-"
}

// Filtre client-side (immatriculation ou reference) : ne renvoie rien tant que l'agent n'a pas
// commence a taper, pour eviter d'afficher d'emblee tous les dossiers de l'antenne (liste
// potentiellement longue et sans interet avant qu'il precise ce qu'il cherche).
fun filterDossiers(dossiers: List<Dossier>, query: String): List<Dossier> {
    val q = query.trim()
    if (q.isBlank()) return emptyList()
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
        // Affiche immediatement le cache local (y compris 100% hors ligne), puis se tient a jour
        // a chaque ecriture du cache (cacheAntenneDossierList, appelee ci-dessous apres chaque
        // fetch reseau reussi) — meme principe que DossierListViewModel pour "mes dossiers".
        viewModelScope.launch {
            repository.observeCachedAntenneDossierList().collect { cached ->
                val eligible = cached.filter { it.etat !in EXCLUDED_STATES }
                _uiState.value = _uiState.value.copy(eligibleDossiers = eligible)
            }
        }
        loadDossiers()
    }

    fun loadDossiers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.searchAllDossiersInAntenne()
                .onSuccess { dossiers ->
                    repository.cacheAntenneDossierList(dossiers)
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                .onFailure { throwable ->
                    // La permission dossiers.view_all_readonly peut ne pas encore etre accordee au
                    // role AGENT_TERRAIN : message dedie plutot que l'erreur HTTP brute.
                    val message = if (throwable is HttpException && throwable.code() == 403) {
                        "Vous n'avez pas la permission de rechercher les dossiers d'un autre agent. Contactez un administrateur."
                    } else {
                        throwable.message?.takeIf { it.isNotBlank() } ?: "Impossible de charger les dossiers."
                    }
                    // Pas d'ecran d'erreur bloquant si on a deja quelque chose en cache a montrer.
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = if (_uiState.value.eligibleDossiers.isEmpty()) message else null
                    )
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
