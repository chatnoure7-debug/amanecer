package com.reloj.despertar.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Curvas de despertar. Todo en funciones puras para poder testearlo sin Android.
 *
 * x = progreso de la rampa, 0.0 (silencio casi total) .. 1.0 (nivel final).
 */
object WakeCurve {
    /** Nivel inicial relativo al final. -50 dB queda por debajo del ruido de una habitación. */
    const val START_DB = -50.0

    fun progress(t: Double, ramp: Double): Double = (t / ramp).coerceIn(0.0, 1.0)

    /** Subida LINEAL EN dB: el oído percibe el volumen de forma logarítmica, así la sensación es de subida pareja. */
    fun gainDb(t: Double, ramp: Double): Double = START_DB * (1.0 - progress(t, ramp))

    fun gain(t: Double, ramp: Double): Double = 10.0.pow(gainDb(t, ramp) / 20.0)

    fun lerp(a: Double, b: Double, x: Double): Double = a + (b - a) * x

    fun smooth(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return c * c * (3.0 - 2.0 * c)
    }

    /** Segundos entre notas: empieza muy espaciado (2,6 s) y se acelera hasta 0,55 s. */
    fun noteInterval(x: Double): Double = lerp(2.6, 0.55, smooth(x))

    /** Ataque de cada nota: al principio muy suave (350 ms) para no provocar sobresalto; luego 40 ms. */
    fun attack(x: Double): Double = lerp(0.35, 0.04, smooth(x))

    /** Duración de la caída de cada nota. */
    fun decayTau(x: Double): Double = lerp(1.6, 0.9, x)

    /** Brillo: peso de los armónicos 2, 3 y 4 (más brillo = más presencia en altavoces de móvil). */
    fun h2(x: Double) = lerp(0.25, 0.55, x)
    fun h3(x: Double) = lerp(0.08, 0.30, x)
    fun h4(x: Double) = lerp(0.02, 0.15, x)

    /**
     * Notas disponibles (semitonos sobre Do4 = 261,6 Hz). Todo son acordes de Do mayor / pentatónica:
     * sin disonancias. Máximo Mi5 = 659 Hz de fundamental: registro medio-grave, nunca estridente.
     */
    fun scale(x: Double): IntArray = when {
        x < 0.34 -> intArrayOf(0, 4, 7)
        x < 0.67 -> intArrayOf(0, 4, 7, 9, 12)
        else -> intArrayOf(0, 4, 7, 9, 12, 14, 16)
    }

    fun midiToHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
}

/**
 * Sintetizador en tiempo real. No usa archivos de audio: genera las notas matemáticamente,
 * así el despertar es idéntico en cualquier móvil y el APK pesa poco.
 *
 * Diseño (ver docs/CIENCIA.md):
 *  - Melódico (arpegios consonantes), no pitidos: menos inercia del sueño percibida.
 *  - Empieza casi inaudible y sube en dB durante `rampSeconds`.
 *  - Mientras sube: más notas por segundo, más brillo, aparece una segunda voz (quinta) y un colchón sostenido.
 *  - Después de la rampa se mantiene en el nivel final hasta que lo pares.
 */
class WakeSynth(
    private val rampSeconds: Double,
    private val sampleRate: Int = 44100,
) {
    private class Voice(
        val freq: Double,
        val amp: Double,
        val attack: Double,
        val tau: Double,
        val h2: Double,
        val h3: Double,
        val h4: Double,
        val norm: Double,
    ) {
        var age = 0.0
        var phase = 0.0
    }

    private val voices = ArrayList<Voice>()
    private var sampleIndex = 0L
    private var nextNoteAt = 0.0
    private var noteIndex = 0
    private var padPhaseA = 0.0
    private var padPhaseB = 0.0

    val elapsedSeconds: Double get() = sampleIndex.toDouble() / sampleRate

    /** Rellena `count` muestras mono en [-1, 1]. */
    fun render(out: FloatArray, count: Int) {
        val dt = 1.0 / sampleRate
        val twoPi = 2.0 * PI
        for (i in 0 until count) {
            val t = sampleIndex * dt
            val x = WakeCurve.progress(t, rampSeconds)
            if (t >= nextNoteAt) {
                triggerNote(x)
                nextNoteAt += WakeCurve.noteInterval(x)
            }

            var s = 0.0
            var vi = voices.size - 1
            while (vi >= 0) {
                val v = voices[vi]
                if (v.age >= LIFE) {
                    voices.removeAt(vi)
                } else {
                    val env = envelope(v)
                    v.phase += twoPi * v.freq * dt
                    if (v.phase > twoPi) v.phase -= twoPi
                    val p = v.phase
                    val wave = sin(p) + v.h2 * sin(2 * p) + v.h3 * sin(3 * p) + v.h4 * sin(4 * p)
                    s += env * v.amp * wave / v.norm
                    v.age += dt
                }
                vi--
            }

            // Colchón sostenido (Do4 + Sol4) que entra a partir del 30 % de la rampa
            val padLevel = WakeCurve.smooth((x - 0.30) / 0.70) * PAD_AMP
            if (padLevel > 0.0) {
                padPhaseA += twoPi * 261.63 * dt
                padPhaseB += twoPi * 392.00 * dt
                if (padPhaseA > twoPi) padPhaseA -= twoPi
                if (padPhaseB > twoPi) padPhaseB -= twoPi
                s += padLevel * (sin(padPhaseA) + sin(padPhaseB))
            }

            // La ganancia (curva en dB) se aplica ANTES del limitador suave
            out[i] = tanh(s * WakeCurve.gain(t, rampSeconds)).toFloat()
            sampleIndex++
        }
    }

    private fun envelope(v: Voice): Double {
        val a = if (v.age < v.attack) 0.5 * (1.0 - cos(PI * v.age / v.attack)) else exp(-(v.age - v.attack) / v.tau)
        val tail = if (v.age > LIFE - RELEASE) ((LIFE - v.age) / RELEASE).coerceAtLeast(0.0) else 1.0
        return a * tail
    }

    private fun triggerNote(x: Double) {
        val pool = WakeCurve.scale(x)
        // arpegio ida y vuelta: sube y baja por las notas disponibles
        val period = if (pool.size > 1) 2 * (pool.size - 1) else 1
        val k = noteIndex % period
        val idx = if (k < pool.size) k else period - k
        val semitone = pool[idx]
        val accent = if (noteIndex % 4 == 0) 1.0 else 0.8

        addVoice(WakeCurve.midiToHz(60 + semitone), NOTE_AMP * accent, x)
        // segunda voz (quinta justa arriba) cuando la rampa va por más del 66 %
        if (x > 0.66 && semitone <= 12) {
            addVoice(WakeCurve.midiToHz(60 + semitone + 7), NOTE_AMP * accent * 0.5, x)
        }
        noteIndex++
    }

    private fun addVoice(freq: Double, amp: Double, x: Double) {
        val h2 = WakeCurve.h2(x)
        val h3 = WakeCurve.h3(x)
        val h4 = WakeCurve.h4(x)
        val norm = sqrt(1.0 + h2 * h2 + h3 * h3 + h4 * h4)
        voices.add(Voice(freq, amp, WakeCurve.attack(x), WakeCurve.decayTau(x), h2, h3, h4, norm))
    }

    private companion object {
        const val LIFE = 6.0      // vida máxima de una nota (s)
        const val RELEASE = 0.25   // fundido final para evitar clics
        const val NOTE_AMP = 0.30
        const val PAD_AMP = 0.06
    }
}
