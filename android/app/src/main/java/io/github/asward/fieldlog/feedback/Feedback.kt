package io.github.asward.fieldlog.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaActionSound
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.asward.fieldlog.R
import io.github.asward.fieldlog.vaults

/** Vibration patterns (off/on milliseconds). */
private object Haptic {
    val SHORT = longArrayOf(0, 40)
    val DOUBLE = longArrayOf(0, 40, 90, 40)
    val TRIPLE = longArrayOf(0, 40, 70, 40, 70, 40)
    val LONG = longArrayOf(0, 180)
}

/**
 * One cue per event (spec §8). Sound and haptic are distinct per mode so
 * mode changes can be told apart without looking.
 */
enum class Cue(internal val sound: Int?, internal val haptic: LongArray?) {
    MIC_READY(R.raw.fb_ready, Haptic.SHORT),
    ENTER_NOTE(R.raw.fb_enter, Haptic.SHORT),
    ENTER_TAG(R.raw.fb_enter_double, Haptic.DOUBLE),
    /** Shutter sound comes from the system; see [Feedback.play]. */
    SNAP(null, Haptic.LONG),
    ENTER_MARKUP(R.raw.fb_enter, Haptic.SHORT),
    MODE_ENDED(R.raw.fb_exit, Haptic.SHORT),
    /** A small acknowledgement (tag added, line scratched). */
    ACK(R.raw.fb_ready, null),
    NOT_ALLOWED(R.raw.fb_buzz, Haptic.TRIPLE),
    COMMITTED(R.raw.fb_chime, Haptic.LONG),
    PUSHED(R.raw.fb_chime2, null),
    FAILED(R.raw.fb_buzz, Haptic.TRIPLE),
}

/**
 * Earcons through [SoundPool] with sonification usage (not the notification
 * stream, which silent mode and Do Not Disturb mute), and explicit
 * [VibrationEffect] patterns (not view haptics, which the system touch
 * setting can turn off). Settings: sound on/off and volume, haptics on/off.
 */
class Feedback(context: Context) {
    private val app = context.applicationContext
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
    private val loaded = HashMap<Int, Int>()
    private val shutter = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        Cue.entries.mapNotNull { it.sound }.distinct().forEach { res ->
            loaded[res] = pool.load(app, res, 1)
        }
    }

    fun play(cue: Cue) {
        val v = app.vaults
        if (v.feedbackSound) {
            if (cue == Cue.SNAP) {
                shutter.play(MediaActionSound.SHUTTER_CLICK)
            } else {
                cue.sound?.let { loaded[it] }?.let { id ->
                    val vol = v.feedbackVolume.coerceIn(0f, 1f)
                    pool.play(id, vol, vol, 1, 0, 1f)
                }
            }
        }
        if (v.feedbackHaptics) {
            cue.haptic?.let { pattern ->
                val vib = vibrator ?: return@let
                if (!vib.hasVibrator()) return@let
                runCatching {
                    @Suppress("DEPRECATION")
                    vib.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
                }
            }
        }
    }

    fun release() {
        pool.release()
        shutter.release()
    }
}
