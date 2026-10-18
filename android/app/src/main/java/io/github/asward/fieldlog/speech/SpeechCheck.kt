package io.github.asward.fieldlog.speech

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** How one setup did in the speech check. */
data class SpeechResult(
    val setup: SpeechSetup,
    val worked: Boolean,
    /** From the start of speech to the first words on screen. */
    val latencyMs: Long? = null,
    /** Share of the prompt's words recognised, 0..1. */
    val accuracy: Float? = null,
    val heard: String? = null,
    val note: String? = null,
    /** On-device failed for want of a language pack; a download may fix it. */
    val needsLanguagePack: Boolean = false,
    /** From starting the recogniser to it listening (model load). Paid once per log, not per utterance. */
    val warmupMs: Long? = null,
) {
    val summary: String
        get() = when {
            !worked -> note ?: "Didn't work"
            else -> buildList {
                latencyMs?.let { add("%.1f s to first words".format(it / 1000f)) }
                warmupMs?.let { add("ready in %.1f s".format(it / 1000f)) }
                accuracy?.let { add("${(it * 100).toInt()}% of words") }
            }.joinToString(" · ")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("setup", setup.key)
        .put("worked", worked)
        .put("latencyMs", latencyMs ?: JSONObject.NULL)
        .put("accuracy", accuracy?.toDouble() ?: JSONObject.NULL)
        .put("heard", heard ?: JSONObject.NULL)
        .put("note", note ?: JSONObject.NULL)
        .put("needsLanguagePack", needsLanguagePack)
        .put("warmupMs", warmupMs ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): SpeechResult? {
            val setup = SpeechSetup.parse(o.optString("setup")) ?: return null
            return SpeechResult(
                setup = setup,
                worked = o.optBoolean("worked"),
                latencyMs = if (o.isNull("latencyMs")) null else o.optLong("latencyMs"),
                accuracy = if (o.isNull("accuracy")) null else o.optDouble("accuracy").toFloat(),
                heard = o.optString("heard").takeIf { !o.isNull("heard") },
                note = o.optString("note").takeIf { !o.isNull("note") },
                needsLanguagePack = o.optBoolean("needsLanguagePack"),
                warmupMs = if (o.isNull("warmupMs") || !o.has("warmupMs")) null else o.optLong("warmupMs"),
            )
        }

        fun listToJson(list: List<SpeechResult>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<SpeechResult> = runCatching {
            val arr = JSONArray(s ?: return emptyList())
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        /** Fastest setup that heard most of the prompt; else the most accurate one that worked. */
        fun best(results: List<SpeechResult>): SpeechResult? {
            val worked = results.filter { it.worked }
            return worked.filter { (it.accuracy ?: 0f) >= 0.6f }.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
                ?: worked.maxByOrNull { it.accuracy ?: 0f }
        }
    }
}

/**
 * Runs one setup: the person says [PROMPT]; we time the first words, score
 * the words heard and, for continuous setups, check results really came as
 * segments of one session.
 */
object SpeechCheck {
    const val PROMPT = "The left joist is soft near the door"
    private const val TIMEOUT_MS = 12_000L

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    fun accuracy(heard: String): Float {
        val want = words(PROMPT)
        val got = words(heard).toMutableList()
        var hit = 0
        for (w in want) {
            val i = got.indexOf(w)
            if (i >= 0) {
                hit++
                got.removeAt(i)
            }
        }
        return hit.toFloat() / want.size
    }

    /** Time allowed for the recogniser to start listening (on-device models can take a while to load). */
    private const val WARMUP_MS = 10_000L

    /**
     * Must be called on the main thread. [ready] fires once the recogniser is
     * actually listening ("speak now"); [level] drives the mic meter after that.
     * The recogniser is always released, even if the check is cancelled.
     */
    suspend fun run(
        context: Context,
        setup: SpeechSetup,
        ready: () -> Unit = {},
        level: (Float) -> Unit = {},
    ): SpeechResult {
        SpeechSupport.unavailableReason(context, setup)?.let { return SpeechResult(setup, false, note = it) }
        val listening = CompletableDeferred<Unit>()
        val done = CompletableDeferred<String?>()
        var speechAt: Long? = null
        var firstWordsAt: Long? = null
        var fellBack = false
        var fatal: String? = null
        var readyAt = 0L
        val started = SystemClock.elapsedRealtime()
        val t = SystemTranscriber(context, setup, object : TranscriberListener {
            override fun onListening(on: Boolean) {
                if (on && !listening.isCompleted) {
                    readyAt = SystemClock.elapsedRealtime()
                    listening.complete(Unit)
                    ready()
                }
            }

            override fun onSpeechStart() {
                if (speechAt == null) speechAt = SystemClock.elapsedRealtime()
            }

            override fun onPartial(text: String) {
                if (text.isNotBlank() && firstWordsAt == null) firstWordsAt = SystemClock.elapsedRealtime()
            }

            override fun onFinal(text: String) {
                if (firstWordsAt == null) firstWordsAt = SystemClock.elapsedRealtime()
                done.complete(text)
            }

            // Ignore levels while the recogniser warms up; they're noise.
            override fun onLevel(rmsDb: Float) {
                if (listening.isCompleted) level(rmsDb)
            }

            override fun onFallback(message: String) {
                fellBack = true
                listening.complete(Unit)
                done.complete(null)
            }

            override fun onFatal(message: String) {
                fatal = message
                listening.complete(Unit)
                done.complete(null)
            }
        })
        var heard: String? = null
        var warmedUp = false
        var segmented = false
        try {
            t.silenceMs = 1200L
            t.start()
            warmedUp = withTimeoutOrNull(WARMUP_MS) { listening.await() } != null
            if (warmedUp) heard = withTimeoutOrNull(TIMEOUT_MS) { done.await() }
            segmented = t.sawSegments
        } finally {
            t.destroy()
        }

        return when {
            fellBack -> SpeechResult(
                setup, false, note = "Language pack missing for on-device speech", needsLanguagePack = true,
            )
            fatal != null -> SpeechResult(setup, false, note = fatal)
            !warmedUp -> SpeechResult(setup, false, note = "Recogniser didn't start listening")
            heard == null -> SpeechResult(setup, false, note = "Heard nothing")
            setup.continuous && !segmented -> SpeechResult(
                setup, false, heard = heard, note = "Continuous listening not supported by this recogniser",
            )
            else -> {
                val from = speechAt ?: readyAt
                SpeechResult(
                    setup = setup,
                    worked = true,
                    latencyMs = ((firstWordsAt ?: SystemClock.elapsedRealtime()) - from).coerceAtLeast(0),
                    accuracy = accuracy(heard!!),
                    heard = heard,
                    warmupMs = (readyAt - started).coerceAtLeast(0),
                )
            }
        }
    }
}
