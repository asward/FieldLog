package io.github.asward.fieldlog.session

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
 * Continuous dictation on top of [SpeechRecognizer]: each final result is
 * handed to [onUtterance] and listening restarts. Prefers the on-device
 * recogniser. No audio is recorded or kept — only recognised text leaves the
 * recogniser.
 *
 * Must be used from the main thread.
 */
class SpeechLoop(
    private val context: Context,
    private val onUtterance: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    /** Speech started or continued; resets the silence timer. */
    private val onActivity: () -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onFatal: (String) -> Unit,
) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var useOnDevice = true

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (running) return
        running = true
        listen()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        onListening(false)
        onPartial("")
    }

    fun destroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun ensure(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = if (useOnDevice && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(this)
        recognizer = r
        return r
    }

    private fun listen() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Let people pause mid-thought without ending the utterance too early.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        try {
            ensure().startListening(intent)
        } catch (e: Exception) {
            restart(1000)
        }
    }

    private fun restart(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        if (running) main.postDelayed({ listen() }, delayMs)
    }

    override fun onReadyForSpeech(params: Bundle?) = onListening(true)
    override fun onBeginningOfSpeech() = onActivity()
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            onActivity()
            onPartial(text)
        }
    }

    override fun onResults(results: Bundle?) {
        onPartial("")
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) onUtterance(text)
        restart(50)
    }

    override fun onError(error: Int) {
        onPartial("")
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                running = false
                onListening(false)
                onFatal("Microphone permission is needed for voice notes.")
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                if (useOnDevice) {
                    // On-device model missing for this language: fall back.
                    useOnDevice = false
                    recognizer?.destroy()
                    recognizer = null
                    restart(100)
                } else {
                    running = false
                    onListening(false)
                    onFatal("Speech recognition isn't available for this language.")
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
