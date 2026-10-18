package io.github.asward.fieldlog.session

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.asward.fieldlog.feedback.Cue
import io.github.asward.fieldlog.feedback.Feedback
import io.github.asward.fieldlog.speech.SystemTranscriber
import io.github.asward.fieldlog.speech.Transcriber
import io.github.asward.fieldlog.speech.TranscriberListener
import io.github.asward.fieldlog.sync.SyncWorker
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fieldlog_core.Command
import uniffi.fieldlog_core.Session
import uniffi.fieldlog_core.SessionEvent
import uniffi.fieldlog_core.VoiceItem
import uniffi.fieldlog_core.parseUtterance

/**
 * Drives one session: speech in, camera, touch backups, the silence timer,
 * and eyes-free feedback (tones and haptics) so it works with the phone in a
 * pocket or on a bench.
 */
class SessionController(
    private val activity: ComponentActivity,
    val vaultId: String,
    val vaultName: String,
    val projectTitle: String,
    private val session: Session,
    private val onCommitted: () -> Unit,
) {
    var state by mutableStateOf(session.state())
        private set
    var partial by mutableStateOf("")
        private set
    var listening by mutableStateOf(false)
        private set
    var micOn by mutableStateOf(true)
        private set
    var banner by mutableStateOf<String?>(null)
        private set
    var secondsToAutoSave by mutableStateOf<Int?>(null)
        private set
    /** e.g. "On-device · continuous"; changes if on-device falls back to cloud. */
    var speechLabel by mutableStateOf("")
        private set
    /** Why on-device speech fell back to cloud, until dismissed. */
    var fallback by mutableStateOf<String?>(null)
    /** Mic level (RMS dB) for the meter. */
    var level by mutableFloatStateOf(-2f)
        private set

    private val scope = activity.lifecycleScope
    /** Core calls run one at a time, in order. */
    private val core = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var lastActivity = SystemClock.elapsedRealtime()
    private var ticker: Job? = null
    private var bannerJob: Job? = null
    private val feedback = Feedback(activity)
    /** "Mic ready" plays once per time the mic is switched on, not on every recogniser restart. */
    private var readyCued = false

    val camera = Camera(activity, activity)
    private val speech: Transcriber = SystemTranscriber(
        activity,
        activity.vaults.speechSetup,
        object : TranscriberListener {
            override fun onFinal(text: String) = onFinalText(text)
            override fun onPartial(text: String) = onPartialText(text)
            override fun onSpeechStart() = touch()
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
                updateLabel()
            }
            override fun onFatal(message: String) {
                micOn = false
                show(message)
            }
        },
    ).also { it.silenceMs = 1500L }

    init {
        updateLabel()
    }

    private fun updateLabel() {
        val engine = speech.activeEngine.label
        speechLabel = "$engine · ${if (speech.setup.continuous) "continuous" else "restart mode"}"
    }

    // ------------------------------------------------------------ speech → commands

    /** Command seen in the previous partial; firing needs two in a row. */
    private var candidate: Command? = null
    /** Command already fired from partials, and when; its final result is dropped. */
    private var firedEarly: Pair<Command, Long>? = null

    private fun commandIn(text: String): Command? =
        (parseUtterance(text, state.settings.commandPrefix).singleOrNull() as? VoiceItem.Command)?.command

    private fun onPartialText(text: String) {
        partial = text
        if (text.isBlank()) {
            candidate = null
            return
        }
        touch()
        if (firedEarly != null) return
        val c = commandIn(text)
        if (c != null && c == candidate) {
            // Stable across two partials: act now instead of waiting for silence.
            firedEarly = c to SystemClock.elapsedRealtime()
            candidate = null
            partial = ""
            exec { listOf(session.apply(c)) }
            return
        }
        candidate = c
    }

    private fun onFinalText(text: String) {
        candidate = null
        val early = firedEarly
        firedEarly = null
        if (early != null && SystemClock.elapsedRealtime() - early.second < 5000 && commandIn(text) == early.first) {
            return // already handled from the partial result
        }
        exec { session.handleUtterance(text) }
    }

    val speechAvailable: Boolean
        get() = io.github.asward.fieldlog.speech.SpeechSupport.unavailableReason(activity, speech.setup) == null ||
            io.github.asward.fieldlog.speech.SpeechSupport.cloudAvailable(activity)

    private fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    // ------------------------------------------------------------ actions

    fun snap() {
        touch()
        camera.takePhoto(
            onBytes = { bytes ->
                feedback.play(Cue.SNAP)
                run1 { session.addPhoto(bytes) }
            },
            fail = { msg -> scope.launch { show(msg) } },
        )
    }

    fun next() = run1 { session.commit() }
    fun scratch() = run1 { session.apply(Command.ScratchThat) }
    fun undo() = run1 { session.apply(Command.Undo) }
    fun tag(tag: String) = run1 { session.apply(Command.Tag(tag)) }
    fun type(text: String) = run1 { session.addText(text) }
    fun discard() = run1 {
        session.discard()
        SessionEvent.NothingToScratch
    }

    fun toggleMic() {
        micOn = !micOn
        readyCued = false
        if (micOn) speech.start() else speech.stop()
    }

    // ------------------------------------------------------------ lifecycle

    fun onResume(hasMicPermission: Boolean) {
        touch()
        if (micOn && hasMicPermission && speechAvailable) speech.start()
        ticker?.cancel()
        ticker = scope.launch { silenceLoop() }
    }

    fun onPause() {
        speech.stop()
        readyCued = false
        ticker?.cancel()
        secondsToAutoSave = null
    }

    fun destroy() {
        speech.destroy()
        camera.shutdown()
        feedback.release()
        core.close()
    }

    // ------------------------------------------------------------ internals

    private suspend fun silenceLoop() {
        while (scope.isActive) {
            delay(1000)
            val timeout = activity.vaults.silenceTimeoutSecs
            if (timeout <= 0 || state.blocks.isEmpty()) {
                secondsToAutoSave = null
                continue
            }
            val idle = ((SystemClock.elapsedRealtime() - lastActivity) / 1000).toInt()
            val left = timeout - idle
            secondsToAutoSave = left.coerceAtLeast(0)
            if (left <= 0) {
                touch()
                run1 { session.onSilence() }
            }
        }
    }

    private fun run1(block: () -> SessionEvent) = exec { listOf(block()) }

    private fun exec(block: () -> List<SessionEvent>) {
        touch()
        scope.launch {
            val result = withContext(core) { runCatching(block) }
            result.onSuccess { events -> events.forEach(::handle) }
                .onFailure {
                    feedback.play(Cue.FAILED)
                    show(it.userMessage())
                }
            state = withContext(core) { session.state() }
        }
    }

    private fun handle(e: SessionEvent) {
        when (e) {
            is SessionEvent.PhotoRequested -> snap()
            is SessionEvent.Committed -> {
                feedback.play(Cue.COMMITTED)
                show("Saved: ${e.info.entry.summary.ifEmpty { "photo" }}")
                SyncWorker.enqueue(activity, vaultId)
                onCommitted()
            }
            is SessionEvent.NothingToCommit -> show("Nothing to save yet")
            is SessionEvent.Scratched -> {
                feedback.play(Cue.MODE_ENDED)
                show("Removed “${e.removed}”")
            }
            is SessionEvent.NothingToScratch -> Unit
            is SessionEvent.Undone -> {
                feedback.play(Cue.MODE_ENDED)
                show("Undid: ${e.entry.summary}")
                SyncWorker.enqueue(activity, vaultId)
            }
            is SessionEvent.NothingToUndo -> show("Nothing to undo")
            is SessionEvent.Tagged -> {
                feedback.play(Cue.ACK)
                show("#${e.tag}")
            }
            is SessionEvent.TextAdded, is SessionEvent.PhotoAdded -> Unit
        }
    }

    private fun show(msg: String) {
        banner = msg
        bannerJob?.cancel()
        bannerJob = scope.launch {
            delay(3000)
            banner = null
        }
    }
}
