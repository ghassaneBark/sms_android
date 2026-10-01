package com.ma.sms.android.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ma.sms.android.data.local.PendingUpload
import com.ma.sms.android.data.model.AgentTerrainUser
import com.ma.sms.android.data.model.Devis
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.model.DossierMessage
import com.ma.sms.android.data.model.PhotoRetakeRequest
import com.ma.sms.android.data.repository.DossierRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

private const val MAX_CONCURRENT_PHOTO_UPLOADS = 4

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
    val lastValidDevis: Devis? = null,
    val agentTerrainUsers: List<AgentTerrainUser> = emptyList(),
    val isLoadingAgentTerrainUsers: Boolean = false,
    val isReassigning: Boolean = false,
    val isDownloading: Boolean = false,
    val fileToOpen: FileToOpen? = null,
    // Demande de reprise photo encore en attente pour la phase courante (resolvedAt == null et
    // phase == dossier.etat) : pilote le bandeau affiche a l'agent terrain et la restriction de
    // la capture a "photos supplémentaires" uniquement. Null si aucune demande en attente.
    val pendingPhotoRetakeRequest: PhotoRetakeRequest? = null,
    // Fil de messages du dossier (meme chat que celui deja affiche sur le web) : permet au
    // responsable de laisser une instruction a l'agent terrain au moment de l'affectation (ou a
    // tout autre moment), visible ici des l'ouverture du dossier.
    val messages: List<DossierMessage> = emptyList(),
    val isLoadingMessages: Boolean = false,
    val isSendingMessage: Boolean = false
)

data class FileToOpen(val uri: Uri, val mimeType: String)

class DossierDetailViewModel(
    private val dossierId: Long,
    private val repository: DossierRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DossierDetailUiState())
    val uiState: StateFlow<DossierDetailUiState> = _uiState

    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack

    // Dernier instantane des lignes Room observees (pour retrouver l'id lors d'une suppression ou
    // d'un upload manuel) ; le UiState continue d'exposer pendingPhotos comme avant (List<PendingPhoto>,
    // simple mapping file+docType) pour ne rien changer au contrat des Composables existants.
    private var pendingUploads: List<PendingUpload> = emptyList()

    init {
        load()
        // Remplace l'ancienne liste en memoire : la file d'attente est maintenant durable (Room),
        // donc survit a un kill du process. SyncWorker (WorkManager) la vide en arriere-plan des
        // que la connectivite le permet, meme sans action de l'utilisateur.
        viewModelScope.launch {
            repository.observePendingUploads(dossierId).collect { uploads ->
                pendingUploads = uploads
                _uiState.value = _uiState.value.copy(
                    pendingPhotos = uploads.map { PendingPhoto(File(it.localFilePath), it.documentType) }
                )
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getDossier(dossierId)
                .onSuccess { dossier ->
                    _uiState.value = _uiState.value.copy(dossier = dossier, isLoading = false)
                    loadDocuments()
                    loadPendingPhotoRetakeRequest(dossier.etat)
                    loadMessages()
                    // En cours/après réparation : charger le dernier devis VALIDE pour l'afficher en mode light
                    if (dossier.etat == "ATTENTE_EXPERTISE_SR" || dossier.etat == "ATTENTE_PHOTO_FIN_REPARATION") {
                        loadLastValidDevis()
                    }
                }
                .onFailure {
                    // Hors ligne (ou reseau indisponible) : on retombe sur la derniere version connue
                    // en cache plutot que de bloquer completement l'ecran — l'agent doit pouvoir
                    // continuer a prendre des photos sur un dossier deja vu meme sans reseau.
                    val cached = repository.getCachedDossier(dossierId)
                    if (cached != null) {
                        _uiState.value = _uiState.value.copy(
                            dossier = cached,
                            isLoading = false,
                            error = "Hors ligne — données peut-être non à jour."
                        )
                    } else {
                        // Diagnostic temporaire : affiche l'etat du cache pour comprendre pourquoi
                        // le repli hors ligne echoue (a retirer une fois le bug identifie).
                        val debug = repository.debugCacheSnapshot(dossierId)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Impossible de charger le dossier. [$debug]"
                        )
                    }
                }
        }
    }

    fun loadDocuments() {
        viewModelScope.launch {
            repository.getDocuments(dossierId)
                .onSuccess { docs -> _uiState.value = _uiState.value.copy(documents = docs) }
        }
    }

    // Cherche, dans l'historique complet des demandes de reprise photo, celle encore en attente
    // (resolvedAt == null) pour la phase courante du dossier — valable sur les 3 phases agent
    // terrain (avant/en cours/apres reparation), pas seulement "avant reparation".
    fun loadPendingPhotoRetakeRequest(currentEtat: String?) {
        viewModelScope.launch {
            repository.getPhotoRetakeRequests(dossierId)
                .onSuccess { requests ->
                    val pending = requests.firstOrNull { it.resolvedAt == null && it.phase == currentEtat }
                    _uiState.value = _uiState.value.copy(pendingPhotoRetakeRequest = pending)
                }
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMessages = true)
            repository.getDossierMessages(dossierId)
                .onSuccess { messages ->
                    _uiState.value = _uiState.value.copy(messages = messages, isLoadingMessages = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoadingMessages = false)
                }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingMessage = true, error = null)
            repository.postDossierMessage(dossierId, text.trim())
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSendingMessage = false)
                    loadMessages()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSendingMessage = false,
                        error = "Impossible d'envoyer le message."
                    )
                }
        }
    }

    fun loadLastValidDevis() {
        viewModelScope.launch {
            repository.getLastValidDevis(dossierId)
                .onSuccess { devis -> _uiState.value = _uiState.value.copy(lastValidDevis = devis) }
        }
    }

    // Met la photo en file d'attente durable (Room) : le fichier est deja sur disque (cacheDir),
    // cet appel garantit qu'il finira par etre televerse (SyncWorker) meme si l'app est tuee avant
    // que l'utilisateur ne tape "Enregistrer". Declenche aussi une tentative immediate si en ligne.
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

    // Upload immediat de toutes les photos en attente, en parallele (concurrence limitee) : sert
    // de retour visuel explicite quand l'utilisateur tape "Enregistrer" pendant qu'il est en ligne.
    // Ce n'est plus le SEUL chemin d'upload : SyncWorker retentera automatiquement en arriere-plan
    // toute ligne encore en attente (echec ici, ou app fermee avant meme d'appuyer sur le bouton).
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
                        // En cas d'echec : on ne touche pas a la ligne Room, elle reste PENDING et
                        // sera reprise par SyncWorker (pas besoin de dupliquer sa logique de retry ici).
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

    // Modifiable uniquement a l'etat AFFECTATION_AGENT_TERRAIN (pilote cote backend le routage
    // EN_ATTENTE_ACCORD_FORFAIT vs EN_ATTENTE_ACCORD a la fin de cette phase, cf.
    // DossierWorkflowService.autoSkipAccordForfaitIfNeeded). Mise a jour optimiste + PUT complet
    // du dossier, meme pattern que DossierExpressViewModel.saveProgress().
    fun setEligiblePourForfait(value: Boolean) {
        val current = _uiState.value.dossier ?: return
        if (current.etat != "AFFECTATION_AGENT_TERRAIN") return
        val updated = current.copy(eligiblePourForfait = value)
        _uiState.value = _uiState.value.copy(dossier = updated)
        viewModelScope.launch {
            repository.updateDossier(dossierId, updated)
                .onSuccess { saved -> _uiState.value = _uiState.value.copy(dossier = saved) }
                .onFailure {
                    // Revert optimiste + message : pas de queue offline pour ce champ, il faut une
                    // reponse serveur immediate (le routage d'etat en depend).
                    _uiState.value = _uiState.value.copy(
                        dossier = current,
                        error = "Impossible de mettre à jour l'éligibilité au forfait."
                    )
                }
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

    // Charge la liste des agents terrain de l'antenne du dossier (pour le selecteur de reaffectation)
    fun loadAgentTerrainUsers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingAgentTerrainUsers = true)
            repository.getAgentTerrainUsers(_uiState.value.dossier?.antenne?.id)
                .onSuccess { users ->
                    _uiState.value = _uiState.value.copy(
                        agentTerrainUsers = users,
                        isLoadingAgentTerrainUsers = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoadingAgentTerrainUsers = false,
                        error = "Impossible de charger la liste des agents terrain."
                    )
                }
        }
    }

    fun reassignAgentTerrain(newAgentTerrainUserId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isReassigning = true, error = null)
            repository.reassignAgentTerrain(dossierId, newAgentTerrainUserId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isReassigning = false)
                    _navigateBack.emit(Unit)
                }
                .onFailure {
                    val msg = it.message?.takeIf { m -> m.isNotBlank() }
                        ?: "Impossible de réaffecter le dossier."
                    _uiState.value = _uiState.value.copy(isReassigning = false, error = msg)
                }
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

    // Telecharge le PDF "Accord sur devis" et demande son ouverture.
    fun viewDevisPdf(devis: Devis) {
        val devisId = devis.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true, error = null)
            repository.downloadDevisPdf(devisId)
                .onSuccess { uri ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        fileToOpen = FileToOpen(uri, "application/pdf")
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        error = "Impossible d'ouvrir l'accord sur devis."
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
