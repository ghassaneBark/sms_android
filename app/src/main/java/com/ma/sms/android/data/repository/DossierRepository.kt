package com.ma.sms.android.data.repository

import android.content.Context
import android.net.Uri
import com.ma.sms.android.data.api.ApiService
import com.ma.sms.android.data.model.AgentTerrainUser
import com.ma.sms.android.data.model.Devis
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.model.ReassignAgentTerrainRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class DossierRepository(private val api: ApiService, private val context: Context) {

    suspend fun getDossiers(): Result<List<Dossier>> = runCatching {
        api.getDossiers()
    }

    suspend fun getDossier(id: Long): Result<Dossier> = runCatching {
        api.getDossier(id)
    }

    suspend fun getDocuments(dossierId: Long): Result<List<DocumentSinistre>> = runCatching {
        api.getDocuments(dossierId)
    }

    suspend fun uploadPhoto(dossierId: Long, photoFile: File, documentType: String): Result<DocumentSinistre> = runCatching {
        val compressed = compressImage(photoFile)
        // Les types d'angles véhicule sont encodés dans le nom du fichier,
        // le backend reçoit le type autorisé "Photos vehicules avant reparation"
        val (actualType, fileName) = when {
            documentType.startsWith("Photos vehicules - ") -> {
                val angle = slugify(documentType.removePrefix("Photos vehicules - "))
                "Photos vehicules avant reparation" to "vehicule-${angle}_${photoFile.name}"
            }
            documentType.startsWith("PHOTOS EN COURS DE REPARATION - ") -> {
                val angle = slugify(documentType.removePrefix("PHOTOS EN COURS DE REPARATION - "))
                "PHOTOS EN COURS DE REPARATION" to "en-cours-${angle}_${photoFile.name}"
            }
            documentType.startsWith("PHOTO APRES REPARATION - ") -> {
                val angle = slugify(documentType.removePrefix("PHOTO APRES REPARATION - "))
                "PHOTO APRES REPARATION" to "apres-reparation-${angle}_${photoFile.name}"
            }
            else -> documentType to photoFile.name
        }
        val typeBody = actualType.toRequestBody("text/plain".toMediaType())
        val fileBody = compressed.asRequestBody("image/jpeg".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", fileName, fileBody)
        api.uploadDocument(dossierId, typeBody, filePart)
    }

    private fun slugify(text: String): String = text.lowercase()
        .replace(" ", "-")
        .replace("é", "e").replace("è", "e").replace("ê", "e")
        .replace("à", "a").replace("â", "a")

    private fun compressImage(file: File): File {
        val maxDimension = 1600

        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return file

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension || bounds.outHeight / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }

        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options) ?: return file

        val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true
            )
            if (scaled !== decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }

        val compressed = File(file.parent, "compressed_${file.name}")
        compressed.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        }
        bitmap.recycle()
        return compressed
    }

    suspend fun deleteDocument(dossierId: Long, documentId: Long): Result<Unit> = runCatching {
        api.deleteDocument(dossierId, documentId)
        Unit
    }

    suspend fun advanceState(dossierId: Long): Result<Dossier> = runCatching {
        api.advanceState(dossierId)
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    suspend fun getAgentTerrainUsers(antenneId: Long?): Result<List<AgentTerrainUser>> = runCatching {
        api.getAgentTerrainUsers(antenneId)
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    suspend fun reassignAgentTerrain(dossierId: Long, newAgentTerrainUserId: String): Result<Dossier> = runCatching {
        api.reassignAgentTerrain(dossierId, ReassignAgentTerrainRequest(newAgentTerrainUserId))
    }.recoverCatching { throwable ->
        throw extractBusinessMessageOrRethrow(throwable)
    }

    /** Retourne le dernier devis VALIDE pour ce dossier (ou null si absent). */
    suspend fun getLastValidDevis(dossierId: Long): Result<Devis?> = runCatching {
        api.getDevisByDossier(dossierId)
            .firstOrNull { d -> d.etat?.equals("VALIDE", ignoreCase = true) == true }
    }

    private fun extractBusinessMessageOrRethrow(t: Throwable): Throwable {
        val ex = t as? retrofit2.HttpException ?: return t
        val body = ex.response()?.errorBody()?.string()
        if (body.isNullOrBlank()) return t
        return try {
            val json = org.json.JSONObject(body)
            val msg = json.optString("message", "").ifBlank { json.optString("error", "") }
            if (msg.isNotBlank()) RuntimeException(msg) else t
        } catch (e: Exception) {
            t
        }
    }
}
