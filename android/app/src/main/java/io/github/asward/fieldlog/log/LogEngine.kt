package io.github.asward.fieldlog.log

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.asward.fieldlog.entry.LogWidget
import io.github.asward.fieldlog.entry.Shortcuts
import io.github.asward.fieldlog.feedback.Cue
import io.github.asward.fieldlog.feedback.Feedback
import io.github.asward.fieldlog.speech.SpeechSupport
import io.github.asward.fieldlog.speech.SystemTranscriber
import io.github.asward.fieldlog.speech.Transcriber
import io.github.asward.fieldlog.speech.TranscriberListener
import io.github.asward.fieldlog.sync.SyncWorker
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fieldlog_core.CommitInfo
import uniffi.fieldlog_core.LogCommand
import uniffi.fieldlog_core.LogEvent
import uniffi.fieldlog_core.LogMode
import uniffi.fieldlog_core.LogSession
import uniffi.fieldlog_core.LogState

/** How the log screen was opened. */
sealed interface OpenRequest {
    /** New log in [project]; resumes instead if the vault already has an open log. */
    data class New(val project: String) : OpenRequest
    data object Resume : OpenRequest
    data class Edit(val path: String) : OpenRequest
}

/** What happened at `end log`. */
sealed interface SaveStatus {
    data object Saving : SaveStatus
    data object NothingToSave : SaveStatus
    data object Discarded : SaveStatus
    data class Committed(val info: CommitInfo, val pushing: Boolean) : SaveStatus
    data class Pushed(val info: CommitInfo) : SaveStatus
    /** Committed; the push failed and will retry in the background. */
    data class PushQueued(val info: CommitInfo, val reason: String) : SaveStatus
    data class Failed(val reason: String) : SaveStatus

    val done: Boolean
        get() = this !is Saving && !(this is Committed && pushing)
}

/** The log screen's camera, while it is visible. */
fun interface PhotoTaker {
    fun take(onBytes: (ByteArray) -> Unit, fail: (String) -> Unit)
}

/**
 * The open log for the whole process: the core [LogSession], speech, feedback
 * and saving. It lives outside the activity so listening continues with the
 * screen locked (the microphone foreground service keeps the process up).
 * All state is Compose-observable; call from the main thread.
 */
class LogEngine(context: Context) {
    private val app = context.applicationContext
    private val scope = MainScope()
    /** Core calls run one at a time, in order. */
    private val core = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val feedback by lazy { Feedback(app) }

    var session by mutableStateOf<LogSession?>(null)
        private set
    var vaultId by mutableStateOf<String?>(null)
        private set
    var projectTitle by mutableStateOf("")
        private set
    var state by mutableStateOf<LogState?>(null)
        private set
    var partial by mutableStateOf("")
        private set
    var listening by mutableStateOf(false)
        private set
    var micOn by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(-2f)
        private set
    /** A short line under the banner ("#pump #valve", "Removed …"). */
    var message by mutableStateOf<String?>(null)
        private set
    var save by mutableStateOf<SaveStatus?>(null)
        private set
    var fallback by mutableStateOf<String?>(null)
    var speechLabel by mutableStateOf("")
        private set
    /** The mic stopped after the idle timeout; the log is still open. */
    var idleStopped by mutableStateOf(false)
        private set

    /** Set by the log screen while it is in front with the camera bound. */
    var camera: PhotoTaker? = null
    var visible = false

    private var speech: Transcriber? = null
    private var readyCued = false
    private var lastSpeech = SystemClock.elapsedRealtime()
    private var idleJob: Job? = null
    private var messageJob: Job? = null

    val isOpen: Boolean get() = session != null

    // ------------------------------------------------------------ open / close

    suspend fun open(vaultId: String, req: OpenRequest): Result<Unit> {
        val cur = session
        if (cur != null) {
            return if (this.vaultId == vaultId && (req !is OpenRequest.Edit || req.path == state?.path)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("A log is already open in $projectTitle. End it first."))
            }
        }
        val vaults = app.vaults
        val r = withContext(core) {
            runCatching {
                val v = vaults.open(vaultId)
                val phrases = vaults.voicePhrases
                val resumed: Boolean
                val s = when (req) {
                    is OpenRequest.New -> {
                        resumed = v.openLog() != null
                        if (resumed) LogSession.resume(v, phrases) else LogSession.start(v, req.project, phrases)
                    }
                    OpenRequest.Resume -> {
                        resumed = true
                        LogSession.resume(v, phrases)
                    }
                    is OpenRequest.Edit -> {
                        resumed = false
                        LogSession.edit(v, req.path, phrases)
                    }
                }
                val st = s.state()
                val title = v.projects().firstOrNull { it.slug == st.project }?.title ?: st.project
                Triple(s, st, title to resumed)
            }
        }
        return r.map { (s, st, info) ->
            session = s
            state = st
            this.vaultId = vaultId
            projectTitle = info.first
            save = null
            partial = ""
            idleStopped = false
            vaults.touchRecent(vaultId, st.project)
            if (info.second && st.entries.isNotEmpty()) flash("Resumed the open log")
            LogNotifications.cancelAlerts(app)
        }
    }

    /** Reload the open log's state, e.g. after phrases changed. */
    fun refreshPhrases() {
        val s = session ?: return
        val p = app.vaults.voicePhrases
        scope.launch { withContext(core) { s.setPhrases(p) } }
    }

    // ------------------------------------------------------------ microphone

    fun startMic() {
        if (session == null) return
        val vaults = app.vaults
        val t = speech ?: SystemTranscriber(app, vaults.speechSetup, listener).also { speech = it }
        t.silenceMs = (state?.mode ?: LogMode.STANDBY).silenceMs
        readyCued = false
        micOn = true
        idleStopped = false
        lastSpeech = SystemClock.elapsedRealtime()
        updateSpeechLabel()
        t.start()
        LogService.start(app)
        LogNotifications.cancelAlerts(app)
        startIdleWatch()
    }

    fun stopMic() {
        speech?.stop()
        micOn = false
        listening = false
        partial = ""
        idleJob?.cancel()
        LogService.stop(app)
    }

    fun toggleMic() = if (micOn) stopMic() else startMic()

    private fun releaseSpeech() {
        stopMic()
        speech?.destroy()
        speech = null
    }

    private fun startIdleWatch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (isActive) {
                delay(15_000)
                val limit = app.vaults.idleMinutes * 60_000L
                if (micOn && SystemClock.elapsedRealtime() - lastSpeech > limit) {
                    stopMic()
                    idleStopped = true
                    LogNotifications.idle(app, projectTitle)
                    break
                }
            }
        }
    }

    private fun updateSpeechLabel() {
        val t = speech ?: return
        speechLabel = "${t.activeEngine.label} · ${if (t.setup.continuous) "continuous" else "restart mode"}"
    }

    val speechAvailable: Boolean
        get() = SpeechSupport.cloudAvailable(app) || SpeechSupport.onDeviceAvailable(app)

    private val listener = object : TranscriberListener {
        override fun onFinal(text: String) = onFinalText(text)
        override fun onPartial(text: String) = onPartialText(text)
        override fun onSpeechStart() {
            lastSpeech = SystemClock.elapsedRealtime()
        }

        override fun onListening(on: Boolean) {
            listening = on
            if (on && !readyCued) {
                readyCued = true
                feedback.play(Cue.MIC_READY)
            }
        }

        override fun onLevel(rmsDb: Float) {
            level = rmsDb
        }

        override fun onFallback(message: String) {
            fallback = message
            updateSpeechLabel()
        }

        override fun onFatal(message: String) {
            micOn = false
            LogService.stop(app)
            flash(message)
        }
    }

    // ------------------------------------------------------------ speech → machine

    /** Command seen in the previous partial; firing needs two in a row. */
    private var candidate: LogCommand? = null
    /** Fired from partials (and when); its final result is dropped. */
    private var firedEarly: Pair<LogCommand, Long>? = null

    private fun onPartialText(text: String) {
        partial = text
        if (text.isBlank()) {
            candidate = null
            return
        }
        lastSpeech = SystemClock.elapsedRealtime()
        val s = session ?: return
        if (firedEarly != null) return
        val c = runCatching { s.peekCommand(text) }.getOrNull()
        if (c != null && c == candidate) {
            firedEarly = c to SystemClock.elapsedRealtime()
            candidate = null
            partial = ""
            command(c)
            return
        }
        candidate = c
    }

    private fun onFinalText(text: String) {
        candidate = null
        val s = session ?: return
        val early = firedEarly
        firedEarly = null
        if (early != null && SystemClock.elapsedRealtime() - early.second < 5000) {
            // The partial already fired this; drop the same words when they finalise.
            val again = runCatching { s.peekCommand(text) }.getOrNull()
            if (again == null || again == early.first) return
        }
        exec { it.handleUtterance(text) }
    }

    // ------------------------------------------------------------ actions

    /** Touch backup for a command. `Snap` takes the photo directly. */
    fun command(c: LogCommand) {
        if (c == LogCommand.Snap) {
            takePhoto()
            return
        }
        if (c == LogCommand.EndLog) {
            endLog()
            return
        }
        exec { it.apply(c) }
    }

    fun type(text: String) = exec { it.addText(text) }

    fun endLog() {
        save = SaveStatus.Saving
        exec { listOf(LogEvent.LogEnded(it.endLog())) }
    }

    fun discard() {
        val s = session ?: return
        scope.launch {
            val r = withContext(core) { runCatching { s.discard() } }
            r.onFailure { flash(it.userMessage()) }
            closeOut()
            save = SaveStatus.Discarded
            feedback.play(Cue.MODE_ENDED)
        }
    }

    fun editText(entry: Int, block: Int, text: String) =
        exec { it.editText(entry.toUInt(), block.toUInt(), text); emptyList() }

    fun editPhoto(entry: Int, block: Int, annotation: String, markup: List<String>) =
        exec { it.editPhoto(entry.toUInt(), block.toUInt(), annotation, markup); emptyList() }

    fun deleteBlock(entry: Int, block: Int) = exec { it.deleteBlock(entry.toUInt(), block.toUInt()); emptyList() }

    fun deleteEntry(entry: Int) = exec { it.deleteEntry(entry.toUInt()); emptyList() }

    fun removeTag(tag: String) = exec { it.removeTag(tag); emptyList() }

    /** Voice "snap", the snap button, a volume key or the widget's camera. */
    fun takePhoto() {
        val cam = camera
        if (!visible || cam == null) {
            // Spec §9: no relaunch; no sound or haptic; just a notification.
            LogNotifications.unlockToSnap(app)
            return
        }
        cam.take(
            onBytes = { bytes ->
                scope.launch { feedback.play(Cue.SNAP) }
                exec { it.addPhoto(bytes) }
            },
            fail = { msg ->
                scope.launch {
                    feedback.play(Cue.FAILED)
                    flash(msg)
                }
            },
        )
    }

    private fun exec(block: (LogSession) -> List<LogEvent>) {
        val s = session ?: return
        lastSpeech = SystemClock.elapsedRealtime()
        scope.launch {
            val r = withContext(core) { runCatching { block(s) } }
            r.onSuccess { events -> events.forEach(::handle) }
                .onFailure {
                    feedback.play(Cue.FAILED)
                    if (save == SaveStatus.Saving) save = SaveStatus.Failed(it.userMessage())
                    flash(it.userMessage())
                }
            if (session === s) {
                state = withContext(core) { if (s.isClosed()) state else s.state() }
            }
        }
    }

    private fun handle(e: LogEvent) {
        val vaults = app.vaults
        when (e) {
            is LogEvent.ModeChanged -> {
                e.mode.enterCue?.let(feedback::play)
                speech?.silenceMs = e.mode.silenceMs
                LogService.update(app, "$projectTitle · ${e.mode.title}")
            }
            LogEvent.PhotoRequested -> takePhoto()
            is LogEvent.Tagged -> {
                feedback.play(Cue.ACK)
                flash(e.tags.joinToString(" ") { "#$it" })
            }
            is LogEvent.Scratched -> {
                feedback.play(Cue.ACK)
                flash("Removed “${e.removed}”")
            }
            LogEvent.NothingToScratch -> flash("Nothing to scratch")
            is LogEvent.Undone -> {
                feedback.play(Cue.MODE_ENDED)
                flash("Removed: ${e.summary.ifEmpty { "entry" }}")
            }
            LogEvent.NothingToUndo -> flash("Nothing to undo")
            is LogEvent.Ignored -> flash("Standby: say “${vaults.voicePhrases.spoken(LogCommand.Note)}” to start a note")
            is LogEvent.NotAllowed -> {
                feedback.play(Cue.NOT_ALLOWED)
                flash(vaults.voicePhrases.hint(e.allowed))
            }
            is LogEvent.LogEnded -> onEnded(e.commit)
            LogEvent.Cancelled, is LogEvent.PhotoAdded, is LogEvent.TextAdded,
            is LogEvent.AnnotationAdded, is LogEvent.MarkupAdded -> Unit
        }
    }

    private fun onEnded(commit: CommitInfo?) {
        val id = vaultId
        closeOut()
        if (commit == null) {
            save = SaveStatus.NothingToSave
            feedback.play(Cue.MODE_ENDED)
            return
        }
        feedback.play(Cue.COMMITTED)
        if (id == null) {
            save = SaveStatus.Committed(commit, pushing = false)
            return
        }
        Shortcuts.publish(app, id)
        val hasRemote = app.vaults.get(id)?.remoteUrl != null
        save = SaveStatus.Committed(commit, pushing = hasRemote)
        if (!hasRemote) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SyncWorker.syncNow(app, id) } }
            r.onSuccess {
                save = SaveStatus.Pushed(commit)
                feedback.play(Cue.PUSHED)
            }.onFailure {
                SyncWorker.enqueue(app, id)
                save = SaveStatus.PushQueued(commit, it.userMessage())
            }
        }
    }

    /** The log is finished (ended or discarded): release speech and notifications. */
    private fun closeOut() {
        releaseSpeech()
        session = null
        partial = ""
        fallback = null
        LogNotifications.cancelAlerts(app)
        LogWidget.refresh(app)
    }

    /** Clear the result once the screen has shown it. */
    fun acknowledgeSave() {
        if (save?.done == true) save = null
    }

    /**
     * "End log" from a notification. Works after the process was restarted:
     * the open log is resumed from disk first.
     */
    fun endFromNotification(done: () -> Unit) {
        scope.launch {
            if (session == null) {
                val vaults = app.vaults
                val id = withContext(core) {
                    vaults.list().firstOrNull { runCatching { vaults.open(it.id).openLog() != null }.getOrDefault(false) }?.id
                }
                if (id == null || open(id, OpenRequest.Resume).isFailure) {
                    LogNotifications.cancelAlerts(app)
                    done()
                    return@launch
                }
            }
            endLog()
            // Give the commit (and a quick push) a moment before the receiver finishes.
            delay(8000)
            done()
        }
    }

    private fun flash(msg: String) {
        message = msg
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(3500)
            message = null
        }
    }
}
