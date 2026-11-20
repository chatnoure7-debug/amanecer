package com.reloj.despertar.alarm

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** Sube el canal "alarma" del móvil al volumen elegido y lo devuelve a como estaba al terminar. */
object AlarmVolume {
    /** Devuelve el volumen anterior, o -1 si el sistema no deja cambiarlo. */
    fun raiseTo(context: Context, percent: Int): Int {
        val am = context.getSystemService(AudioManager::class.java) ?: return -1
        return try {
            val old = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (max * percent / 100f).roundToInt().coerceIn(1, max)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            old
        } catch (_: SecurityException) {
            -1
        }
    }

    fun restore(context: Context, old: Int) {
        if (old < 0) return
        try {
            context.getSystemService(AudioManager::class.java)
                ?.setStreamVolume(AudioManager.STREAM_ALARM, old, 0)
        } catch (_: SecurityException) {
        }
    }
}
