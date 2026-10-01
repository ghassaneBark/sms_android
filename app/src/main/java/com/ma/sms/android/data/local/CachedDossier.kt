package com.ma.sms.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache local de la liste des dossiers (derniere reponse reseau reussie de GET /dossiers), pour
 * afficher quelque chose immediatement — y compris 100% hors ligne — au lieu d'un ecran vide/erreur.
 * Le dossier complet est stocke tel quel en JSON (Gson, deja utilise dans le projet) plutot que
 * d'etre eclate en colonnes : plus simple et suffisant, ce cache ne sert qu'a la lecture d'affichage.
 */
@Entity(tableName = "cached_dossiers")
data class CachedDossier(
    @PrimaryKey val id: Long,
    val dossierJson: String,
    val cachedAt: Long
)
