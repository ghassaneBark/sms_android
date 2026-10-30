package com.ma.sms.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache local de la derniere reponse reseau reussie de GET /dossiers/{id}/photo-retake-requests,
 * pour pouvoir savoir hors ligne si une reprise photo est encore en attente (et donc masquer le
 * croquis comme en ligne) plutot que de se rabattre silencieusement sur "aucune reprise en cours".
 * Meme approche que CachedDossier : la liste complete est stockee telle quelle en JSON (Gson).
 */
@Entity(tableName = "cached_photo_retake_requests")
data class CachedPhotoRetakeRequests(
    @PrimaryKey val dossierId: Long,
    val requestsJson: String,
    val cachedAt: Long
)
