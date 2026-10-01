package com.ma.sms.android.data.repository

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.ma.sms.android.data.api.ApiService
import com.ma.sms.android.data.local.CachedAntenneDossier
import com.ma.sms.android.data.local.CachedAntenneDossierDao
import com.ma.sms.android.data.local.CachedDossier
import com.ma.sms.android.data.local.CachedDossierDao
import com.ma.sms.android.data.local.PendingUpload
import com.ma.sms.android.data.local.PendingUploadDao
import com.ma.sms.android.data.local.PendingUploadStatus
import com.ma.sms.android.data.model.AgentTerrainUser
import com.ma.sms.android.data.model.Assurance
import com.ma.sms.android.data.model.Devis
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.model.DossierExpressCreateRequest
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.model.DossierMessage
import com.ma.sms.android.data.model.DossierMessageCreateRequest
import com.ma.sms.android.data.model.Intermediaire
import com.ma.sms.android.data.model.PhotoRetakeRequest
import com.ma.sms.android.data.model.ReassignAgentTerrainRequest
import com.ma.sms.android.data.model.VehiculeExtraction
import com.ma.sms.android.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class DossierRepository(
    private val api: ApiService,
    private val context: Context,
    private val pendingUploadDao: PendingUploadDao,
    private val cachedDossierDao: CachedDossierDao,
    private val cachedAntenneDossierDao: CachedAntenneDossierDao
) {

    private val gson = Gson()

    suspend fun getDossiers(): Result<List<Dossier>> = runCatching {
        api.getDossiers()
    }

    /**
     * Recherche tous les dossiers ENCORE ACTIFS de l'antenne de l'agent (pas seulement les
     * siens), en lecture seule. Scope dedie "all_active_readonly" (distinct de "all_readonly"
     * utilise par l'ecran admin web, qui lui reste volontairement sans filtre d'etat) : un
     * dossier deja clos n'a pas d'interet pour "contribuer une photo", et l'antenne peut compter
     * des milliers de dossiers historiques si on ne filtre pas.
     */
    suspend fun searchAllDossiersInAntenne(): Result<List<Dossier>> = runCatching {
        api.getDossiersWithScope("all_active_readonly")
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    suspend fun getDossier(id: Long): Result<Dossier> = runCatching {
        api.getDossier(id)
    }

    suspend fun getDocuments(dossierId: Long): Result<List<DocumentSinistre>> = runCatching {
        api.getDocuments(dossierId)
    }

    /** Cree un nouveau dossier depuis le terrain ("Dossier Express"). */
    suspend fun createDossierExpress(request: DossierExpressCreateRequest): Result<Dossier> = runCatching {
        api.createDossier(request)
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    /** Met a jour le dossier (DTO complet attendu par le backend, ex: proposition/reponse de forfait). */
    suspend fun updateDossier(dossierId: Long, dossier: Dossier): Result<Dossier> = runCatching {
        api.updateDossier(dossierId, dossier)
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    suspend fun listAssurances(): Result<List<Assurance>> = runCatching {
        api.getAssurances()
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    suspend fun listIntermediaires(): Result<List<Intermediaire>> = runCatching {
        api.getIntermediaires()
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    suspend fun uploadPhoto(dossierId: Long, photoFile: File, documentType: String): Result<DocumentSinistre> = runCatching {
        val compressed = compressImage(photoFile)
        // Les types d'angles véhicule sont encodés dans le nom du fichier,
        // le backend reçoit le type autorisé "Photos vehicules avant reparation"
        val (actualType, fileName) = when {
            documentType.startsWith("Photos vehicules - ") -> {
                val angle = slugify(documentType.removePrefix("Photos vehicules - "))
                "Photos vehicules avant reparation" to "vehicule-${angle}_${photoFile.name}"
            }
            documentType.startsWith("PHOTOS EN COURS DE REPARATION - ") -> {
                val angle = slugify(documentType.removePrefix("PHOTOS EN COURS DE REPARATION - "))
                "PHOTOS EN COURS DE REPARATION" to "en-cours-${angle}_${photoFile.name}"
            }
            documentType.startsWith("PHOTO APRES REPARATION - ") -> {
                val angle = slugify(documentType.removePrefix("PHOTO APRES REPARATION - "))
                "PHOTO APRES REPARATION" to "apres-reparation-${angle}_${photoFile.name}"
            }
            else -> documentType to photoFile.name
        }
        val typeBody = actualType.toRequestBody("text/plain".toMediaType())
        val fileBody = compressed.asRequestBody("image/jpeg".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", fileName, fileBody)
        api.uploadDocument(dossierId, typeBody, filePart)
    }

    private fun slugify(text: String): String = text.lowercase()
        .replace(" ", "-")
        .replace("é", "e").replace("è", "e").replace("ê", "e")
        .replace("à", "a").replace("â", "a")

    private fun compressImage(file: File): File {
        val maxDimension = 1600

        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return file

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension || bounds.outHeight / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }

        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options) ?: return file

        val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true
            )
            if (scaled !== decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }

        val compressed = File(file.parent, "compressed_${file.name}")
        compressed.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        }
        bitmap.recycle()
        return compressed
    }

    // --- File d'attente durable (offline) : cf. PendingUpload / SyncWorker ---

    /**
     * Met une photo/document en file d'attente durable (Room), pour ne jamais perdre le fichier
     * meme si l'app est tuee avant que l'utilisateur ne declenche l'envoi. Ne programme PAS de
     * synchronisation automatique en arriere-plan a elle seule : tant que l'agent n'a pas tape
     * "Valider" au moins une fois (cf. confirmPendingUploads), la photo attend sagement, sans
     * tentative d'envoi silencieuse — c'est le comportement explicitement demande.
     */
    suspend fun queuePendingUpload(dossierId: Long, file: File, documentType: String) {
        pendingUploadDao.insert(
            PendingUpload(
                dossierId = dossierId,
                documentType = documentType,
                localFilePath = file.absolutePath,
                createdAt = System.currentTimeMillis(),
                status = PendingUploadStatus.PENDING
            )
        )
    }

    /**
     * Appele quand l'agent tape "Valider"/"Enregistrer" : marque toutes les photos en attente de
     * ce dossier comme confirmees, autorisant SyncWorker a les reprendre automatiquement en
     * arriere-plan si la tentative immediate qui suit (cf. ViewModels) echoue faute de reseau.
     */
    suspend fun confirmPendingUploads(dossierId: Long) {
        pendingUploadDao.confirmForDossier(dossierId)
        SyncScheduler.enqueue(context)
    }

    /** Pour l'UI (ecran detail/express/contribution) : liste des photos en attente d'envoi d'un dossier. */
    fun observePendingUploads(dossierId: Long): Flow<List<PendingUpload>> =
        pendingUploadDao.getByDossier(dossierId)

    suspend fun deletePendingUpload(id: Long) = pendingUploadDao.delete(id)

    // --- Cache local de la liste des dossiers (offline-first) ---

    suspend fun cacheDossierList(dossiers: List<Dossier>) {
        val now = System.currentTimeMillis()
        val entities = dossiers.map { CachedDossier(id = it.id, dossierJson = gson.toJson(it), cachedAt = now) }
        cachedDossierDao.replaceAll(entities)
    }

    fun observeCachedDossierList(): Flow<List<Dossier>> =
        cachedDossierDao.getAll().map { cached -> cached.map { gson.fromJson(it.dossierJson, Dossier::class.java) } }

    // --- Cache local de "Rechercher un dossier" (dossiers actifs de l'antenne, offline-first) ---

    suspend fun cacheAntenneDossierList(dossiers: List<Dossier>) {
        val now = System.currentTimeMillis()
        val entities = dossiers.map { CachedAntenneDossier(id = it.id, dossierJson = gson.toJson(it), cachedAt = now) }
        cachedAntenneDossierDao.replaceAll(entities)
    }

    fun observeCachedAntenneDossierList(): Flow<List<Dossier>> =
        cachedAntenneDossierDao.getAll().map { cached -> cached.map { gson.fromJson(it.dossierJson, Dossier::class.java) } }

    suspend fun getLastSyncedAt(): Long? = cachedDossierDao.getLastCachedAt()

    /**
     * Repli hors-ligne pour l'ecran detail/express (resume) : si GET /dossier/{id} echoue (pas de
     * reseau), on retombe sur la derniere version connue depuis le cache liste (meme si ce cache
     * n'a pas ete pense a l'origine pour le detail, il contient le Dossier complet en JSON).
     * Retourne null si ce dossier n'a jamais ete vu dans la liste mise en cache.
     */
    suspend fun getCachedDossier(dossierId: Long): Dossier? =
        cachedDossierDao.getById(dossierId)?.let { cached ->
            runCatching { gson.fromJson(cached.dossierJson, Dossier::class.java) }.getOrNull()
        }

    /** Diagnostic temporaire (pas d'adb sur le terrain) : etat du cache au moment d'un echec de repli. */
    suspend fun debugCacheSnapshot(dossierId: Long): String {
        val row = runCatching { cachedDossierDao.getById(dossierId) }.getOrNull()
        val allIds = runCatching { cachedDossierDao.getAllIdsOnce() }.getOrDefault(emptyList())
        return "id cherché=$dossierId, ligne trouvée=${row != null}, cache contient ${allIds.size} dossier(s), ids=${allIds.take(30)}"
    }

    suspend fun deleteDocument(dossierId: Long, documentId: Long): Result<Unit> = runCatching {
        api.deleteDocument(dossierId, documentId)
        Unit
    }

    suspend fun advanceState(dossierId: Long): Result<Dossier> = runCatching {
        api.advanceState(dossierId)
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    suspend fun getAgentTerrainUsers(antenneId: Long?): Result<List<AgentTerrainUser>> = runCatching {
        api.getAgentTerrainUsers(antenneId)
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    suspend fun reassignAgentTerrain(dossierId: Long, newAgentTerrainUserId: String): Result<Dossier> = runCatching {
        api.reassignAgentTerrain(dossierId, ReassignAgentTerrainRequest(newAgentTerrainUserId))
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    /** Extrait les champs vehicule depuis la carte grise deja televersee sur le dossier (IA vision). */
    suspend fun extractVehiculeFromCarteGrise(dossierId: Long): Result<VehiculeExtraction> = runCatching {
        api.extractVehiculeFromCarteGrise(dossierId)
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    /**
     * Demande(s) de reprise de photo pour ce dossier, toutes phases confondues (historique
     * complet) : utilise par l'ecran detail pour afficher le message de la demande encore en
     * attente (resolvedAt == null) dans un bandeau, quelle que soit la phase agent terrain en
     * cours (avant/en cours/apres reparation).
     */
    suspend fun getPhotoRetakeRequests(dossierId: Long): Result<List<PhotoRetakeRequest>> = runCatching {
        api.getPhotoRetakeRequests(dossierId)
    }

    /** Fil de messages du dossier (meme fonctionnalite que le chat deja affiche sur le web). */
    suspend fun getDossierMessages(dossierId: Long): Result<List<DossierMessage>> = runCatching {
        api.getDossierMessages(dossierId)
    }

    suspend fun postDossierMessage(dossierId: Long, text: String): Result<DossierMessage> = runCatching {
        api.postDossierMessage(dossierId, DossierMessageCreateRequest(text))
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    /** Retourne le dernier devis VALIDE pour ce dossier (ou null si absent). */
    suspend fun getLastValidDevis(dossierId: Long): Result<Devis?> = runCatching {
        api.getDevisByDossier(dossierId)
            .firstOrNull { d -> d.etat?.equals("VALIDE", ignoreCase = true) == true }
    }

    /** Télécharge une pièce jointe en cache local et retourne une Uri consultable via FileProvider. */
    suspend fun downloadDocument(dossierId: Long, documentId: Long, fileName: String): Result<Uri> = runCatching {
        val response = api.downloadDocument(dossierId, documentId)
        if (!response.isSuccessful) throw RuntimeException("Téléchargement impossible (${response.code()})")
        val body = response.body() ?: throw RuntimeException("Fichier vide")
        writeToCacheAndShare(body.byteStream(), fileName)
    }

    /** Télécharge le PDF "Accord sur devis" en cache local et retourne une Uri consultable via FileProvider. */
    suspend fun downloadDevisPdf(devisId: Long): Result<Uri> = runCatching {
        val response = api.downloadDevisPdf(devisId)
        if (!response.isSuccessful) throw RuntimeException("Téléchargement impossible (${response.code()})")
        val body = response.body() ?: throw RuntimeException("Fichier vide")
        writeToCacheAndShare(body.byteStream(), "accord-sur-devis-$devisId.pdf")
    }

    /** Uri consultable (FileProvider) pour une photo pas encore envoyee, deja stockee localement. */
    fun uriForLocalFile(file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    private fun writeToCacheAndShare(input: java.io.InputStream, fileName: String): Uri {
        val downloadsDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val file = File(downloadsDir, fileName)
        input.use { stream ->
            file.outputStream().use { out -> stream.copyTo(out) }
        }
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun extractBusinessMessageOrRethrow(t: Throwable): Throwable {
        val ex = t as? retrofit2.HttpException ?: return t
        val body = ex.response()?.errorBody()?.string()
        if (body.isNullOrBlank()) return t
        return try {
            val json = org.json.JSONObject(body)
            val msg = json.optString("message", "").ifBlank { json.optString("error", "") }
            if (msg.isNotBlank()) RuntimeException(msg) else t
        } catch (e: Exception) {
            t
        }
    }
}
