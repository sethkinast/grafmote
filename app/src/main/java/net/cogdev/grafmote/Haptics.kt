package net.cogdev.grafmote

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * Single haptic path for every button press: a short, subtle tick (~15ms).
 * Callers pass the per-TV toggle state; when disabled this is a no-op
 * (visual pressed states still show — only the vibration is gated).
 *
 * VIBRATE is a normal permission (manifest only, no runtime prompt).
 */
object Haptics {
    private const val TICK_MS = 15L
    /** Subtle amplitude (1-255 scale on API 26+). */
    private const val TICK_AMPLITUDE = 64

    fun tick(view: View, enabled: Boolean) {
        if (!enabled) return
        try {
            val vib = vibrator(view.context) ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(TICK_MS, TICK_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(TICK_MS)
            }
        } catch (_: Exception) {
            // Haptics must never break a button press.
        }
    }

    private fun vibrator(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }
}
