package com.ma.sms.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CachedPhotoRetakeRequestDao {

    @Query("SELECT * FROM cached_photo_retake_requests WHERE dossierId = :dossierId")
    suspend fun getByDossierId(dossierId: Long): CachedPhotoRetakeRequests?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CachedPhotoRetakeRequests)
}
