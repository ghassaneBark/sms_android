package com.ma.sms.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache local du resultat de "Rechercher un dossier" (scope all_active_readonly : tous les
 * dossiers actifs de l'antenne, pas seulement ceux affectes a l'agent). Table separee de
 * [CachedDossier] ("mes dossiers") pour que rafraichir l'une ne fasse pas disparaitre l'autre
 * (chacune est remplacee integralement a chaque succes reseau, cf. replaceAll).
 */
@Entity(tableName = "cached_antenne_dossiers")
data class CachedAntenneDossier(
    @PrimaryKey val id: Long,
    val dossierJson: String,
    val cachedAt: Long
)
