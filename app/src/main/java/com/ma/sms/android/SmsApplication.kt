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
                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra("error", throwable.stackTraceToString())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    // Initialisation "a la demande" de WorkManager (pas besoin de desactiver le WorkManagerInitializer
    // dans le manifest : androidx.work detecte automatiquement qu'Application implemente
    // Configuration.Provider et bascule sur l'init a la demande).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SmsWorkerFactory(dossierRepository, appDatabase.pendingUploadDao()))
            .build()
}
