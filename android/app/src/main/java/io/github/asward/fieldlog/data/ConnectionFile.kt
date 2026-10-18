package io.github.asward.fieldlog.data

import android.content.Context
import android.net.Uri
import io.github.asward.fieldlog.vaults
import java.util.UUID
import org.json.JSONObject

/**
 * Dev upgrade path: a vault's connection (remote URL, pinned host key and
 * credentials) as a JSON file, so a fresh install can reconnect without
 * generating and registering a new deploy key. Settings stay behind.
 *
 * The file holds the private key in plain text. Keep it somewhere private
 * and delete it after importing.
 */
object ConnectionFile {
    private const val FORMAT = "fieldlog-connection"

    /** Blocks; call from a background dispatcher. */
    fun export(context: Context, vaultId: String, uri: Uri) {
        val vaults = context.vaults
        val rec = vaults.get(vaultId) ?: error("Vault not found")
        val url = rec.remoteUrl ?: error("This vault has no git remote to reconnect to")
        val s = vaults.secretsFor(vaultId)
        val o = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("name", rec.name)
            .put("remoteUrl", url)
            .put("hostKey", rec.hostKey ?: JSONObject.NULL)
            .put("sshPrivateKey", s.sshPrivateKey ?: JSONObject.NULL)
            .put("sshPublicKey", s.sshPublicKey ?: JSONObject.NULL)
            .put("sshPassphrase", s.sshPassphrase ?: JSONObject.NULL)
            .put("httpsUsername", s.httpsUsername ?: JSONObject.NULL)
            .put("httpsToken", s.httpsToken ?: JSONObject.NULL)
            .put("s3AccessKey", s.s3AccessKey ?: JSONObject.NULL)
            .put("s3SecretKey", s.s3SecretKey ?: JSONObject.NULL)
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the file")
        out.use { it.write(o.toString(2).toByteArray()) }
    }

    /**
     * Read a connection file and clone its remote as a new vault on this device.
     * Blocks (network); call from a background dispatcher. Returns the vault name.
     */
    fun import(context: Context, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Couldn't open the file")
        val o = runCatching { JSONObject(text) }.getOrElse { error("Not a FieldLog connection file") }
        if (o.optString("format") != FORMAT) error("Not a FieldLog connection file")
        fun str(k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
        val url = str("remoteUrl") ?: error("The file has no remote URL")
        val vaults = context.vaults
        val id = UUID.randomUUID().toString()
        vaults.setSecrets(
            id,
            VaultSecrets(
                sshPrivateKey = str("sshPrivateKey"),
                sshPublicKey = str("sshPublicKey"),
                sshPassphrase = str("sshPassphrase"),
                httpsUsername = str("httpsUsername"),
                httpsToken = str("httpsToken"),
                s3AccessKey = str("s3AccessKey"),
                s3SecretKey = str("s3SecretKey"),
            ),
        )
        val rec = try {
            vaults.cloneRemote(id, str("name") ?: "Notes", url, knownHost = str("hostKey"))
        } catch (e: Exception) {
            vaults.secrets.removePrefix("$id.")
            throw e
        }
        str("hostKey")?.let { vaults.update(rec.copy(hostKey = it)) }
        return rec.name
    }
}
