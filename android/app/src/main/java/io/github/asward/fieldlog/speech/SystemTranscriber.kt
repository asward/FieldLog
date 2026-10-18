package io.github.asward.fieldlog.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android's [SpeechRecognizer], on-device or cloud.
 *
 * - Continuous: one segmented session (Android 13+) hands back each pause as a
 *   segment, so nothing is lost between utterances. If the recogniser ignores
 *   segmentation it behaves like restart mode, and [sawSegments] stays false.
 * - Restart mode (v1): one recognition per utterance, restarted after each.
 *
 * Must be used from the main thread.
 */
class SystemTranscriber(
    private val context: Context,
    override val setup: SpeechSetup,
    private val listener: TranscriberListener,
) : Transcriber, RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var engine = setup.engine
    private var inSegmentedSession = false

    override val activeEngine: SpeechEngine get() = engine
    override var sawSegments: Boolean = false
        private set

    override var silenceMs: Long = 1500L
        set(v) {
            if (field == v) return
            field = v
            // A segmented session reads its silence settings once; restart it so the
            // new window applies. Restart mode picks it up on the next utterance.
            if (running && inSegmentedSession) {
                recognizer?.cancel()
                restart(30)
            }
        }

    private val wantsSegments: Boolean
        get() = setup.continuous && Build.VERSION.SDK_INT >= 33

    override fun start() {
        if (running) return
        running = true
        listen()
    }

    override fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        inSegmentedSession = false
        listener.onListening(false)
        listener.onPartial("")
    }

    override fun destroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun ensure(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = if (engine == SpeechEngine.ON_DEVICE && SpeechSupport.onDeviceAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            engine = SpeechEngine.CLOUD
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(this)
        recognizer = r
        return r
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, engine == SpeechEngine.ON_DEVICE)
        if (Build.VERSION.SDK_INT >= 33) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        if (wantsSegments) {
            // One session; it ends only after a long silence, and each pause of
            // `silenceMs` closes a segment.
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        } else {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        }
    }

    private fun listen() {
        if (!running) return
        try {
            inSegmentedSession = wantsSegments
            ensure().startListening(intent())
        } catch (e: Exception) {
            restart(1000)
        }
    }

    private fun restart(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        if (running) main.postDelayed({ listen() }, delayMs)
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------ RecognitionListener

    override fun onReadyForSpeech(params: Bundle?) = listener.onListening(true)
    override fun onBeginningOfSpeech() = listener.onSpeechStart()
    override fun onRmsChanged(rmsdB: Float) = listener.onLevel(rmsdB)
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onPartialResults(partialResults: Bundle?) {
        first(partialResults)?.let(listener::onPartial)
    }

    override fun onSegmentResults(segmentResults: Bundle) {
        sawSegments = true
        listener.onPartial("")
        first(segmentResults)?.let(listener::onFinal)
    }

    override fun onEndOfSegmentedSession() {
        inSegmentedSession = false
        listener.onPartial("")
        restart(50)
    }

    override fun onResults(results: Bundle?) {
        inSegmentedSession = false
        listener.onPartial("")
        first(results)?.let(listener::onFinal)
        restart(50)
    }

    override fun onError(error: Int) {
        inSegmentedSession = false
        listener.onPartial("")
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                running = false
                listener.onListening(false)
                listener.onFatal("Microphone permission is needed for voice notes.")
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                if (engine == SpeechEngine.ON_DEVICE) {
                    engine = SpeechEngine.CLOUD
                    recognizer?.destroy()
                    recognizer = null
                    listener.onFallback(
                        "On-device speech isn't ready for this language (language pack missing). Using cloud for now.",
                    )
                    restart(100)
                } else {
                    running = false
                    listener.onListening(false)
                    listener.onFatal("Speech recognition isn't available for this language.")
                }
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                recognizer?.destroy()
                recognizer = null
                restart(500)
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(50)
            else -> restart(750)
        }
    }
}
