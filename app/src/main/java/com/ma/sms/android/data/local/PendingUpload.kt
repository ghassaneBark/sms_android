package com.ma.sms.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * File d'attente durable des photos/documents pas encore televerses au backend. Persiste en base
 * locale (Room) pour survivre a un kill du processus par l'OS : la capture photo (deja decouplee
 * de l'upload dans toute l'app) ecrit le fichier sur disque (cacheDir) puis insere une ligne ici,
 * et c'est SyncWorker (WorkManager) qui se charge de l'upload effectif des que la connectivite le
 * permet, meme si l'agent ne rouvre jamais l'app.
 */
@Entity(tableName = "pending_uploads")
data class PendingUpload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dossierId: Long,
    val documentType: String,
    val localFilePath: String,
    val createdAt: Long,
    val status: String,
    val retryCount: Int = 0,
    val lastError: String? = null,
    // Une photo n'est reprise automatiquement par SyncWorker en arriere-plan que si l'agent a
    // explicitement tape "Valider" au moins une fois pour elle (que cette tentative ait reussi,
    // echoue faute de reseau, ou echoue pour une autre raison). Simplement prendre une photo ne
    // suffit pas a autoriser un envoi silencieux : cf. demande explicite de l'agent terrain.
    val confirmed: Boolean = false
)

/** Valeurs possibles de [PendingUpload.status] (stocke en String, pas d'enum Room ici pour rester simple). */
object PendingUploadStatus {
    const val PENDING = "PENDING"
    const val UPLOADING = "UPLOADING"
    const val FAILED = "FAILED"
}
