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
    val antenne: AntenneRef?,
    val documents: List<DocumentSinistre>?,
    val accordForfaits: List<AccordForfait>? = null
)

data class AntenneRef(
    val id: Long?
)

data class AgentTerrainUser(
    val id: String,
    val username: String?,
    val nom: String?,
    val prenom: String?,
    val email: String?
)

data class ReassignAgentTerrainRequest(
    val newAgentTerrainUserId: String
)

data class Assure(
    val nom: String?,
    val prenom: String?,
    val telephone: String?,
    val email: String?,
    val adresse: String?,
    val type: String?,
    val intermediaire: String? = null
)

data class Assurance(
    val id: Long,
    val nom: String?
)

data class Intermediaire(
    val id: Long,
    val nom: String?
)

data class Vehicule(
    val immatriculation: String?,
    val marque: String?,
    val modele: String?,
    val usage: String?,
    val adresse: String?,
    val numeroChassis: String? = null
)

/** Une proposition de forfait pour un dossier (accord/reponse assure, cf. flux "Dossier Express"). */
data class AccordForfait(
    val id: Long? = null,
    val typeAccord: String = "FORFAIT",
    val montantForfait: Double,
    val reponseAssureForfait: Boolean? = null
)

/** Corps de la requete de creation d'un dossier depuis le terrain ("Dossier Express"). */
data class DossierExpressCreateRequest(
    val assure: Assure,
    val assurance: Assurance,
    val vehiculeAssure: Vehicule?,
    val agentTerrainUserId: String?,
    val agentTerrainUserErId: String? = null,
    val express: Boolean = true
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
