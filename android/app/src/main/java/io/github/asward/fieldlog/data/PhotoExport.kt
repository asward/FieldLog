package io.github.asward.fieldlog.data

import android.content.Context
import android.net.Uri
import io.github.asward.fieldlog.vaults
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes every photo on this device to a zip the user picked, keeping vault paths. */
object PhotoExport {
    /** Returns how many photos were written. Blocks; call from a background dispatcher. */
    fun write(context: Context, vaultId: String, uri: Uri): Int {
        val vaults = context.vaults
        val root = vaults.dir(vaultId)
        val files = vaults.open(vaultId).localPhotos()
        var n = 0
        val out = context.contentResolver.openOutputStream(uri) ?: error("Couldn't open the export file")
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            for (rel in files) {
                val f = File(root, rel)
                if (!f.isFile) continue
                // Photos are already JPEG/PNG; storing them saves time.
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                n++
            }
        }
        return n
    }
}
