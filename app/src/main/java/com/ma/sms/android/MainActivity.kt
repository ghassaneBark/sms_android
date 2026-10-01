package com.ma.sms.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.ma.sms.android.navigation.NavGraph
import com.ma.sms.android.sync.SyncScheduler
import com.ma.sms.android.ui.theme.SmsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SmsApplication
        setContent {
            SmsTheme {
                val navController = rememberNavController()
                NavGraph(navController = navController, app = app)
            }
        }
    }

    // Filet de securite supplementaire : la synchronisation declenchee par ConnectivityObserver
    // (SmsApplication) peut etre retardee par les restrictions d'execution en arriere-plan de
    // certains constructeurs Android. Rouvrir l'app est un moment fiable pour retenter — no-op
    // si rien n'est en attente ou si personne n'a confirme via "Valider".
    override fun onResume() {
        super.onResume()
        SyncScheduler.enqueue(applicationContext)
    }
}
