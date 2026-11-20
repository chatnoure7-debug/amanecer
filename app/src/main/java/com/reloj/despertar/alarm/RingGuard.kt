package com.reloj.despertar.alarm

import android.content.Context

data class RingRecord(val alarmId: Int, val startedAtMs: Long, val originalVolume: Int)

/**
 * Apunte persistente de "hay una alarma sonando y nadie ha pulsado Parar".
 * Si Android mata el servicio o la app, el vigilante (una alarma de respaldo del sistema) lo lee y
 * vuelve a poner el sonido por donde iba. Solo se borra al pulsar Parar o Posponer.
 */
object RingGuard {
    private const val PREFS = "ring_guard"
    private const val MAX_AGE_MS = 8 * 60 * 60 * 1000L

    fun begin(context: Context, alarmId: Int, startedAtMs: Long, originalVolume: Int) {
        prefs(context).edit()
            .putInt("id", alarmId)
            .putLong("started", startedAtMs)
            .putInt("vol", originalVolume)
            .commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    fun current(context: Context): RingRecord? {
        val p = prefs(context)
        if (!p.contains("id")) return null
        val started = p.getLong("started", 0L)
        if (System.currentTimeMillis() - started > MAX_AGE_MS) {
            clear(context)
            return null
        }
        return RingRecord(p.getInt("id", -1), started, p.getInt("vol", -1))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
