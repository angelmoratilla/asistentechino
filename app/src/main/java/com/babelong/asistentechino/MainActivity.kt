package com.babelong.asistentechino

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.babelong.asistentechino.ui.HistoryScreen
import com.babelong.asistentechino.ui.LiveScreen
import com.babelong.asistentechino.ui.SessionDetailScreen
import com.babelong.asistentechino.ui.theme.AsistenteChinoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val ui by viewModel.ui.collectAsState()
            AsistenteChinoTheme(darkTheme = ui.theme != "light") {
                AppNav(viewModel)
            }
        }
    }
}

@Composable
private fun AppNav(viewModel: MainViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "live") {
        composable("live") {
            LiveScreen(viewModel = viewModel, onOpenHistory = { nav.navigate("history") })
        }
        composable("history") {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onOpenSession = { id -> nav.navigate("detail/$id") },
            )
        }
        composable("detail/{id}") { entry ->
            SessionDetailScreen(
                sessionId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
    }
}
