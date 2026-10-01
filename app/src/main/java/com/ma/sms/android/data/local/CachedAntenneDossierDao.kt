package com.ma.sms.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedAntenneDossierDao {

    @Query("SELECT * FROM cached_antenne_dossiers ORDER BY id ASC")
    fun getAll(): Flow<List<CachedAntenneDossier>>

    @Query("DELETE FROM cached_antenne_dossiers")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dossiers: List<CachedAntenneDossier>)

    /** Remplace integralement le cache par la derniere reponse reseau reussie (pas une fusion). */
    @Transaction
    suspend fun replaceAll(dossiers: List<CachedAntenneDossier>) {
        clear()
        insertAll(dossiers)
    }
}
