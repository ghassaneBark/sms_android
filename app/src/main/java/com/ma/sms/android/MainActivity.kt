package com.ma.sms.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.ma.sms.android.navigation.NavGraph
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
}
