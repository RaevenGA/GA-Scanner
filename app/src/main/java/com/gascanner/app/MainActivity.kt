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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.i18n.Strings
import com.gascanner.app.ui.card.CardDetailScreen
import com.gascanner.app.ui.collection.CollectionScreen
import com.gascanner.app.ui.compare.CompareScreen
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
        setContent {
            val strings by appContainer().strings.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalStrings provides strings) {
                GATheme { AppRoot() }
            }
        }
    }
}

/*
 * Each bottom tab has its own nested navigation graph, so each keeps its own history
 * (e.g. an open deck stays in the Decks tab and never shows up when switching to Collection).
 */
private object Routes {
    const val TAB_SEARCH = "tab_search"
    const val TAB_SCAN = "tab_scan"
    const val TAB_COLLECTION = "tab_collection"
    const val TAB_DECKS = "tab_decks"
    const val TAB_SETTINGS = "tab_settings"

    const val SEARCH = "search"
    const val SCAN = "scan"
    const val COLLECTION = "collection"
    const val DECKS = "decks"
    const val SETTINGS = "settings"
    const val COMPARE = "collection/compare"
    const val DECK = "decks/deck/{deckId}"
    const val DECK_FROM_COLLECTION = "decks/deck/{deckId}/from-collection"
    const val SCAN_REVIEW = "scan/review"

    fun cardPattern(tab: String) = "$tab/card/{slug}?edition={edition}"
    fun card(tab: String, slug: String, editionUuid: String?): String =
        "$tab/card/${Uri.encode(slug)}" + (editionUuid?.let { "?edition=${Uri.encode(it)}" } ?: "")

    fun deck(id: Long) = "decks/deck/$id"
    fun deckFromCollection(id: Long) = "decks/deck/$id/from-collection"
}

private data class Tab(val graph: String, val label: (Strings) -> String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TAB_SEARCH, { it.tabCards }, Icons.Default.Search),
    Tab(Routes.TAB_SCAN, { it.tabScan }, Icons.Default.CameraAlt),
    Tab(Routes.TAB_COLLECTION, { it.tabCollection }, Icons.Default.CollectionsBookmark),
    Tab(Routes.TAB_DECKS, { it.tabDecks }, Icons.Default.Style),
    Tab(Routes.TAB_SETTINGS, { it.tabSettings }, Icons.Default.Settings),
)

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val s = LocalStrings.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = backStack?.destination?.hierarchy?.any { it.route == tab.graph } == true,
                        onClick = { nav.switchTab(tab.graph) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label(s), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { padding ->
        UpdateDialog()
        NavHost(nav, startDestination = Routes.TAB_SEARCH, modifier = Modifier.padding(padding)) {
            fun openCard(tab: String): (String, String?) -> Unit = { slug, edition -> nav.navigate(Routes.card(tab, slug, edition)) }

            navigation(startDestination = Routes.SEARCH, route = Routes.TAB_SEARCH) {
                composable(Routes.SEARCH) { SearchScreen(onOpenCard = openCard(Routes.SEARCH)) }
                cardScreen(nav, Routes.SEARCH)
            }
            navigation(startDestination = Routes.SCAN, route = Routes.TAB_SCAN) {
                composable(Routes.SCAN) {
                    ScanScreen(
                        onOpenCard = openCard(Routes.SCAN),
                        onOpenSettings = { nav.switchTab(Routes.TAB_SETTINGS) },
                        onReview = { nav.navigate(Routes.SCAN_REVIEW) },
                    )
                }
                composable(Routes.SCAN_REVIEW) {
                    com.gascanner.app.ui.scan.ReviewScreen(onBack = { nav.popBackStack() }, onOpenCard = openCard(Routes.SCAN))
                }
                cardScreen(nav, Routes.SCAN)
            }
            navigation(startDestination = Routes.COLLECTION, route = Routes.TAB_COLLECTION) {
                composable(Routes.COLLECTION) {
                    CollectionScreen(onOpenCard = openCard(Routes.COLLECTION), onCompare = { nav.navigate(Routes.COMPARE) })
                }
                composable(Routes.COMPARE) {
                    CompareScreen(onBack = { nav.popBackStack() }, onOpenCard = openCard(Routes.COLLECTION))
                }
                cardScreen(nav, Routes.COLLECTION)
            }
            navigation(startDestination = Routes.DECKS, route = Routes.TAB_DECKS) {
                composable(Routes.DECKS) { DecksScreen(onOpenDeck = { nav.navigate(Routes.deck(it)) }) }
                composable(Routes.DECK, arguments = listOf(navArgument("deckId") { type = NavType.LongType })) { entry ->
                    DeckDetailScreen(
                        deckId = entry.arguments?.getLong("deckId") ?: 0L,
                        onBack = { nav.popBackStack() },
                        onOpenCard = openCard(Routes.DECKS),
                        onSearchCards = { nav.switchTab(Routes.TAB_SEARCH) },
                        onScanCards = { nav.switchTab(Routes.TAB_SCAN) },
                        onFromCollection = { nav.navigate(Routes.deckFromCollection(entry.arguments?.getLong("deckId") ?: 0L)) },
                    )
                }
                composable(Routes.DECK_FROM_COLLECTION, arguments = listOf(navArgument("deckId") { type = NavType.LongType })) { entry ->
                    com.gascanner.app.ui.decks.FromCollectionScreen(
                        deckId = entry.arguments?.getLong("deckId") ?: 0L,
                        onBack = { nav.popBackStack() },
                        onOpenCard = openCard(Routes.DECKS),
                    )
                }
                cardScreen(nav, Routes.DECKS)
            }
            navigation(startDestination = Routes.SETTINGS, route = Routes.TAB_SETTINGS) {
                composable(Routes.SETTINGS) { SettingsScreen() }
            }
        }
    }
}

private fun NavGraphBuilder.cardScreen(nav: NavHostController, tab: String) {
    composable(
        Routes.cardPattern(tab),
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
}

private fun NavHostController.switchTab(graph: String) {
    navigate(graph) {
        popUpTo(this@switchTab.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pops up once when GitHub has a newer release; "Later" hides it until the next release. */
@Composable
private fun UpdateDialog() {
    val s = LocalStrings.current
    val c = com.gascanner.app.appContainer()
    val state by c.updates.state.collectAsStateWithLifecycle()
    val dismissed by c.settings.dismissedUpdate.collectAsStateWithLifecycle(initialValue = "")
    var hiddenNow by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val available = state as? com.gascanner.app.data.update.UpdateState.Available ?: return
    // "" = setting not loaded yet; null = never dismissed.
    if (hiddenNow || dismissed == "" || dismissed == available.info.version) return
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun later() {
        hiddenNow = true
        scope.launch { c.settings.setDismissedUpdate(available.info.version) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { hiddenNow = true },
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text(s.updateDialogTitle) },
        text = { Text(s.updateDialogText(c.updates.currentVersion, available.info.version)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                hiddenNow = true
                uri.openUri(available.info.apkUrl ?: available.info.pageUrl)
            }) { Text(s.downloadUpdate) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { later() }) { Text(s.later) } },
    )
}
