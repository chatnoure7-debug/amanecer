package com.reloj.despertar.alarm

import android.os.VibrationEffect

/** Niveles de vibración. Todos son pulsos con pausas largas; el más suave es un golpecito leve cada 2 s. */
object Vibe {
    val names = listOf("Apagada", "Muy suave", "Suave", "Media", "Fuerte")
    private val amplitudes = intArrayOf(0, 35, 80, 150, 255)
    private val onMs = longArrayOf(0, 250, 350, 500, 700)
    private val offMs = longArrayOf(0, 1750, 1650, 1500, 1300)

    /** Vibración que se repite hasta que se cancela. null si el nivel es "Apagada". */
    fun repeating(level: Int, hasAmplitudeControl: Boolean): VibrationEffect? {
        val l = level.coerceIn(0, 4)
        if (l == 0) return null
        val timings = longArrayOf(0, onMs[l], offMs[l])
        return if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, amplitudes[l], 0), 0)
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
    }

    /** Un solo pulso, para que al elegir el nivel se note cómo es. */
    fun sample(level: Int, hasAmplitudeControl: Boolean): VibrationEffect? {
        val l = level.coerceIn(0, 4)
        if (l == 0) return null
        val amp = if (hasAmplitudeControl) amplitudes[l] else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(maxOf(onMs[l], 400L), amp)
    }
}
