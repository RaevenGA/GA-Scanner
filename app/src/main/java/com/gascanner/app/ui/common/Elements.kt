package com.gascanner.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gascanner.app.i18n.Strings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Colour per element, used for the dots and filter chips (the database only gives element names). */
fun elementColor(element: String): Color = when (element.uppercase()) {
    "FIRE" -> Color(0xFFE53935)
    "WATER" -> Color(0xFF1E88E5)
    "WIND" -> Color(0xFF43A047)
    "ARCANE" -> Color(0xFF8E24AA)
    "ASTRA" -> Color(0xFFEC407A)
    "CRUX" -> Color(0xFFFFB300)
    "LUXEM" -> Color(0xFFFDD835)
    "UMBRA" -> Color(0xFF4527A0)
    "EXIA" -> Color(0xFFB71C1C)
    "NEOS" -> Color(0xFF00ACC1)
    "TERA" -> Color(0xFF8D6E63)
    else -> Color(0xFF9E9E9E) // Norm and anything new
}

/** "FIRE" → "Fire". */
fun prettyName(raw: String): String = raw.lowercase().split(' ', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/** Official element icon from the Grand Archive site (kept in the image cache once loaded). */
fun elementIconUrl(element: String): String = "https://cdn2.gatcg.com/i/elements/${element.lowercase()}.png"

/** Official speed symbol; tinted red for fast and blue for slow, as on the cards. */
const val SPEED_ICON_URL = "https://cdn2.gatcg.com/i/effects/thunder.svg"

/** The element's official icon; a coloured dot while it loads or when offline. */
@Composable
fun ElementDot(element: String, size: Dp = 14.dp) {
    val painter = coil.compose.rememberAsyncImagePainter(elementIconUrl(element))
    val loaded = painter.state is coil.compose.AsyncImagePainter.State.Success
    Box(Modifier.size(size)) {
        if (!loaded) {
            Box(
                Modifier.matchParentSize().background(elementColor(element), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
            )
        }
        androidx.compose.foundation.Image(painter, contentDescription = prettyName(element), modifier = Modifier.matchParentSize())
    }
}

/** Coloured dot with the element's name. */
@Composable
fun ElementBadge(element: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ElementDot(element, 20.dp)
        Spacer(Modifier.width(4.dp))
        Text(prettyName(element), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The database stores speed as true (fast) / false (slow). */
fun isFastSpeed(speed: JsonElement?): Boolean? {
    val p = speed as? JsonPrimitive ?: return null
    p.booleanOrNull?.let { return it }
    return when (p.contentOrNull?.trim()?.lowercase()) {
        "fast", "true" -> true
        "slow", "false" -> false
        else -> null
    }
}

fun speedLabel(s: Strings, speed: JsonElement?): String? = isFastSpeed(speed)?.let { if (it) s.speedFast else s.speedSlow }

@Composable
fun SpeedBadge(speed: JsonElement?, s: Strings, modifier: Modifier = Modifier) {
    val fast = isFastSpeed(speed) ?: return
    val tint = if (fast) Color(0xFFD32F2F) else Color(0xFF1E88E5)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val painter = coil.compose.rememberAsyncImagePainter(SPEED_ICON_URL)
        if (painter.state is coil.compose.AsyncImagePainter.State.Error) {
            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        } else {
            androidx.compose.foundation.Image(
                painter,
                contentDescription = null,
                modifier = Modifier.size(width = 11.dp, height = 16.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(if (fast) s.speedFast else s.speedSlow, style = MaterialTheme.typography.bodyMedium)
    }
}
