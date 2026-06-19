package com.ma.sms.android.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ma.sms.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class KeycloakAuthManager(private val context: Context, private val tokenManager: TokenManager) {

    private val authService = AuthorizationService(context)
    private val httpClient = OkHttpClient()

    private val tokenUrl = "${BuildConfig.KEYCLOAK_URL}/realms/${BuildConfig.KEYCLOAK_REALM}/protocol/openid-connect/token"

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("${BuildConfig.KEYCLOAK_URL}/realms/${BuildConfig.KEYCLOAK_REALM}/protocol/openid-connect/auth"),
        Uri.parse(tokenUrl),
        null,
        Uri.parse("${BuildConfig.KEYCLOAK_URL}/realms/${BuildConfig.KEYCLOAK_REALM}/protocol/openid-connect/logout")
    )

    // --- Login natif username/password (Direct Grant) ---
    suspend fun loginWithPassword(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("grant_type", "password")
                    .add("client_id", BuildConfig.KEYCLOAK_CLIENT_ID)
                    .add("username", username)
                    .add("password", password)
                    .add("scope", "openid profile email offline_access")
                    .build()
                val request = Request.Builder().url(tokenUrl).post(body).build()
                val response = httpClient.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val error = runCatching { JSONObject(bodyStr).optString("error_description") }.getOrNull()
                    throw Exception(error?.ifBlank { null } ?: "Identifiants incorrects")
                }
                val json = JSONObject(bodyStr)
                tokenManager.accessToken = json.getString("access_token")
                tokenManager.refreshToken = json.optString("refresh_token").ifBlank { tokenManager.refreshToken }
                val expiresIn = json.optLong("expires_in", 300) * 1000L
                tokenManager.accessTokenExpiry = System.currentTimeMillis() + expiresIn
            }
        }

    // --- Refresh token ---
    suspend fun refreshAccessToken(): Result<String> {
        val refresh = tokenManager.refreshToken
            ?: return Result.failure(Exception("Pas de refresh token"))

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val tokenRequest = TokenRequest.Builder(serviceConfig, BuildConfig.KEYCLOAK_CLIENT_ID)
                .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setRefreshToken(refresh)
                .build()
            authService.performTokenRequest(tokenRequest) { tokenResponse, ex ->
                if (ex != null || tokenResponse == null) {
                    tokenManager.clear()
                    cont.resumeWith(Result.failure(ex ?: Exception("Refresh échoué")))
                } else {
                    saveTokens(tokenResponse)
                    cont.resumeWith(Result.success(Result.success(tokenResponse.accessToken ?: "")))
                }
            }
        }
    }

    fun logout() {
        tokenManager.clear()
    }

    private fun saveTokens(response: TokenResponse) {
        tokenManager.accessToken = response.accessToken
        tokenManager.refreshToken = response.refreshToken ?: tokenManager.refreshToken
        val expiresIn = response.accessTokenExpirationTime ?: (System.currentTimeMillis() + 300_000)
        tokenManager.accessTokenExpiry = expiresIn
    }
}
