package io.github.asward.fieldlog.data

import android.content.Context
import android.content.SharedPreferences
import io.github.asward.fieldlog.entry.SessionWidget
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import uniffi.fieldlog_core.Author
import uniffi.fieldlog_core.GitCredentials
import uniffi.fieldlog_core.S3Credentials
import uniffi.fieldlog_core.Session
import uniffi.fieldlog_core.SessionSettings
import uniffi.fieldlog_core.Vault

/** A vault known to this device. Secrets live in [SecretStore], not here. */
data class VaultRecord(
    val id: String,
    val name: String,
    val remoteUrl: String? = null,
    /** Pinned SSH host key (hex sha256), learned on first sync. */
    val hostKey: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("remoteUrl", remoteUrl ?: JSONObject.NULL)
        .put("hostKey", hostKey ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = VaultRecord(
            id = o.getString("id"),
            name = o.getString("name"),
            remoteUrl = o.optString("remoteUrl").takeIf { it.isNotEmpty() && it != "null" },
            hostKey = o.optString("hostKey").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

/** Secrets for one vault, as edited in settings. */
data class VaultSecrets(
    val sshPrivateKey: String? = null,
    val sshPublicKey: String? = null,
    val sshPassphrase: String? = null,
    val httpsUsername: String? = null,
    val httpsToken: String? = null,
    val s3AccessKey: String? = null,
    val s3SecretKey: String? = null,
)

/**
 * Device-side registry of vaults, preferences and open core objects.
 * Core calls block; call these from a background dispatcher.
 */
class Vaults(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("fieldlog", Context.MODE_PRIVATE)
    val secrets = SecretStore(context)
    private val opened = HashMap<String, Vault>()

    // ------------------------------------------------------------ registry

    fun list(): List<VaultRecord> {
        val arr = JSONArray(prefs.getString(KEY_VAULTS, "[]"))
        return (0 until arr.length()).map { VaultRecord.fromJson(arr.getJSONObject(it)) }
    }

    private fun save(list: List<VaultRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_VAULTS, arr.toString()).apply()
    }

    fun get(id: String): VaultRecord? = list().firstOrNull { it.id == id }

    fun update(record: VaultRecord) {
        save(list().map { if (it.id == record.id) record else it })
    }

    var currentId: String?
        get() = prefs.getString(KEY_CURRENT, null)?.takeIf { id -> list().any { it.id == id } }
            ?: list().firstOrNull()?.id
        set(v) {
            prefs.edit().putString(KEY_CURRENT, v).apply()
            SessionWidget.refresh(context)
        }

    fun current(): VaultRecord? = currentId?.let(::get)

    fun dir(id: String) = File(context.filesDir, "vaults/$id")

    // ------------------------------------------------------------ preferences

    var author: Author
        get() = Author(
            prefs.getString("author_name", null) ?: "FieldLog",
            prefs.getString("author_email", null) ?: "fieldlog@localhost",
        )
        set(a) {
            prefs.edit().putString("author_name", a.name).putString("author_email", a.email).apply()
            synchronized(opened) { opened.values.forEach { it.setAuthor(a) } }
        }

    /** Spoken prefix for commands ("log"); empty = bare commands. */
    var commandPrefix: String
        get() = prefs.getString("command_prefix", "") ?: ""
        set(v) = prefs.edit().putString("command_prefix", v.trim()).apply()

    var silenceTimeoutSecs: Int
        get() = prefs.getInt("silence_secs", 30)
        set(v) = prefs.edit().putInt("silence_secs", v).apply()

    fun sessionSettings() = SessionSettings(
        commandPrefix.takeIf { it.isNotBlank() },
        silenceTimeoutSecs.coerceAtLeast(0).toUInt(),
    )

    /** Volume keys act as shutter (up) and mic toggle (down) during a session. */
    var volumeKeys: Boolean
        get() = prefs.getBoolean("volume_keys", true)
        set(v) = prefs.edit().putBoolean("volume_keys", v).apply()

    fun stickyProject(vaultId: String): String? = prefs.getString("sticky_$vaultId", null)

    fun setStickyProject(vaultId: String, project: String) {
        prefs.edit().putString("sticky_$vaultId", project).apply()
        touchRecent(vaultId, project)
        SessionWidget.refresh(context)
    }

    /** Most recently used projects in a vault, newest first. */
    fun recentProjects(vaultId: String): List<String> =
        prefs.getString("recent_$vaultId", "")!!.split('\n').filter { it.isNotEmpty() }

    fun touchRecent(vaultId: String, project: String) {
        val list = listOf(project) + recentProjects(vaultId).filter { it != project }
        prefs.edit().putString("recent_$vaultId", list.take(8).joinToString("\n")).apply()
    }

    fun lastSync(vaultId: String): String? = prefs.getString("sync_status_$vaultId", null)

    fun setLastSync(vaultId: String, status: String) =
        prefs.edit().putString("sync_status_$vaultId", status).apply()

    // ------------------------------------------------------------ secrets

    fun secretsFor(id: String) = VaultSecrets(
        sshPrivateKey = secrets.get("$id.ssh_private"),
        sshPublicKey = secrets.get("$id.ssh_public"),
        sshPassphrase = secrets.get("$id.ssh_pass"),
        httpsUsername = secrets.get("$id.https_user"),
        httpsToken = secrets.get("$id.https_token"),
        s3AccessKey = secrets.get("$id.s3_key"),
        s3SecretKey = secrets.get("$id.s3_secret"),
    )

    fun setSecrets(id: String, s: VaultSecrets) {
        secrets.put("$id.ssh_private", s.sshPrivateKey)
        secrets.put("$id.ssh_public", s.sshPublicKey)
        secrets.put("$id.ssh_pass", s.sshPassphrase)
        secrets.put("$id.https_user", s.httpsUsername)
        secrets.put("$id.https_token", s.httpsToken)
        secrets.put("$id.s3_key", s.s3AccessKey)
        secrets.put("$id.s3_secret", s.s3SecretKey)
    }

    fun gitCredentials(id: String): GitCredentials {
        val s = secretsFor(id)
        return GitCredentials(
            s.sshPrivateKey, s.sshPassphrase, s.httpsUsername, s.httpsToken, get(id)?.hostKey,
        )
    }

    fun s3Credentials(id: String): S3Credentials? {
        val s = secretsFor(id)
        val k = s.s3AccessKey ?: return null
        val sec = s.s3SecretKey ?: return null
        return S3Credentials(k, sec, null)
    }

    // ------------------------------------------------------------ core objects

    fun open(id: String): Vault = synchronized(opened) {
        opened.getOrPut(id) { Vault.open(dir(id).absolutePath, author) }
    }

    fun openCurrent(): Vault? = currentId?.let(::open)

    fun createLocal(name: String): VaultRecord {
        val rec = VaultRecord(UUID.randomUUID().toString(), name.trim().ifEmpty { "Notes" })
        val v = Vault.create(dir(rec.id).absolutePath, rec.name, author)
        synchronized(opened) { opened[rec.id] = v }
        save(list() + rec)
        currentId = rec.id
        return rec
    }

    /** Clone `url` into a new vault. Secrets must be stored first under [id]. */
    fun cloneRemote(id: String, name: String, url: String): VaultRecord {
        val dir = dir(id)
        dir.deleteRecursively()
        val v = Vault.cloneRemote(url, dir.absolutePath, name.trim().ifEmpty { "Notes" }, gitCredentials(id), author)
        val rec = VaultRecord(id, v.config().name, url)
        synchronized(opened) { opened[id] = v }
        save(list().filter { it.id != id } + rec)
        currentId = id
        return rec
    }

    fun setRemote(id: String, url: String?) {
        val rec = get(id) ?: return
        if (!url.isNullOrBlank()) open(id).setRemote(url.trim())
        update(rec.copy(remoteUrl = url?.trim()?.ifEmpty { null }))
    }

    fun remove(id: String) {
        synchronized(opened) { opened.remove(id)?.close() }
        dir(id).deleteRecursively()
        secrets.removePrefix("$id.")
        save(list().filter { it.id != id })
    }

    fun newSession(vaultId: String, project: String): Session =
        Session(open(vaultId), project, sessionSettings())

    private companion object {
        const val KEY_VAULTS = "vaults"
        const val KEY_CURRENT = "current_vault"
    }
}
