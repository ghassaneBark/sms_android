package com.ma.sms.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ma.sms.android.data.local.PendingUploadDao
import com.ma.sms.android.data.local.PendingUploadStatus
import com.ma.sms.android.data.repository.DossierRepository
import java.io.File

/**
 * Worker WorkManager qui vide la file d'attente durable (PendingUpload) des que la contrainte
 * reseau (CONNECTED) est satisfaite — filet de securite qui garantit qu'une photo capturee finit
 * par etre televersee meme si l'agent ne retape jamais sur "Enregistrer"/"Valider", ou si le
 * processus de l'app est tue par l'OS avant l'upload manuel.
 *
 * Reutilise exactement DossierRepository.uploadPhoto (meme forme de requete multipart, meme
 * parsing d'erreur) que le chemin d'upload manuel des ViewModels : pas de logique d'upload
 * parallele/differente ici.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: DossierRepository,
    private val pendingUploadDao: PendingUploadDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val items = pendingUploadDao.getAllPendingOrFailed()
        for (item in items) {
            // Plafond de securite : au-dela de MAX_RETRY tentatives, on arrete de reessayer
            // automatiquement (probablement un rejet definitif cote backend, ex: piece invalide)
            // pour ne pas consommer des cycles worker en boucle. La ligne reste visible en FAILED
            // pour une reprise manuelle.
            if (item.retryCount >= MAX_RETRY) continue

            pendingUploadDao.updateStatus(item.id, PendingUploadStatus.UPLOADING, item.retryCount, item.lastError)
            val file = File(item.localFilePath)
            val result = repository.uploadPhoto(item.dossierId, file, item.documentType)
            if (result.isSuccess) {
                file.delete()
                pendingUploadDao.delete(item.id)
            } else {
                val message = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "Erreur inconnue"
                pendingUploadDao.updateStatus(item.id, PendingUploadStatus.FAILED, item.retryCount + 1, message)
            }
        }
        // Le worker s'est execute correctement (qu'il y ait eu des echecs individuels ou non) :
        // ceux-ci resteront en FAILED/PENDING pour le prochain declenchement (connectivite,
        // nouvelle photo mise en file), pas besoin de faire echouer/reessayer tout le worker.
        return Result.success()
    }

    companion object {
        private const val MAX_RETRY = 10
    }
}
