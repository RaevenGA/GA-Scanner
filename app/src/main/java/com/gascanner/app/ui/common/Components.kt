package com.gascanner.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gascanner.app.data.settings.ViewMode
import com.gascanner.app.i18n.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        // The outer app scaffold owns the bottom bar; avoid double insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.back)
                        }
                    }
                },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        content = content,
    )
}

@Composable
fun CardThumb(url: String?, modifier: Modifier = Modifier, width: Dp = 48.dp) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .size(width = width, height = width * 1.4f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Card image tile for grid views, with quantity / foil / price badges. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardTile(
    imageUrl: String?,
    title: String,
    quantity: Int,
    foil: Boolean,
    price: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_RATIO)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(title, Modifier.align(Alignment.Center).padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
            Badge("×$quantity", Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (foil) Badge("✦", Modifier.align(Alignment.TopStart).padding(4.dp))
            if (price != null) Badge(price, Modifier.align(Alignment.BottomCenter).padding(4.dp))
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

const val CARD_RATIO = 0.716f

/** Cycles list → 2 per row → 3 per row. */
@Composable
fun ViewModeButton(mode: ViewMode, onChange: (ViewMode) -> Unit) {
    val next = when (mode) {
        ViewMode.LIST -> ViewMode.GRID2
        ViewMode.GRID2 -> ViewMode.GRID3
        ViewMode.GRID3 -> ViewMode.LIST
    }
    IconButton(onClick = { onChange(next) }) {
        Icon(
            when (mode) {
                ViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                ViewMode.GRID2 -> Icons.Default.GridView
                ViewMode.GRID3 -> Icons.Default.ViewModule
            },
            contentDescription = LocalStrings.current.viewMode,
        )
    }
}

@Composable
fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(-1) }) { Icon(Icons.Default.Remove, contentDescription = s.removeOne) }
        Text("$quantity", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(1) }) { Icon(Icons.Default.Add, contentDescription = s.addOne) }
    }
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun SmallNote(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
