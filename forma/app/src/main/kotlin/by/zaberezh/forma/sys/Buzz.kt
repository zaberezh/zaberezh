package by.zaberezh.forma.sys

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Вибрация с пометкой «будильник» (USAGE_ALARM). Без неё Android 12+ молча игнорирует вибрацию приложения,
 * которое не на экране, — то есть ровно тогда, когда срабатывают будильник по парам и таймеры (экран выключен).
 * Будильная вибрация работает и в беззвучном режиме.
 */
object Buzz {
    val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java).defaultVibrator
        else c.getSystemService(Vibrator::class.java)

    /** [repeat] — индекс в [pattern], с которого повторять (-1 — один раз). */
    @Suppress("DEPRECATION")
    fun alarm(c: Context, pattern: LongArray, repeat: Int = -1) = runCatching {
        val effect = VibrationEffect.createWaveform(pattern, repeat)
        val v = vibrator(c)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else v.vibrate(effect, ALARM_AUDIO)
    }

    fun stop(c: Context) = runCatching { vibrator(c).cancel() }
}
