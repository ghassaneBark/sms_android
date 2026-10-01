package com.ma.sms.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingUploadDao {

    @Insert
    suspend fun insert(upload: PendingUpload): Long

    /** Pour l'UI (ecran detail/contribution) : observe en continu les uploads en attente d'un dossier donne. */
    @Query("SELECT * FROM pending_uploads WHERE dossierId = :dossierId ORDER BY id ASC")
    fun getByDossier(dossierId: Long): Flow<List<PendingUpload>>

    /**
     * Pour SyncWorker : lecture ponctuelle (pas de Flow) de tout ce qui reste a (re)tenter, tous
     * dossiers confondus. Limite aux lignes "confirmed" : la simple prise de photo ne suffit pas
     * a autoriser un envoi automatique en arriere-plan, il faut que l'agent ait explicitement
     * tape "Valider" au moins une fois (cf. confirmForDossier).
     */
    @Query("SELECT * FROM pending_uploads WHERE confirmed = 1 AND (status = 'PENDING' OR status = 'FAILED') ORDER BY id ASC")
    suspend fun getAllPendingOrFailed(): List<PendingUpload>

    /** Marque toutes les lignes en attente d'un dossier comme confirmees (appui sur "Valider"). */
    @Query("UPDATE pending_uploads SET confirmed = 1 WHERE dossierId = :dossierId")
    suspend fun confirmForDossier(dossierId: Long)

    @Query("UPDATE pending_uploads SET status = :status, retryCount = :retryCount, lastError = :lastError WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, retryCount: Int, lastError: String?)

    @Query("DELETE FROM pending_uploads WHERE id = :id")
    suspend fun delete(id: Long)
}
