package com.ma.sms.android.data.api

import com.ma.sms.android.data.model.AgentTerrainUser
import com.ma.sms.android.data.model.Devis
import com.ma.sms.android.data.model.Dossier
import com.ma.sms.android.data.model.DocumentSinistre
import com.ma.sms.android.data.model.PageResponse
import com.ma.sms.android.data.model.ReassignAgentTerrainRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @GET("api/dossier")
    suspend fun getDossiers(): List<Dossier>

    @GET("api/dossier/{id}")
    suspend fun getDossier(@Path("id") id: Long): Dossier

    @GET("api/dossier/{id}/documents")
    suspend fun getDocuments(@Path("id") id: Long): List<DocumentSinistre>

    @Multipart
    @POST("api/dossier/{id}/documents")
    suspend fun uploadDocument(
        @Path("id") id: Long,
        @Part("type") type: RequestBody,
        @Part file: MultipartBody.Part
    ): DocumentSinistre

    @DELETE("api/dossier/{id}/documents/{documentId}")
    suspend fun deleteDocument(
        @Path("id") id: Long,
        @Path("documentId") documentId: Long
    ): Response<Unit>

    @POST("api/dossier/{id}/advance-state")
    suspend fun advanceState(@Path("id") id: Long): Dossier

    @GET("api/dossier/agent-terrain-users")
    suspend fun getAgentTerrainUsers(@Query("antenneId") antenneId: Long?): List<AgentTerrainUser>

    @POST("api/dossier/{id}/reassign-agent-terrain")
    suspend fun reassignAgentTerrain(
        @Path("id") id: Long,
        @Body body: ReassignAgentTerrainRequest
    ): Dossier

    @GET("api/dossier/{id}/devis")
    suspend fun getDevisByDossier(@Path("id") id: Long): List<Devis>

    @POST("api/mobile/fcm-token")
    suspend fun registerFcmToken(@Body body: Map<String, String>)

    @GET("api/dossier/{id}/documents/{documentId}/download")
    suspend fun downloadDocument(
        @Path("id") id: Long,
        @Path("documentId") documentId: Long
    ): Response<ResponseBody>
}
