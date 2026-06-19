package com.ma.sms.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ma.sms.android.SmsApplication
import com.ma.sms.android.ui.detail.DossierDetailScreen
import com.ma.sms.android.ui.dossiers.DossierListScreen
import com.ma.sms.android.ui.login.LoginScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object DossierList : Screen("dossiers")
    object DossierDetail : Screen("dossiers/{id}") {
        fun buildRoute(id: Long) = "dossiers/$id"
    }
}

@Composable
fun NavGraph(navController: NavHostController, app: SmsApplication) {
    val startDestination = if (app.tokenManager.isLoggedIn()) Screen.DossierList.route else Screen.Login.route

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Login.route) {
            LoginScreen(
                authManager = app.authManager,
                onLoginSuccess = {
                    navController.navigate(Screen.DossierList.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.DossierList.route) {
            DossierListScreen(
                repository = app.dossierRepository,
                onDossierClick = { id ->
                    navController.navigate(Screen.DossierDetail.buildRoute(id))
                },
                onLogout = {
                    app.authManager.logout()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.DossierDetail.route,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: return@composable
            DossierDetailScreen(
                dossierId = id,
                repository = app.dossierRepository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
