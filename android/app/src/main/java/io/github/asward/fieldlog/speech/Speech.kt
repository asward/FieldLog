package io.github.asward.fieldlog.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Which recogniser turns speech into text. */
enum class SpeechEngine(val label: String) {
    ON_DEVICE("On-device"),
    CLOUD("Cloud"),
    // Future: SELF_HOSTED (a Whisper-compatible HTTP endpoint) behind the same Transcriber interface.
}

/**
 * A recogniser plus how it listens. Continuous listening keeps one recognition
 * running for the whole log (Android 13+); restart mode is the v1 loop that
 * starts a new recognition after every pause.
 */
data class SpeechSetup(val engine: SpeechEngine, val continuous: Boolean) {
    val label: String get() = "${engine.label} · ${if (continuous) "continuous" else "restart mode"}"
    val key: String get() = "${engine.name}:${if (continuous) "continuous" else "restart"}"

    companion object {
        val ALL = listOf(
            SpeechSetup(SpeechEngine.ON_DEVICE, true),
            SpeechSetup(SpeechEngine.CLOUD, true),
            SpeechSetup(SpeechEngine.ON_DEVICE, false),
            SpeechSetup(SpeechEngine.CLOUD, false),
        )

        val DEFAULT: SpeechSetup
            get() = SpeechSetup(SpeechEngine.ON_DEVICE, SpeechSupport.continuousSupported())

        fun parse(key: String?): SpeechSetup? = ALL.firstOrNull { it.key == key }
    }
}

object SpeechSupport {
    /** Continuous (segmented) listening needs Android 13. */
    fun continuousSupported(): Boolean = Build.VERSION.SDK_INT >= 33

    fun cloudAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun onDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Why a setup can't run on this phone, or null if it might. */
    fun unavailableReason(context: Context, setup: SpeechSetup): String? = when {
        setup.continuous && !continuousSupported() -> "Needs Android 13"
        setup.engine == SpeechEngine.ON_DEVICE && !onDeviceAvailable(context) -> "No on-device recogniser"
        setup.engine == SpeechEngine.CLOUD && !cloudAvailable(context) -> "No speech service"
        else -> null
    }

    /** Ask the on-device recogniser to download its language pack. */
    fun triggerLanguageDownload(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !onDeviceAvailable(context)) return false
        return runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
            r.triggerModelDownload(intent)
            r.destroy()
            true
        }.getOrDefault(false)
    }
}

/** Callbacks from a [Transcriber], on the main thread. */
interface TranscriberListener {
    /** The microphone is (or stopped) listening. */
    fun onListening(on: Boolean) {}
    fun onSpeechStart() {}
    fun onPartial(text: String) {}
    /** A finished utterance (or segment). */
    fun onFinal(text: String)
    /** Mic level in dB, roughly -2..10. */
    fun onLevel(rmsDb: Float) {}
    /** On-device recognition couldn't run (e.g. language pack missing); now using cloud. */
    fun onFallback(message: String) {}
    fun onFatal(message: String) {}
}

/**
 * Speech to text. Implementations: [SystemTranscriber] (Android's recogniser,
 * on-device or cloud). A self-hosted engine can be added behind this interface.
 * No audio is recorded or kept; only recognised text leaves the recogniser.
 */
interface Transcriber {
    val setup: SpeechSetup
    /** The engine actually in use (may differ after a fallback). */
    val activeEngine: SpeechEngine
    /** True once results have arrived as segments of one continuous session. */
    val sawSegments: Boolean
    /** Silence that ends an utterance. Shorter in standby, longer for dictation. */
    var silenceMs: Long
    fun start()
    fun stop()
    fun destroy()
}
