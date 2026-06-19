package com.ma.sms.android.data.repository

import com.ma.sms.android.data.api.ApiService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class FcmTokenRepository(private val api: ApiService) {

    suspend fun registerToken(token: String): Result<Unit> = runCatching {
        api.registerFcmToken(mapOf("token" to token))
    }
}
