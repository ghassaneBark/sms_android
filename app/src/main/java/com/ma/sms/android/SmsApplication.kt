package com.ma.sms.android

import android.app.Application
import android.content.Intent
import androidx.work.Configuration
import com.ma.sms.android.auth.KeycloakAuthManager
import com.ma.sms.android.auth.TokenManager
import com.ma.sms.android.data.api.RetrofitClient
import com.ma.sms.android.data.api.ApiService
import com.ma.sms.android.data.local.AppDatabase
import com.ma.sms.android.data.repository.DossierRepository
import com.ma.sms.android.data.repository.FcmTokenRepository
import com.ma.sms.android.sync.SmsWorkerFactory
import com.ma.sms.android.sync.SyncScheduler
import com.ma.sms.android.util.ConnectivityObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SmsApplication : Application(), Configuration.Provider {

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
    lateinit var connectivityObserver: ConnectivityObserver
        private set
    lateinit var appDatabase: AppDatabase
        private set

    // Scope applicatif (pas viewModelScope : doit survivre tant que le process vit, independamment
    // de tout ecran affiche) pour observer la connectivite en continu et re-declencher la sync.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        tokenManager = TokenManager(this)
        authManager = KeycloakAuthManager(this, tokenManager)
        apiService = RetrofitClient.create(tokenManager, authManager)
        appDatabase = AppDatabase.getInstance(this)
        dossierRepository = DossierRepository(
            apiService,
            this,
            appDatabase.pendingUploadDao(),
            appDatabase.cachedDossierDao(),
            appDatabase.cachedAntenneDossierDao()
        )
        fcmRepository = FcmTokenRepository(apiService)
        connectivityObserver = ConnectivityObserver(this)

        // Filet de securite : des que la connectivite revient, on retente la sync de la file
        // d'attente durable (harmless no-op si rien n'est en attente ; enqueueUniqueWork + KEEP
        // evite les executions concurrentes en doublon).
        applicationScope.launch {
            connectivityObserver.isConnected.collect { connected ->
                if (connected) SyncScheduler.enqueue(this@SmsApplication)
            }
        }
    }

    // Diagnostic temporaire : evite l'ecran "L'application s'est arretee" du systeme (qui ne montre
    // aucune stack trace exploitable sans adb) en redirigeant tout crash non rattrape vers un ecran
    // dedie affichant l'erreur complete, copiable. A retirer une fois le bug hors-ligne diagnostique.
    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val intent = if (isClockSkewAuthError(throwable)) {
                    // AppAuth-Android relance cette erreur de validation du jeton (iat trop eloigne
                    // de l'heure locale, signe que l'horloge du telephone est mal reglee) comme une
                    // exception non rattrapee depuis AsyncTask plutot que de la livrer proprement au
                    // callback de refreshAccessToken() — on l'intercepte ici pour afficher un message
                    // actionnable au lieu du dump de stack trace brut.
                    tokenManager.clear()
                    val skewMinutes = measureClockSkewMinutes()
                    Intent(this, CrashActivity::class.java).apply {
                        putExtra("title", "Connexion impossible")
                        putExtra(
                            "error",
                            buildClockSkewMessage(skewMinutes)
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    Intent(this, CrashActivity::class.java).apply {
                        putExtra("error", throwable.stackTraceToString())
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                startActivity(intent)
            } catch (e: Throwable) {
                // best effort : si meme ca echoue, on laisse simplement le crash normal se produire.
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(1)
            }
        }
    }

    private fun isClockSkewAuthError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message ?: ""
            if (message.contains("Issued at time is more than", ignoreCase = true)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    // Mesure l'ecart reel entre l'horloge du telephone et celle du serveur via l'en-tete HTTP
    // "Date" (toujours present, meme sans authentification) plutot que de se contenter d'affirmer
    // "votre horloge est fausse" sans preuve verifiable par l'utilisateur ni par nous au support.
    // Appel synchrone a dessein : on est deja dans le crash handler, le process va etre tue juste
    // apres de toute facon ; timeouts courts pour ne pas bloquer ce dernier instant trop longtemps.
    private fun measureClockSkewMinutes(): Long? = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val url = "${BuildConfig.KEYCLOAK_URL}/realms/${BuildConfig.KEYCLOAK_REALM}/.well-known/openid-configuration"
        val response = client.newCall(Request.Builder().url(url).head().build()).execute()
        val serverDate = response.headers.getDate("Date")
        response.close()
        serverDate?.let { (it.time - System.currentTimeMillis()) / 60_000L }
    }.getOrNull()

    private fun buildClockSkewMessage(skewMinutes: Long?): String {
        val intro = "L'heure de votre téléphone semble incorrecte, ce qui empêche la connexion."
        val detail = if (skewMinutes != null && kotlin.math.abs(skewMinutes) >= 1) {
            val sens = if (skewMinutes > 0) "en retard" else "en avance"
            "\n\nVotre téléphone est ${sens} d'environ ${kotlin.math.abs(skewMinutes)} minute(s) par rapport à l'heure du serveur."
        } else {
            ""
        }
        return intro + detail + "\n\n" +
            "Veuillez vérifier les réglages \"Date et heure\" de votre téléphone " +
            "(activez \"Date et heure automatiques\" ET \"Fuseau horaire automatique\"), " +
            "puis relancez l'application."
    }

    // Initialisation "a la demande" de WorkManager (pas besoin de desactiver le WorkManagerInitializer
    // dans le manifest : androidx.work detecte automatiquement qu'Application implemente
    // Configuration.Provider et bascule sur l'init a la demande).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SmsWorkerFactory(dossierRepository, appDatabase.pendingUploadDao()))
            .build()
}
