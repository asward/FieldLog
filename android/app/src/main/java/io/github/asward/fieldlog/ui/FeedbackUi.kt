package io.github.asward.fieldlog.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Banner colours per state (spec §8). */
object BannerColors {
    val Standby = Color(0xFF6B6B6B)
    val Note = Color(0xFF2F6FDB)
    val Tags = Color(0xFF7B4FC9)
    val Annotate = Color(0xFFD99A00)
    val Markup = Color(0xFFE07A2E)
    val Saved = Color(0xFF2E8B57)
    val Failed = Color(0xFFC62828)
    val Off = Color(0xFF424242)
}

/**
 * Full-width banner in the mode's colour, with an optional hint line (e.g.
 * the commands the mode accepts) and the mic level meter.
 */
@Composable
fun ModeBanner(
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    hint: String? = null,
    level: Float? = null,
) {
    val c by animateColorAsState(color, tween(180), label = "banner")
    Column(
        modifier.fillMaxWidth().background(c).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (level != null) {
                Spacer(Modifier.width(12.dp))
                LevelMeter(level, Modifier.width(72.dp).height(10.dp))
            }
        }
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Mic level from the recogniser's RMS dB (about -2..10). */
@Composable
fun LevelMeter(rmsDb: Float, modifier: Modifier = Modifier, color: Color = Color.White) {
    val target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    val v by animateFloatAsState(target, tween(90), label = "level")
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(v).background(color))
    }
}
