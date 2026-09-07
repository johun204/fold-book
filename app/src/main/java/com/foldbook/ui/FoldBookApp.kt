package com.foldbook.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.foldbook.App

/** 폴더 탐색 라우트에서 "연결 루트" 를 뜻하는 센티넬 (빈 쿼리값은 nav 매칭이 불안정). */
const val ROOT = "__root__"

private const val T = 230

@Composable
fun FoldBookApp() {
    val nav = rememberNavController()
    val app = App.of(LocalContext.current)

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tab = when {
        route == "home" -> 0
        route?.startsWith("browse") == true -> 1
        route == "settings" -> 2
        else -> 0
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { nav.tab("home") },
                    icon = { Icon(Icons.Filled.Home, null) },
                    label = { Text("홈") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { nav.tab("browse") },
                    icon = { Icon(AppIcons.FolderOpen, null) },
                    label = { Text("탐색") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { nav.tab("settings") },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("설정") },
                )
            }
        }
    ) { pad ->
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.padding(pad),
            // 앞으로 갈 땐 오른쪽에서 슬라이드 인, 뒤로 갈 땐 오른쪽으로 슬라이드 아웃 — 방향이 보이는 전환
            enterTransition = { slideInHorizontally(tween(T)) { it / 3 } + fadeIn(tween(T)) },
            exitTransition = { slideOutHorizontally(tween(T)) { -it / 6 } + fadeOut(tween(T)) },
            popEnterTransition = { slideInHorizontally(tween(T)) { -it / 6 } + fadeIn(tween(T)) },
            popExitTransition = { slideOutHorizontally(tween(T)) { it / 3 } + fadeOut(tween(T)) },
        ) {
            composable("home") { HomeScreen(app) }

            composable("browse") {
                BrowseScreen(app) { conn ->
                    nav.navigate(
                        "browse/folder?conn=${conn.id}&folder=${Uri.encode(ROOT)}" +
                            "&name=${Uri.encode(conn.label)}&crumb=${Uri.encode(conn.label)}"
                    )
                }
            }

            composable("browse/folder?conn={conn}&folder={folder}&name={name}&crumb={crumb}") { entry ->
                val a = entry.arguments
                val rawFolder = Uri.decode(a?.getString("folder").orEmpty())
                val connId = a?.getString("conn").orEmpty()
                val crumb = Uri.decode(a?.getString("crumb").orEmpty())
                BrowseFolderScreen(
                    app = app,
                    connId = connId,
                    folderId = if (rawFolder == ROOT) "" else rawFolder,
                    folderName = Uri.decode(a?.getString("name").orEmpty()),
                    crumb = crumb,
                    onOpenFolder = { fid, fname ->
                        nav.navigate(
                            "browse/folder?conn=$connId&folder=${Uri.encode(fid)}" +
                                "&name=${Uri.encode(fname)}&crumb=${Uri.encode("$crumb / $fname")}"
                        )
                    },
                    onBack = { nav.popBackStack() },
                    onConnectionRemoved = { nav.popBackStack("browse", inclusive = false) },
                )
            }

            composable("settings") { SettingsScreen(app) }
        }
    }
}

private fun NavHostController.tab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
