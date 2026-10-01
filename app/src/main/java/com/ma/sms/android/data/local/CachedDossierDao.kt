package com.ma.sms.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedDossierDao {

    @Query("SELECT * FROM cached_dossiers ORDER BY id ASC")
    fun getAll(): Flow<List<CachedDossier>>

    @Query("SELECT * FROM cached_dossiers WHERE id = :id")
    suspend fun getById(id: Long): CachedDossier?

    @Query("SELECT id FROM cached_dossiers ORDER BY id ASC")
    suspend fun getAllIdsOnce(): List<Long>

    @Query("DELETE FROM cached_dossiers")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dossiers: List<CachedDossier>)

    /** Remplace integralement le cache par la derniere reponse reseau reussie (pas une fusion). */
    @Transaction
    suspend fun replaceAll(dossiers: List<CachedDossier>) {
        clear()
        insertAll(dossiers)
    }

    @Query("SELECT MAX(cachedAt) FROM cached_dossiers")
    suspend fun getLastCachedAt(): Long?
}
