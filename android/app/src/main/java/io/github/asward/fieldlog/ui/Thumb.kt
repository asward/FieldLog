package io.github.asward.fieldlog.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.asward.fieldlog.session.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads an image off the main thread. [resolve] returns a local file path
 * (and may download it first, e.g. from S3).
 */
@Composable
fun Thumb(key: String, edge: Int, modifier: Modifier = Modifier, resolve: () -> String?) {
    val bmp by produceState<Bitmap?>(null, key) {
        value = withContext(Dispatchers.IO) {
            runCatching { resolve()?.let { Photos.thumbnail(it, edge) } }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = "Photo", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
