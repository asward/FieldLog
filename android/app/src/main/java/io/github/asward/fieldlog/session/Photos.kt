package io.github.asward.fieldlog.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max

object Photos {
    /** Long edge of stored photos. Keeps a git-backed vault from ballooning. */
    const val MAX_EDGE = 2048

    /**
     * Decode, apply rotation, downscale to [MAX_EDGE] and re-encode as JPEG.
     * Re-encoding also drops EXIF (including GPS) from the stored file.
     */
    fun normalize(bytes: ByteArray, rotationDegrees: Int = 0, quality: Int = 85): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return bytes // not something we can decode; keep as-is
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return bytes
        val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        val m = Matrix()
        if (scale < 1f) m.postScale(scale, scale)
        if (rotationDegrees != 0) m.postRotate(rotationDegrees.toFloat())
        val out = if (m.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val buf = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, buf)
        if (out !== decoded) out.recycle()
        decoded.recycle()
        return buf.toByteArray()
    }

    /** Small bitmap for thumbnails. */
    fun thumbnail(path: String, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
