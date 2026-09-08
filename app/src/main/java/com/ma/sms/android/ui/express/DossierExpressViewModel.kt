package com.ma.sms.android.ui.express

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.AccordForfait
import com.ma.sms.android.data.model.Assurance
import com.ma.sms.android.data.model.Assure
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.model.DossierExpressCreateRequest
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.model.Vehicule
import com.ma.sms.android.data.repository.DossierRepository
import com.ma.sms.android.ui.detail.PendingPhoto
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
private const val MAX_ADVANCE_STATE_ITERATIONS = 10

// --- Types de documents obligatoires avant de pouvoir proposer un forfait (cf. contrat backend) ---
const val DOC_TYPE_PHOTO_VEHICULE = "Photos vehicules avant reparation"
const val DOC_TYPE_CARTE_GRISE = "Carte grise"
const val DOC_TYPE_ATTESTATION_ASSURANCE = "Attestation assurance"
const val DOC_TYPE_GARANTIE = "Garantie"

val JUSTIFICATIF_TYPES = listOf(
    "Déclaration sur l'honneur",
    "Constat amiable",
    "Déclaration des autorités de sécurité",
    "Déclaration des autorités de sécurité - PV",
    "Déclaration des autorités de sécurité - Récépissé"
)
const val DEFAULT_JUSTIFICATIF_TYPE = "Déclaration sur l'honneur"

val EXPRESS_STATES_BEFORE_FORFAIT = setOf(
    "BROUILLON",
    "EN_ATTENTE_AFFECTATION",
    "AFFECTATION_AGENT_TERRAIN",
    "EN_ATTENTE_COMPLEMENT_DOCUMENT"
)

data class DossierExpressUiState(
    // Etape A : formulaire assure / vehicule / assurance
    val nom: String = "",
    val prenom: String = "",
    val telephone: String = "",
    val email: String = "",
    val intermediaire: String = "",
    val immatriculation: String = "",
    val marque: String = "",
    val modele: String = "",
    val numeroChassis: String = "",
    val assurances: List<Assurance> = emptyList(),
    val isLoadingAssurances: Boolean = false,
    val selectedAssuranceId: Long? = null,
    val justificatifType: String = DEFAULT_JUSTIFICATIF_TYPE,

    // Etape B : documents (photos en attente d'envoi + deja envoyes une fois le dossier cree)
    val pendingPhotos: List<PendingPhoto> = emptyList(),
    val documents: List<DocumentSinistre> = emptyList(),

    // Dossier cree/en cours (source de verite une fois la creation faite)
    val dossier: Dossier? = null,

    val isSaving: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,

    // Etape C : proposition de forfait
    val montantForfait: String = "",
    val reponseAssureForfait: Boolean? = null,
    val isSubmittingForfait: Boolean = false,
    val forfaitResult: String? = null
)

class DossierExpressViewModel(
    private val repository: DossierRepository,
    private val agentTerrainUserId: String?
) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierExpressUiState())
    val uiState: StateFlow<DossierExpressUiState> = _uiState

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished

    init {
        loadAssurances()
    }

    fun loadAssurances() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingAssurances = true)
            repository.listAssurances()
                .onSuccess { list ->
                    _uiState.value = _uiState.value.copy(assurances = list, isLoadingAssurances = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoadingAssurances = false,
                        error = "Impossible de charger la liste des assurances."
                    )
                }
        }
    }

    // --- Champs du formulaire ---
    fun updateNom(v: String) { _uiState.value = _uiState.value.copy(nom = v) }
    fun updatePrenom(v: String) { _uiState.value = _uiState.value.copy(prenom = v) }
    fun updateTelephone(v: String) { _uiState.value = _uiState.value.copy(telephone = v) }
    fun updateEmail(v: String) { _uiState.value = _uiState.value.copy(email = v) }
    fun updateIntermediaire(v: String) { _uiState.value = _uiState.value.copy(intermediaire = v) }
    fun updateImmatriculation(v: String) { _uiState.value = _uiState.value.copy(immatriculation = v) }
    fun updateMarque(v: String) { _uiState.value = _uiState.value.copy(marque = v) }
    fun updateModele(v: String) { _uiState.value = _uiState.value.copy(modele = v) }
    fun updateNumeroChassis(v: String) { _uiState.value = _uiState.value.copy(numeroChassis = v) }
    fun selectAssurance(id: Long) { _uiState.value = _uiState.value.copy(selectedAssuranceId = id) }
    fun selectJustificatifType(type: String) { _uiState.value = _uiState.value.copy(justificatifType = type) }
    fun updateMontantForfait(v: String) { _uiState.value = _uiState.value.copy(montantForfait = v) }
    fun selectReponseAssureForfait(accepted: Boolean) { _uiState.value = _uiState.value.copy(reponseAssureForfait = accepted) }

    fun addPendingPhoto(file: File, docType: String) {
        val current = _uiState.value.pendingPhotos.toMutableList()
        current.add(PendingPhoto(file, docType))
        _uiState.value = _uiState.value.copy(pendingPhotos = current)
    }

    fun removePendingPhoto(index: Int) {
        val current = _uiState.value.pendingPhotos.toMutableList()
        current.getOrNull(index)?.file?.delete()
        current.removeAt(index)
        _uiState.value = _uiState.value.copy(pendingPhotos = current)
    }

    fun dismissMessages() {
        _uiState.value = _uiState.value.copy(error = null, statusMessage = null, forfaitResult = null)
    }

    /**
     * "Enregistrement intermediaire" : cree le dossier si necessaire, envoie les photos en attente,
     * puis tente d'avancer l'etat autant que possible. Chaque etape peut echouer normalement
     * (pieces manquantes, etc) sans que ce soit une erreur fatale : le dossier reste sauvegarde
     * a l'etat atteint.
     */
    fun saveProgress() {
        val state = _uiState.value
        if (state.dossier == null) {
            if (state.telephone.isBlank() || state.intermediaire.isBlank() || state.selectedAssuranceId == null) {
                _uiState.value = state.copy(
                    error = "Téléphone, intermédiaire et assurance sont obligatoires pour créer le dossier."
                )
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, statusMessage = null)

            var current = _uiState.value.dossier
            if (current == null) {
                val request = buildCreateRequest(_uiState.value)
                val created = repository.createDossierExpress(request)
                created.onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = it.message?.takeIf { m -> m.isNotBlank() } ?: "Impossible de créer le dossier."
                    )
                }
                if (created.isFailure) return@launch
                current = created.getOrNull()
                _uiState.value = _uiState.value.copy(dossier = current)
            }

            val dossierId = current?.id
            if (dossierId == null) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = "Dossier invalide.")
                return@launch
            }

            val uploadFailures = uploadPendingPhotos(dossierId)
            refreshDocuments(dossierId)

            val advanceMessage = advanceStateAsFarAsPossible(dossierId)

            val summary = buildString {
                append("Dossier enregistré (réf. ${_uiState.value.dossier?.reference ?: dossierId})")
                if (uploadFailures > 0) append(" — $uploadFailures photo(s) non envoyée(s)")
                append(". ")
                append(advanceMessage)
            }
            _uiState.value = _uiState.value.copy(isSaving = false, statusMessage = summary)
        }
    }

    private suspend fun uploadPendingPhotos(dossierId: Long): Int {
        val pending = _uiState.value.pendingPhotos
        if (pending.isEmpty()) return 0
        val semaphore = Semaphore(MAX_CONCURRENT_PHOTO_UPLOADS)
        val results = coroutineScopeUploads(pending, dossierId, semaphore)
        val successFiles = mutableSetOf<File>()
        results.forEachIndexed { index, result ->
            if (result.isSuccess) successFiles.add(pending[index].file)
        }
        val remaining = pending.filter { it.file !in successFiles }
        successFiles.forEach { it.delete() }
        _uiState.value = _uiState.value.copy(pendingPhotos = remaining)
        return results.count { it.isFailure }
    }

    private suspend fun coroutineScopeUploads(
        pending: List<PendingPhoto>,
        dossierId: Long,
        semaphore: Semaphore
    ) = kotlinx.coroutines.coroutineScope {
        pending.map { photo ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    repository.uploadPhoto(dossierId, photo.file, photo.docType)
                }
            }
        }.awaitAll()
    }

    private fun refreshDocuments(dossierId: Long) {
        viewModelScope.launch {
            repository.getDocuments(dossierId).onSuccess { docs ->
                _uiState.value = _uiState.value.copy(documents = docs)
            }
        }
    }

    /** Boucle d'avancement d'etat : s'arrete sur echec (piece/agent manquant), sur EN_ATTENTE_ACCORD_FORFAIT, ou apres 10 tentatives. */
    private suspend fun advanceStateAsFarAsPossible(dossierId: Long): String {
        var iterations = 0
        while (iterations < MAX_ADVANCE_STATE_ITERATIONS) {
            iterations++
            val result = repository.advanceState(dossierId)
            val advanced = result.getOrNull()
            if (advanced != null) {
                _uiState.value = _uiState.value.copy(dossier = advanced)
                if (advanced.etat == "EN_ATTENTE_ACCORD_FORFAIT") {
                    return "Prêt pour la proposition de forfait."
                }
                continue
            }
            val msg = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                ?: "Impossible de faire avancer l'état du dossier."
            return "Étape actuelle : ${_uiState.value.dossier?.etat ?: "?"}. $msg"
        }
        return "Étape actuelle : ${_uiState.value.dossier?.etat ?: "?"}."
    }

    private fun buildCreateRequest(state: DossierExpressUiState): DossierExpressCreateRequest {
        val assure = Assure(
            nom = state.nom.ifBlank { null },
            prenom = state.prenom.ifBlank { null },
            telephone = state.telephone.ifBlank { null },
            email = state.email.ifBlank { null },
            adresse = null,
            type = null,
            intermediaire = state.intermediaire.ifBlank { null }
        )
        val vehicule = if (listOf(state.immatriculation, state.marque, state.modele, state.numeroChassis).any { it.isNotBlank() }) {
            Vehicule(
                immatriculation = state.immatriculation.ifBlank { null },
                marque = state.marque.ifBlank { null },
                modele = state.modele.ifBlank { null },
                usage = null,
                adresse = null,
                numeroChassis = state.numeroChassis.ifBlank { null }
            )
        } else null
        return DossierExpressCreateRequest(
            assure = assure,
            assurance = Assurance(id = state.selectedAssuranceId ?: 0L, nom = null),
            vehiculeAssure = vehicule,
            agentTerrainUserId = agentTerrainUserId,
            agentTerrainUserErId = null
        )
    }

    /**
     * "Fin de mission" (etapes 4 a 7) : propose le forfait, avance l'etat, enregistre la reponse
     * de l'assure puis avance a nouveau. Un refus n'est pas une erreur : le dossier repasse a
     * EN_ATTENTE_ACCORD_FORFAIT et l'agent peut retenter avec un nouveau montant.
     */
    fun submitForfait() {
        val state = _uiState.value
        val dossier = state.dossier ?: return
        val dossierId = dossier.id
        val montant = state.montantForfait.replace(",", ".").toDoubleOrNull()
        if (montant == null || montant <= 0.0) {
            _uiState.value = state.copy(error = "Montant du forfait invalide.")
            return
        }
        val reponse = state.reponseAssureForfait
        if (reponse == null) {
            _uiState.value = state.copy(error = "Merci d'indiquer si l'assuré a accepté ou refusé le forfait.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmittingForfait = true, error = null, forfaitResult = null)

            // Etape 4 : proposition du montant
            val proposalDossier = _uiState.value.dossier!!.copy(
                accordForfaits = listOf(AccordForfait(typeAccord = "FORFAIT", montantForfait = montant))
            )
            val step4 = repository.updateDossier(dossierId, proposalDossier)
            val afterStep4 = step4.getOrNull()
            if (afterStep4 == null) {
                failForfait(step4.exceptionOrNull(), "Impossible d'enregistrer la proposition de forfait.")
                return@launch
            }
            _uiState.value = _uiState.value.copy(dossier = afterStep4)
            val accordId = afterStep4.accordForfaits?.lastOrNull()?.id

            // Etape 5 : avance vers ATTENTE_REPONSE_ASSURE_FORFAIT
            val step5 = repository.advanceState(dossierId)
            val afterStep5 = step5.getOrNull()
            if (afterStep5 == null) {
                failForfait(step5.exceptionOrNull(), "Impossible de faire avancer le dossier après la proposition.")
                return@launch
            }
            _uiState.value = _uiState.value.copy(dossier = afterStep5)

            // Etape 6 : enregistrement de la reponse de l'assure (reponseAssureForfait doit etre
            // porte par l'entree AccordForfait elle-meme, pas par le Dossier : c'est le champ que
            // le backend lit reellement, cf. AccordForfaitDto cote serveur).
            val responseDossier = afterStep5.copy(
                accordForfaits = listOf(
                    AccordForfait(id = accordId, typeAccord = "FORFAIT", montantForfait = montant, reponseAssureForfait = reponse)
                )
            )
            val step6 = repository.updateDossier(dossierId, responseDossier)
            val afterStep6 = step6.getOrNull()
            if (afterStep6 == null) {
                failForfait(step6.exceptionOrNull(), "Impossible d'enregistrer la réponse de l'assuré.")
                return@launch
            }
            _uiState.value = _uiState.value.copy(dossier = afterStep6)

            // Etape 7 : routage final
            val step7 = repository.advanceState(dossierId)
            val afterStep7 = step7.getOrNull()
            if (afterStep7 == null) {
                failForfait(step7.exceptionOrNull(), "Impossible de finaliser le dossier.")
                return@launch
            }
            _uiState.value = _uiState.value.copy(dossier = afterStep7, isSubmittingForfait = false)

            if (afterStep7.etat == "FORFAIT_ACCEPTE") {
                _uiState.value = _uiState.value.copy(forfaitResult = "Forfait accepté. Dossier finalisé.")
                _finished.emit(Unit)
            } else {
                _uiState.value = _uiState.value.copy(
                    forfaitResult = "Forfait refusé par l'assuré. Vous pouvez proposer un nouveau montant.",
                    montantForfait = "",
                    reponseAssureForfait = null
                )
            }
        }
    }

    private fun failForfait(throwable: Throwable?, fallback: String) {
        val msg = throwable?.message?.takeIf { it.isNotBlank() } ?: fallback
        _uiState.value = _uiState.value.copy(isSubmittingForfait = false, error = msg)
    }
}
