package com.ma.sms.android.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ma.sms.android.data.local.PendingUploadDao
import com.ma.sms.android.data.repository.DossierRepository

/**
 * WorkerFactory manuelle (pas de Hilt/Dagger dans ce projet) : permet d'injecter DossierRepository
 * et PendingUploadDao — deja construits en singleton dans SmsApplication — dans SyncWorker, qui
 * n'a pas de constructeur sans-argument utilisable par WorkManager par defaut.
 */
class SmsWorkerFactory(
    private val repository: DossierRepository,
    private val pendingUploadDao: PendingUploadDao
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, repository, pendingUploadDao)
        else -> null
    }
}
