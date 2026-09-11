package com.ma.sms.android.ui.express

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.model.AccordForfait
import com.ma.sms.android.data.model.Assurance
import com.ma.sms.android.data.model.Assure
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.model.DossierExpressCreateRequest
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.model.Intermediaire
import com.ma.sms.android.data.model.Vehicule
import com.ma.sms.android.data.repository.DossierRepository
import com.ma.sms.android.ui.detail.FileToOpen
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
// Photos vehicules : desormais gerees via le vrai diagramme (CarDiagramCard, VEHICLE_ANGLES,
// allRequiredAnglesDoneForState), donc plus de constante de type generique ici.
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
    "EN_ATTENTE_AFFECTATION"
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
    val intermediaires: List<Intermediaire> = emptyList(),
    val isLoadingIntermediaires: Boolean = false,
    val justificatifType: String = DEFAULT_JUSTIFICATIF_TYPE,

    // Etape B : documents (photos en attente d'envoi + deja envoyes une fois le dossier cree)
    val pendingPhotos: List<PendingPhoto> = emptyList(),
    val documents: List<DocumentSinistre> = emptyList(),

    // Dossier cree/en cours (source de verite une fois la creation faite)
    val dossier: Dossier? = null,

    val isSaving: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,
    val fileToOpen: FileToOpen? = null,
    val isDownloading: Boolean = false,

    // Etape C : proposition de forfait. Bascule locale (pas derivee de l'etat backend) : dans
    // TRAITEMENT_DOSSIER_EXPRESS, mission terrain et forfait partagent le meme etat, donc c'est
    // ce flag qui decide quelle section afficher, une fois les pieces requises televersees
    // (voir requiredExpressDocumentsUploaded dans DossierExpressScreen.kt).
    val showForfaitSection: Boolean = false,
    val montantForfait: String = "",
    val reponseAssureForfait: Boolean? = null,
    val isSubmittingForfait: Boolean = false,
    val forfaitResult: String? = null,

    // Bascule locale (pas derivee de l'etat backend) : permet a l'agent de revenir editer
    // assure/vehicule a tout moment avant "Fin de mission", meme une fois le dossier passe
    // en TRAITEMENT_DOSSIER_EXPRESS (voir PhotosAvantReparationSection / AssureVehiculeFormSection).
    val showAssureVehiculeForm: Boolean = false,

    // Extraction IA (vision) des champs vehicule depuis la carte grise deja televersee.
    val isExtractingVehicule: Boolean = false,

    // Chargement d'un dossier Express deja cree (repris depuis la liste des dossiers, cf.
    // existingDossierId) : tant que c'est en cours, on n'affiche ni le formulaire de creation
    // vide ni les photos, pour eviter un flash incoherent avant que dossier/documents/champs
    // ne soient effectivement charges.
    val isLoadingExisting: Boolean = false
)

class DossierExpressViewModel(
    private val repository: DossierRepository,
    private val agentTerrainUserId: String?,
    private val existingDossierId: Long? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierExpressUiState())
    val uiState: StateFlow<DossierExpressUiState> = _uiState

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished

    init {
        loadAssurances()
        loadIntermediaires()
        if (existingDossierId != null) {
            loadExistingDossier(existingDossierId)
        }
    }

    /**
     * Reprend un dossier Express deja cree (ex: l'agent a quitte l'app puis rouvert le dossier
     * depuis la liste) : recharge le dossier + ses documents et repeuple tous les champs du
     * formulaire assure/vehicule depuis les donnees deja enregistrees, pour que "Modifier
     * assure / vehicule" et la section photos redeviennent accessibles comme avant.
     */
    private fun loadExistingDossier(dossierId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingExisting = true, error = null)
            val dossierResult = repository.getDossier(dossierId)
            val dossier = dossierResult.getOrNull()
            if (dossier == null) {
                _uiState.value = _uiState.value.copy(
                    isLoadingExisting = false,
                    error = dossierResult.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                        ?: "Impossible de charger le dossier."
                )
                return@launch
            }
            val documents = repository.getDocuments(dossierId).getOrDefault(emptyList())
            val assure = dossier.assure
            val vehicule = dossier.vehiculeAssure
            _uiState.value = _uiState.value.copy(
                isLoadingExisting = false,
                dossier = dossier,
                documents = documents,
                nom = assure?.nom.orEmpty(),
                prenom = assure?.prenom.orEmpty(),
                telephone = assure?.telephone.orEmpty(),
                email = assure?.email.orEmpty(),
                intermediaire = assure?.intermediaire.orEmpty(),
                selectedAssuranceId = dossier.assurance?.id,
                immatriculation = vehicule?.immatriculation.orEmpty(),
                marque = vehicule?.marque.orEmpty(),
                modele = vehicule?.modele.orEmpty(),
                numeroChassis = vehicule?.numeroChassis.orEmpty()
            )
        }
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

    fun loadIntermediaires() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingIntermediaires = true)
            repository.listIntermediaires()
                .onSuccess { list ->
                    _uiState.value = _uiState.value.copy(intermediaires = list, isLoadingIntermediaires = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoadingIntermediaires = false,
                        error = "Impossible de charger la liste des intermédiaires."
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
    fun continueToForfait() { _uiState.value = _uiState.value.copy(showForfaitSection = true) }
    fun backToDocuments() { _uiState.value = _uiState.value.copy(showForfaitSection = false) }
    fun editAssureVehicule() { _uiState.value = _uiState.value.copy(showAssureVehiculeForm = true) }
    fun backToPhotosFromForm() { _uiState.value = _uiState.value.copy(showAssureVehiculeForm = false) }

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

    /**
     * "Extraire depuis la carte grise" : lit la carte grise deja televersee sur le dossier via
     * l'IA (vision) et pre-remplit les champs vehicule encore vides du formulaire. Ne remplace
     * jamais un champ deja saisi par l'agent (pre-remplissage non destructif, cf. web).
     */
    fun extractFromCarteGrise() {
        val dossierId = _uiState.value.dossier?.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExtractingVehicule = true, error = null, statusMessage = null)
            repository.extractVehiculeFromCarteGrise(dossierId)
                .onSuccess { extraction ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        isExtractingVehicule = false,
                        immatriculation = current.immatriculation.ifBlank { extraction.immatriculation.orEmpty() },
                        marque = current.marque.ifBlank { extraction.marque.orEmpty() },
                        modele = current.modele.ifBlank { extraction.modele.orEmpty() },
                        numeroChassis = current.numeroChassis.ifBlank { extraction.numeroChassis.orEmpty() },
                        statusMessage = "Champs véhicule pré-remplis depuis la carte grise. Vérifiez avant d'enregistrer."
                    )
                }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        isExtractingVehicule = false,
                        error = throwable.message?.takeIf { it.isNotBlank() } ?: "Impossible d'extraire les informations de la carte grise."
                    )
                }
        }
    }

    fun dismissMessages() {
        _uiState.value = _uiState.value.copy(error = null, statusMessage = null, forfaitResult = null)
    }

    fun showError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    // Telecharge une piece jointe et demande son ouverture (visionneuse image/PDF du systeme).
    // Miroir de DossierContributeViewModel.viewDocument, adapte : pas de dossierId injecte au
    // constructeur ici, on lit celui du dossier deja cree (no-op si le dossier n'existe pas encore).
    fun viewDocument(doc: DocumentSinistre) {
        val dossierId = _uiState.value.dossier?.id ?: return
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

    fun deleteDocument(documentId: Long) {
        val dossierId = _uiState.value.dossier?.id ?: return
        viewModelScope.launch {
            repository.deleteDocument(dossierId, documentId)
                .onSuccess { refreshDocuments(dossierId) }
                .onFailure { _uiState.value = _uiState.value.copy(error = "Impossible de supprimer le document.") }
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
            } else {
                // Dossier deja cree : l'agent peut revenir editer assure/vehicule/intermediaire a
                // tout moment avant "Fin de mission" (voir showAssureVehiculeForm) ; il faut donc
                // persister ces edits via PUT avant l'upload des photos / avancement d'etat.
                val updatedDossier = current.copy(
                    assure = buildAssureFromState(_uiState.value),
                    vehiculeAssure = buildVehiculeFromState(_uiState.value)
                )
                val putResult = repository.updateDossier(updatedDossier.id, updatedDossier)
                val afterPut = putResult.getOrNull()
                if (afterPut == null) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = putResult.exceptionOrNull()?.message?.takeIf { m -> m.isNotBlank() }
                            ?: "Impossible d'enregistrer les modifications."
                    )
                    return@launch
                }
                current = afterPut
                _uiState.value = _uiState.value.copy(dossier = current)
            }

            val dossierId = current?.id
            if (dossierId == null) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = "Dossier invalide.")
                return@launch
            }

            val uploadFailures = uploadPendingPhotos(dossierId)
            refreshDocuments(dossierId)

            // Une fois TRAITEMENT_DOSSIER_EXPRESS atteint, "Enregistrer" ne sert qu'a sauvegarder
            // les photos/modifications en cours : on ne retente plus d'avancer l'etat a chaque
            // clic (ca echouait systematiquement avec "Pieces manquantes..." tant que le dossier
            // n'est pas complet, ce qui ressemblait a tort a une erreur alors que l'enregistrement
            // avait bien reussi). Le passage au forfait se fait desormais uniquement via le bouton
            // dedie "Continuer vers le forfait" (deja gate sur les pieces completes cote client).
            val etatAfterSave = _uiState.value.dossier?.etat
            val advanceMessage = if (etatAfterSave == "TRAITEMENT_DOSSIER_EXPRESS") {
                null
            } else {
                advanceStateAsFarAsPossible(dossierId)
            }

            val summary = buildString {
                append("Dossier enregistré (réf. ${_uiState.value.dossier?.reference ?: dossierId})")
                if (uploadFailures > 0) append(" — $uploadFailures photo(s) non envoyée(s)")
                if (advanceMessage != null) {
                    append(". ")
                    append(advanceMessage)
                } else {
                    append(".")
                }
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
                if (advanced.etat == "TRAITEMENT_DOSSIER_EXPRESS") {
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

    private fun buildAssureFromState(state: DossierExpressUiState): Assure = Assure(
        nom = state.nom.ifBlank { null },
        prenom = state.prenom.ifBlank { null },
        telephone = state.telephone.ifBlank { null },
        email = state.email.ifBlank { null },
        adresse = null,
        type = null,
        intermediaire = state.intermediaire.ifBlank { null }
    )

    private fun buildVehiculeFromState(state: DossierExpressUiState): Vehicule? =
        if (listOf(state.immatriculation, state.marque, state.modele, state.numeroChassis).any { it.isNotBlank() }) {
            Vehicule(
                immatriculation = state.immatriculation.ifBlank { null },
                marque = state.marque.ifBlank { null },
                modele = state.modele.ifBlank { null },
                usage = null,
                adresse = null,
                numeroChassis = state.numeroChassis.ifBlank { null }
            )
        } else null

    private fun buildCreateRequest(state: DossierExpressUiState): DossierExpressCreateRequest {
        return DossierExpressCreateRequest(
            reference = generateReference(),
            assure = buildAssureFromState(state),
            assurance = Assurance(id = state.selectedAssuranceId ?: 0L, nom = null),
            vehiculeAssure = buildVehiculeFromState(state),
            agentTerrainUserId = agentTerrainUserId,
            agentTerrainUserErId = null,
            express = true
        )
    }

    /** Meme format que le web (dossier-form.component.ts, generateReference()) : DOS-YYYYMMDD-RRRR. */
    private fun generateReference(): String {
        val now = java.util.Calendar.getInstance()
        val year = now.get(java.util.Calendar.YEAR)
        val month = (now.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
        val day = now.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val random = (0 until 10000).random().toString().padStart(4, '0')
        return "DOS-$year$month$day-$random"
    }

    /**
     * "Terminer mission" : le dossier est dans l'etat unique TRAITEMENT_DOSSIER_EXPRESS, qui
     * couvre a la fois la mission terrain et le forfait. Montant et reponse assure sont saisis
     * ensemble par l'agent (sur place, il a deja les deux en main), donc un seul PUT (une seule
     * entree AccordForfait avec les deux champs) suivi d'un seul advance-state suffit : le
     * backend valide tout d'un coup puis route vers FORFAIT_ACCEPTE (accepte) ou boucle sur
     * TRAITEMENT_DOSSIER_EXPRESS (refuse, l'agent peut alors ressaisir un nouveau montant).
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

            // Proposition + reponse ensemble, dans la meme entree AccordForfait.
            val proposalDossier = dossier.copy(
                accordForfaits = listOf(
                    AccordForfait(typeAccord = "FORFAIT", montantForfait = montant, reponseAssureForfait = reponse)
                )
            )
            val putResult = repository.updateDossier(dossierId, proposalDossier)
            val afterPut = putResult.getOrNull()
            if (afterPut == null) {
                failForfait(putResult.exceptionOrNull(), "Impossible d'enregistrer la proposition de forfait.")
                return@launch
            }
            _uiState.value = _uiState.value.copy(dossier = afterPut)

            // Routage final : accepte -> FORFAIT_ACCEPTE, refuse -> boucle sur TRAITEMENT_DOSSIER_EXPRESS.
            val advanceResult = repository.advanceState(dossierId)
            val afterAdvance = advanceResult.getOrNull()
            if (afterAdvance == null) {
                failForfait(advanceResult.exceptionOrNull(), "Impossible de finaliser le dossier.")
                return@launch
            }
            _uiState.value = _uiState.value.copy(dossier = afterAdvance, isSubmittingForfait = false)

            if (afterAdvance.etat == "FORFAIT_ACCEPTE") {
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
