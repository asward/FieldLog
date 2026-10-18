package io.github.asward.fieldlog.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.asward.fieldlog.entry.LogWidget
import io.github.asward.fieldlog.speech.SpeechResult
import io.github.asward.fieldlog.speech.SpeechSetup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import uniffi.fieldlog_core.Author
import uniffi.fieldlog_core.GitCredentials
import uniffi.fieldlog_core.S3Credentials
import uniffi.fieldlog_core.Vault
import uniffi.fieldlog_core.VoicePhrases
import uniffi.fieldlog_core.defaultVoicePhrases

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
            LogWidget.refresh(context)
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

    /** Spoken phrases per command (spec §3), stored on this device. */
    var voicePhrases: VoicePhrases
        get() = prefs.getString("voice_phrases", null)?.let(::phrasesFromJson) ?: defaultVoicePhrases()
        set(v) = prefs.edit().putString("voice_phrases", phrasesToJson(v)).apply()

    fun resetVoicePhrases() = prefs.edit().remove("voice_phrases").apply()

    /** Stop the mic after this many minutes without speech; the log stays open. */
    var idleMinutes: Int
        get() = prefs.getInt("idle_minutes", 20)
        set(v) = prefs.edit().putInt("idle_minutes", v.coerceIn(1, 240)).apply()

    /** Recogniser and listening mode, chosen in Settings → Speech. */
    var speechSetup: SpeechSetup
        get() = SpeechSetup.parse(prefs.getString("speech_setup", null)) ?: SpeechSetup.DEFAULT
        set(v) = prefs.edit().putString("speech_setup", v.key).apply()

    /** Last speech-check results on this phone. */
    var speechResults: List<SpeechResult>
        get() = SpeechResult.listFromJson(prefs.getString("speech_results", null))
        set(v) = prefs.edit().putString("speech_results", SpeechResult.listToJson(v)).apply()

    /** The speech check has run (or was dismissed) on this phone. */
    val speechChecked: Boolean get() = prefs.contains("speech_results")

    fun dismissSpeechCheck() {
        if (!speechChecked) prefs.edit().putString("speech_results", "[]").apply()
    }

    // Feedback (spec §8): earcons and vibration for every state change.
    var feedbackSound: Boolean
        get() = prefs.getBoolean("fb_sound", true)
        set(v) = prefs.edit().putBoolean("fb_sound", v).apply()

    /** 0..1, relative to the device's sonification volume. */
    var feedbackVolume: Float
        get() = prefs.getFloat("fb_volume", 0.8f)
        set(v) = prefs.edit().putFloat("fb_volume", v.coerceIn(0f, 1f)).apply()

    var feedbackHaptics: Boolean
        get() = prefs.getBoolean("fb_haptics", true)
        set(v) = prefs.edit().putBoolean("fb_haptics", v).apply()

    /** Volume keys act as shutter (up) and mic toggle (down) on the log screen. */
    var volumeKeys: Boolean
        get() = prefs.getBoolean("volume_keys", true)
        set(v) = prefs.edit().putBoolean("volume_keys", v).apply()

    /** The active project: picked by tapping it; New log and entry points use it. */
    fun activeProject(vaultId: String): String? = prefs.getString("sticky_$vaultId", null)

    fun setActiveProject(vaultId: String, project: String) {
        prefs.edit().putString("sticky_$vaultId", project).apply()
        touchRecent(vaultId, project)
        LogWidget.refresh(context)
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
        opened.getOrPut(id) { Vault.open(dir(id).absolutePath, author).also(::named) }
    }

    fun openCurrent(): Vault? = currentId?.let(::open)

    fun createLocal(name: String): VaultRecord {
        val rec = VaultRecord(UUID.randomUUID().toString(), name.trim().ifEmpty { "Notes" })
        val v = Vault.create(dir(rec.id).absolutePath, rec.name, author).also(::named)
        synchronized(opened) { opened[rec.id] = v }
        save(list() + rec)
        currentId = rec.id
        return rec
    }

    /** Clone `url` into a new vault. Secrets must be stored first under [id]. */
    fun cloneRemote(id: String, name: String, url: String, knownHost: String? = null): VaultRecord {
        val dir = dir(id)
        dir.deleteRecursively()
        val creds = gitCredentials(id).let { if (knownHost != null) it.copy(knownHostSha256 = knownHost) else it }
        val v = Vault.cloneRemote(url, dir.absolutePath, name.trim().ifEmpty { "Notes" }, creds, author)
            .also(::named)
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

    /** Conflict copies are named after the device, e.g. `….conflict-pixel-8-….md`. */
    private fun named(v: Vault) = v.setDeviceName(Build.MODEL ?: "phone")

    private companion object {
        const val KEY_VAULTS = "vaults"
        const val KEY_CURRENT = "current_vault"

        fun phrasesToJson(p: VoicePhrases): String = JSONObject().apply {
            put("note", JSONArray(p.note))
            put("endNote", JSONArray(p.endNote))
            put("tag", JSONArray(p.tag))
            put("endTags", JSONArray(p.endTags))
            put("snap", JSONArray(p.snap))
            put("endAnnotation", JSONArray(p.endAnnotation))
            put("endMarkup", JSONArray(p.endMarkup))
            put("scratchThat", JSONArray(p.scratchThat))
            put("cancel", JSONArray(p.cancel))
            put("undo", JSONArray(p.undo))
            put("endLog", JSONArray(p.endLog))
        }.toString()

        fun phrasesFromJson(s: String): VoicePhrases? = runCatching {
            val o = JSONObject(s)
            val d = defaultVoicePhrases()
            fun list(key: String, fallback: List<String>): List<String> {
                val a = o.optJSONArray(key) ?: return fallback
                val l = (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() }
                return l.ifEmpty { fallback }
            }
            VoicePhrases(
                note = list("note", d.note),
                endNote = list("endNote", d.endNote),
                tag = list("tag", d.tag),
                endTags = list("endTags", d.endTags),
                snap = list("snap", d.snap),
                endAnnotation = list("endAnnotation", d.endAnnotation),
                endMarkup = list("endMarkup", d.endMarkup),
                scratchThat = list("scratchThat", d.scratchThat),
                cancel = list("cancel", d.cancel),
                undo = list("undo", d.undo),
                endLog = list("endLog", d.endLog),
            )
        }.getOrNull()
    }
}
