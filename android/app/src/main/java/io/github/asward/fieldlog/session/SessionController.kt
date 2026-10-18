package io.github.asward.fieldlog.session

import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
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

    private val scope = activity.lifecycleScope
    /** Core calls run one at a time, in order. */
    private val core = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var lastActivity = SystemClock.elapsedRealtime()
    private var ticker: Job? = null
    private var bannerJob: Job? = null
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()
    private val shutter = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    val camera = Camera(activity, activity)
    private val speech = SpeechLoop(
        activity,
        onUtterance = { text -> exec { session.handleUtterance(text) } },
        onPartial = { partial = it },
        onActivity = ::touch,
        onListening = { listening = it },
        onFatal = { msg ->
            micOn = false
            show(msg)
        },
    )

    val speechAvailable get() = speech.available

    private fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    // ------------------------------------------------------------ actions

    fun snap() {
        touch()
        camera.takePhoto(
            onBytes = { bytes ->
                shutter.play(MediaActionSound.SHUTTER_CLICK)
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
        if (micOn) speech.start() else speech.stop()
    }

    // ------------------------------------------------------------ lifecycle

    fun onResume(hasMicPermission: Boolean) {
        touch()
        if (micOn && hasMicPermission && speech.available) speech.start()
        ticker?.cancel()
        ticker = scope.launch { silenceLoop() }
    }

    fun onPause() {
        speech.stop()
        ticker?.cancel()
        secondsToAutoSave = null
    }

    fun destroy() {
        speech.destroy()
        camera.shutdown()
        shutter.release()
        tones?.release()
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
                .onFailure { show(it.userMessage()) }
            state = withContext(core) { session.state() }
        }
    }

    private fun handle(e: SessionEvent) {
        when (e) {
            is SessionEvent.PhotoRequested -> snap()
            is SessionEvent.Committed -> {
                tone(ToneGenerator.TONE_PROP_ACK)
                haptic()
                show("Saved: ${e.info.entry.summary.ifEmpty { "photo" }}")
                SyncWorker.enqueue(activity, vaultId)
                onCommitted()
            }
            is SessionEvent.NothingToCommit -> show("Nothing to save yet")
            is SessionEvent.Scratched -> {
                tone(ToneGenerator.TONE_PROP_NACK)
                show("Removed “${e.removed}”")
            }
            is SessionEvent.NothingToScratch -> Unit
            is SessionEvent.Undone -> {
                tone(ToneGenerator.TONE_PROP_NACK)
                haptic()
                show("Undid: ${e.entry.summary}")
                SyncWorker.enqueue(activity, vaultId)
            }
            is SessionEvent.NothingToUndo -> show("Nothing to undo")
            is SessionEvent.Tagged -> {
                tone(ToneGenerator.TONE_PROP_BEEP)
                show("#${e.tag}")
            }
            is SessionEvent.TextAdded, is SessionEvent.PhotoAdded -> Unit
        }
    }

    private fun tone(t: Int) {
        tones?.startTone(t, 150)
    }

    private fun haptic() {
        activity.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
