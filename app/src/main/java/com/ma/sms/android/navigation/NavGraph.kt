package com.ma.sms.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.ma.sms.android.SmsApplication
import com.ma.sms.android.ui.detail.DossierDetailScreen
import com.ma.sms.android.ui.dossiers.DossierListScreen
import com.ma.sms.android.ui.express.DossierExpressScreen
import com.ma.sms.android.ui.login.LoginScreen
import com.ma.sms.android.ui.search.DossierContributeScreen
import com.ma.sms.android.ui.search.DossierSearchScreen
import com.ma.sms.android.ui.search.DossierSearchViewModel

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object DossierList : Screen("dossiers")
    object DossierDetail : Screen("dossiers/{id}") {
        fun buildRoute(id: Long) = "dossiers/$id"
    }
    object DossierExpress : Screen("dossiers/express")
    object DossierExpressResume : Screen("dossiers/express/{id}") {
        fun buildRoute(id: Long) = "dossiers/express/$id"
    }
    object DossierSearchFlow : Screen("dossiers/search/flow")
    object DossierSearch : Screen("dossiers/search")
    object DossierContribute : Screen("dossiers/search/{id}") {
        fun buildRoute(id: Long) = "dossiers/search/$id"
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
                onDossierClick = { dossier ->
                    // Un dossier Express en cours (TRAITEMENT_DOSSIER_EXPRESS) n'a pas d'ecran de
                    // detail generique : photos/forfait/edition assure-vehicule ne vivent que dans
                    // l'assistant DossierExpressScreen, donc on y revient au lieu du detail normal.
                    if (dossier.etat == "TRAITEMENT_DOSSIER_EXPRESS") {
                        navController.navigate(Screen.DossierExpressResume.buildRoute(dossier.id))
                    } else {
                        navController.navigate(Screen.DossierDetail.buildRoute(dossier.id))
                    }
                },
                onNewDossierExpress = {
                    navController.navigate(Screen.DossierExpress.route)
                },
                onSearchDossiers = {
                    navController.navigate(Screen.DossierSearchFlow.route)
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

        composable(Screen.DossierExpress.route) {
            DossierExpressScreen(
                repository = app.dossierRepository,
                onBack = { navController.popBackStack() },
                onFinished = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.DossierExpressResume.route,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: return@composable
            DossierExpressScreen(
                repository = app.dossierRepository,
                existingDossierId = id,
                onBack = { navController.popBackStack() },
                onFinished = { navController.popBackStack() }
            )
        }

        // Sous-graphe "recherche + contribution" : DossierSearchViewModel est partage entre les
        // deux ecrans (scope sur l'entree du sous-graphe) pour transmettre le Dossier selectionne
        // sans avoir a le re-serialiser dans la route ou a le recharger via un appel API distinct.
        navigation(startDestination = Screen.DossierSearch.route, route = Screen.DossierSearchFlow.route) {
            composable(Screen.DossierSearch.route) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.DossierSearchFlow.route) }
                val searchVm: DossierSearchViewModel = viewModel(parentEntry, factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return DossierSearchViewModel(app.dossierRepository) as T
                    }
                })
                DossierSearchScreen(
                    vm = searchVm,
                    onResultClick = { dossier ->
                        navController.navigate(Screen.DossierContribute.buildRoute(dossier.id))
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.DossierContribute.route,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.DossierSearchFlow.route) }
                val searchVm: DossierSearchViewModel = viewModel(parentEntry, factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return DossierSearchViewModel(app.dossierRepository) as T
                    }
                })
                val dossier = searchVm.uiState.collectAsState().value.selectedDossier
                if (dossier == null) {
                    // Acces direct sans passer par la recherche (ex: retour arriere systeme
                    // incoherent) : rien a afficher, retour a la recherche.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    DossierContributeScreen(
                        dossier = dossier,
                        repository = app.dossierRepository,
                        onDone = { navController.popBackStack(Screen.DossierSearch.route, inclusive = false) }
                    )
                }
            }
        }
    }
}
