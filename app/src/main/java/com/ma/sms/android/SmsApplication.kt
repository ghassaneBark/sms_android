package com.ma.sms.android

import android.app.Application
import com.ma.sms.android.auth.KeycloakAuthManager
import com.ma.sms.android.auth.TokenManager
import com.ma.sms.android.data.api.RetrofitClient
import com.ma.sms.android.data.api.ApiService
import com.ma.sms.android.data.repository.DossierRepository
import com.ma.sms.android.data.repository.FcmTokenRepository

class SmsApplication : Application() {

    lateinit var tokenManager: TokenManager
        private set
    lateinit var authManager: KeycloakAuthManager
        private set
    lateinit var apiService: ApiService
        private set
    lateinit var dossierRepository: DossierRepository
        private set
    lateinit var fcmRepository: FcmTokenRepository
        private set

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager(this)
        authManager = KeycloakAuthManager(this, tokenManager)
        apiService = RetrofitClient.create(tokenManager, authManager)
        dossierRepository = DossierRepository(apiService, this)
        fcmRepository = FcmTokenRepository(apiService)
    }
}
