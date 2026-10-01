package com.ma.sms.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Point d'entree unique pour declencher une tentative de synchronisation de la file d'attente
 * durable (PendingUpload). Utilise enqueueUniqueWork + KEEP : appeler enqueue() plusieurs fois
 * de suite (ex: a chaque photo mise en file, et a chaque retour de connectivite) est un no-op
 * sans effet si un run est deja planifie/en cours, WorkManager attend juste que la contrainte
 * reseau soit satisfaite pour executer le worker.
 */
object SyncScheduler {

    const val UNIQUE_WORK_NAME = "sync_pending_uploads"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
