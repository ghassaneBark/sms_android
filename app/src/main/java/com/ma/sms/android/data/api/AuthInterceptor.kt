package com.ma.sms.android.data.api

import com.ma.sms.android.auth.KeycloakAuthManager
import com.ma.sms.android.auth.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenManager: TokenManager,
    private val keycloakAuthManager: KeycloakAuthManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = getValidToken() ?: return chain.proceed(chain.request())

        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Active-Role", "ROLE_AGENT_TERRAIN")
            .build()

        val response = chain.proceed(request)

        if (response.code == 401) {
            response.close()
            val newToken = runBlocking { keycloakAuthManager.refreshAccessToken().getOrNull() }
                ?: return response
            val retryRequest = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $newToken")
                .addHeader("X-Active-Role", "ROLE_AGENT_TERRAIN")
                .build()
            return chain.proceed(retryRequest)
        }

        return response
    }

    private fun getValidToken(): String? {
        if (tokenManager.isAccessTokenValid()) return tokenManager.accessToken
        return runBlocking { keycloakAuthManager.refreshAccessToken().getOrNull() }
    }
}
