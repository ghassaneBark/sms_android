package com.ma.sms.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PendingUpload::class, CachedDossier::class, CachedAntenneDossier::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingUploadDao(): PendingUploadDao
    abstract fun cachedDossierDao(): CachedDossierDao
    abstract fun cachedAntenneDossierDao(): CachedAntenneDossierDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sms_offline.db"
                )
                    // Cache/file d'attente locale uniquement (pas la source de verite serveur) :
                    // en cas de changement de schema, on peut se permettre de la recreer plutot
                    // que d'ecrire une vraie migration. A ne garder que tant que l'app est en test.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
