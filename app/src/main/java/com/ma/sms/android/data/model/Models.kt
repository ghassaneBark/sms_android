package com.ma.sms.android.data.model

import com.google.gson.annotations.SerializedName

data class PageResponse<T>(
    val content: List<T>,
    val totalElements: Long,
    val totalPages: Int,
    @SerializedName("pageNumber") val number: Int,
    @SerializedName("pageSize") val size: Int
)

data class Dossier(
    val id: Long,
    val reference: String?,
    val referenceAssurance: String?,
    val etat: String?,
    val typeMission: String?,
    val dateCreation: String?,
    val dateSurvenance: String?,
    val villeExpertise: String?,
    val villeSinistre: String?,
    val assure: Assure?,
    val assurance: Assurance?,
    val vehiculeAssure: Vehicule?,
    val agentTerrainUserId: String?,
    val assignedUserId: String?,
    val documents: List<DocumentSinistre>?
)

data class Assure(
    val nom: String?,
    val prenom: String?,
    val telephone: String?,
    val email: String?,
    val adresse: String?,
    val type: String?
)

data class Assurance(
    val id: Long,
    val nom: String?
)

data class Vehicule(
    val immatriculation: String?,
    val marque: String?,
    val modele: String?,
    val usage: String?
)

data class DocumentSinistre(
    val id: Long?,
    val type: String?,
    val fileName: String?,
    val originalFileName: String?,
    val contentType: String?,
    val fileSize: Long?
)

data class AdvanceStateRequest(
    @SerializedName("dummy") val dummy: String? = null
)
