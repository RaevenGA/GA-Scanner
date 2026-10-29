package com.gascanner.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gascanner.app.ui.card.CardDetailScreen
import com.gascanner.app.ui.collection.CollectionScreen
import com.gascanner.app.ui.decks.DeckDetailScreen
import com.gascanner.app.ui.decks.DecksScreen
import com.gascanner.app.ui.scan.ScanScreen
import com.gascanner.app.ui.search.SearchScreen
import com.gascanner.app.ui.settings.SettingsScreen
import com.gascanner.app.ui.theme.GATheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GATheme { AppRoot() } }
    }
}

private object Routes {
    const val SEARCH = "search"
    const val SCAN = "scan"
    const val COLLECTION = "collection"
    const val DECKS = "decks"
    const val SETTINGS = "settings"
    const val CARD = "card/{slug}?edition={edition}"
    const val DECK = "deck/{deckId}"

    fun card(slug: String, editionUuid: String?): String =
        "card/${Uri.encode(slug)}" + (editionUuid?.let { "?edition=${Uri.encode(it)}" } ?: "")

    fun deck(id: Long) = "deck/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.SEARCH, "Cards", Icons.Default.Search),
    Tab(Routes.SCAN, "Scan", Icons.Default.CameraAlt),
    Tab(Routes.COLLECTION, "Collection", Icons.Default.CollectionsBookmark),
    Tab(Routes.DECKS, "Decks", Icons.Default.Style),
    Tab(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { nav.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.SEARCH, modifier = Modifier.padding(padding)) {
            composable(Routes.SEARCH) {
                SearchScreen(onOpenCard = { slug -> nav.navigate(Routes.card(slug, null)) })
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    onOpenCard = { slug, edition -> nav.navigate(Routes.card(slug, edition)) },
                    onOpenSettings = { nav.switchTab(Routes.SETTINGS) },
                )
            }
            composable(Routes.COLLECTION) {
                CollectionScreen(onOpenCard = { slug, edition -> nav.navigate(Routes.card(slug, edition)) })
            }
            composable(Routes.DECKS) {
                DecksScreen(onOpenDeck = { nav.navigate(Routes.deck(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(
                Routes.CARD,
                arguments = listOf(
                    navArgument("slug") { type = NavType.StringType },
                    navArgument("edition") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                CardDetailScreen(
                    slug = entry.arguments?.getString("slug").orEmpty(),
                    editionUuid = entry.arguments?.getString("edition"),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.DECK,
                arguments = listOf(navArgument("deckId") { type = NavType.LongType }),
            ) { entry ->
                DeckDetailScreen(
                    deckId = entry.arguments?.getLong("deckId") ?: 0L,
                    onBack = { nav.popBackStack() },
                    onOpenCard = { slug, edition -> nav.navigate(Routes.card(slug, edition)) },
                    onAddCards = { nav.switchTab(Routes.SEARCH) },
                )
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
