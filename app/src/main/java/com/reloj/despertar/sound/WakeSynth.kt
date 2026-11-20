package com.reloj.despertar.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

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

/** Parámetros de timbre y ritmo de cada sonido. El "amanecer" es el original. */
private class Style(
    val baseMidi: Int,
    val intervalScale: Double,
    val attackScale: Double,
    val decayScale: Double,
    val brightScale: Double,
    val bell: Boolean,
    val fifth: Boolean,
    val noteAmp: Double,
    val notesFrom: Double,
    val padLowHz: Double,
    val padHighHz: Double,
    val padAmp: Double,
    val noiseAmp: Double,
    val birds: Boolean,
)

private fun styleFor(id: String): Style = when (id) {
    "arpa" -> Style(
        baseMidi = 62, intervalScale = 0.8, attackScale = 0.25, decayScale = 0.55, brightScale = 1.3,
        bell = false, fifth = false, noteAmp = 0.30, notesFrom = 0.0,
        padLowHz = 0.0, padHighHz = 0.0, padAmp = 0.0, noiseAmp = 0.0, birds = false,
    )
    "campanas" -> Style(
        baseMidi = 67, intervalScale = 1.7, attackScale = 0.12, decayScale = 2.4, brightScale = 1.0,
        bell = true, fifth = false, noteAmp = 0.26, notesFrom = 0.0,
        padLowHz = 196.0, padHighHz = 293.66, padAmp = 0.035, noiseAmp = 0.0, birds = false,
    )
    "calido" -> Style(
        baseMidi = 48, intervalScale = 1.3, attackScale = 1.0, decayScale = 1.8, brightScale = 1.8,
        bell = false, fifth = true, noteAmp = 0.32, notesFrom = 0.0,
        padLowHz = 130.81, padHighHz = 196.0, padAmp = 0.08, noiseAmp = 0.0, birds = false,
    )
    "bosque" -> Style(
        baseMidi = 60, intervalScale = 1.0, attackScale = 1.0, decayScale = 1.0, brightScale = 1.0,
        bell = false, fifth = false, noteAmp = 0.0, notesFrom = 2.0,
        padLowHz = 130.81, padHighHz = 196.0, padAmp = 0.05, noiseAmp = 0.0, birds = true,
    )
    "olas" -> Style(
        baseMidi = 67, intervalScale = 3.2, attackScale = 0.15, decayScale = 2.4, brightScale = 1.0,
        bell = true, fifth = false, noteAmp = 0.20, notesFrom = 0.35,
        padLowHz = 130.81, padHighHz = 196.0, padAmp = 0.05, noiseAmp = 0.16, birds = false,
    )
    else -> Style(
        baseMidi = 60, intervalScale = 1.0, attackScale = 1.0, decayScale = 1.0, brightScale = 1.0,
        bell = false, fifth = true, noteAmp = 0.30, notesFrom = 0.0,
        padLowHz = 261.63, padHighHz = 392.0, padAmp = 0.06, noiseAmp = 0.0, birds = false,
    )
}

/**
 * Sintetizador en tiempo real. No usa archivos de audio: genera los sonidos matemáticamente,
 * así el despertar es idéntico en cualquier móvil y el APK pesa poco.
 *
 * Diseño (ver docs/CIENCIA.md):
 *  - Melódico (arpegios consonantes), no pitidos: menos inercia del sueño percibida.
 *  - Empieza casi inaudible y sube en dB durante `rampSeconds`.
 *  - Mientras sube: más notas por segundo, más brillo, aparece una segunda voz (quinta) y un colchón sostenido.
 *  - Después de la rampa se mantiene en el nivel final hasta que lo pares.
 *
 * @param preset id del sonido (ver [Sounds]); cualquier id desconocido suena como "amanecer".
 * @param startOffsetSeconds por dónde de la subida empieza (para retomar una alarma si Android mató el servicio).
 */
class WakeSynth(
    private val rampSeconds: Double,
    private val sampleRate: Int = 44100,
    preset: String = Sounds.DEFAULT,
    startOffsetSeconds: Double = 0.0,
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
        val bell: Boolean,
        val life: Double,
    ) {
        var age = 0.0
        var phase = 0.0
    }

    private class Chirp(val f0: Double, val f1: Double, val dur: Double, val amp: Double) {
        var age = 0.0
        var phase = 0.0
    }

    private val style = styleFor(preset)
    private val rnd = Random(7)
    private val voices = ArrayList<Voice>()
    private val chirps = ArrayList<Chirp>()
    private var sampleIndex = (startOffsetSeconds.coerceAtLeast(0.0) * sampleRate).toLong()
    private var nextNoteAt = startOffsetSeconds.coerceAtLeast(0.0)
    private var nextBirdAt = startOffsetSeconds.coerceAtLeast(0.0)
    private var noteIndex = 0
    private var padPhaseA = 0.0
    private var padPhaseB = 0.0
    private var lp1 = 0.0
    private var lp2 = 0.0

    val elapsedSeconds: Double get() = sampleIndex.toDouble() / sampleRate

    /** Rellena `count` muestras mono en [-1, 1]. */
    fun render(out: FloatArray, count: Int) {
        val dt = 1.0 / sampleRate
        val twoPi = 2.0 * PI
        for (i in 0 until count) {
            val t = sampleIndex * dt
            val x = WakeCurve.progress(t, rampSeconds)
            if (t >= nextNoteAt) {
                if (x >= style.notesFrom && style.noteAmp > 0.0) triggerNote(x)
                nextNoteAt += WakeCurve.noteInterval(x) * style.intervalScale
            }
            if (style.birds && t >= nextBirdAt) {
                triggerBird()
                nextBirdAt += WakeCurve.lerp(3.6, 1.2, WakeCurve.smooth(x)) * rnd.nextDouble(0.7, 1.4)
            }

            var s = 0.0

            var vi = voices.size - 1
            while (vi >= 0) {
                val v = voices[vi]
                if (v.age >= v.life) {
                    voices.removeAt(vi)
                } else {
                    val env = envelope(v)
                    v.phase += twoPi * v.freq * dt
                    val p = v.phase
                    val wave = if (v.bell) {
                        sin(p) + 0.45 * sin(2.76 * p) + 0.20 * sin(5.40 * p) + 0.08 * sin(8.93 * p)
                    } else {
                        sin(p) + v.h2 * sin(2 * p) + v.h3 * sin(3 * p) + v.h4 * sin(4 * p)
                    }
                    s += env * v.amp * wave / v.norm
                    v.age += dt
                }
                vi--
            }

            var ci = chirps.size - 1
            while (ci >= 0) {
                val c = chirps[ci]
                if (c.age >= c.dur) {
                    chirps.removeAt(ci)
                } else {
                    if (c.age >= 0.0) {
                        val u = c.age / c.dur
                        val f = (c.f0 + (c.f1 - c.f0) * u) * (1.0 + 0.03 * sin(twoPi * 30.0 * c.age))
                        c.phase += twoPi * f * dt
                        val env = sin(PI * u)
                        s += c.amp * env * env * sin(c.phase)
                    }
                    c.age += dt
                }
                ci--
            }

            // Colchón sostenido que entra a partir del 30 % de la rampa
            val padLevel = WakeCurve.smooth((x - 0.30) / 0.70) * style.padAmp
            if (padLevel > 0.0) {
                padPhaseA += twoPi * style.padLowHz * dt
                padPhaseB += twoPi * style.padHighHz * dt
                if (padPhaseA > twoPi) padPhaseA -= twoPi
                if (padPhaseB > twoPi) padPhaseB -= twoPi
                s += padLevel * (sin(padPhaseA) + sin(padPhaseB))
            }

            // Oleaje: ruido filtrado cuyo volumen y brillo suben y bajan cada ~8 s
            if (style.noiseAmp > 0.0) {
                val ph = 0.5 + 0.5 * sin(twoPi * t / 8.0 - PI / 2.0)
                val swell = ph * ph
                val a = 0.012 + 0.03 * swell
                val n = rnd.nextDouble() * 2.0 - 1.0
                lp1 += a * (n - lp1)
                lp2 += a * (lp1 - lp2)
                s += style.noiseAmp * NOISE_GAIN * (0.25 + 0.75 * swell) * lp2
            }

            // La ganancia (curva en dB) se aplica ANTES del limitador suave
            out[i] = tanh(s * WakeCurve.gain(t, rampSeconds)).toFloat()
            sampleIndex++
        }
    }

    private fun envelope(v: Voice): Double {
        val a = if (v.age < v.attack) 0.5 * (1.0 - cos(PI * v.age / v.attack)) else exp(-(v.age - v.attack) / v.tau)
        val tail = if (v.age > v.life - RELEASE) ((v.life - v.age) / RELEASE).coerceAtLeast(0.0) else 1.0
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

        addVoice(WakeCurve.midiToHz(style.baseMidi + semitone), style.noteAmp * accent, x)
        // segunda voz (quinta justa arriba) cuando la rampa va por más del 66 %
        if (style.fifth && x > 0.66 && semitone <= 12) {
            addVoice(WakeCurve.midiToHz(style.baseMidi + semitone + 7), style.noteAmp * accent * 0.5, x)
        }
        noteIndex++
    }

    private fun addVoice(freq: Double, amp: Double, x: Double) {
        val b = style.brightScale
        val h2 = WakeCurve.h2(x) * b
        val h3 = WakeCurve.h3(x) * b
        val h4 = WakeCurve.h4(x) * b
        val norm = if (style.bell) BELL_NORM else sqrt(1.0 + h2 * h2 + h3 * h3 + h4 * h4)
        val tau = WakeCurve.decayTau(x) * style.decayScale
        val life = maxOf(LIFE_MIN, tau * 4.0)
        voices.add(Voice(freq, amp, WakeCurve.attack(x) * style.attackScale, tau, h2, h3, h4, norm, style.bell, life))
    }

    /** Una frase de pájaro: 2-4 píos cortos con glissando, tono y orden distintos cada vez. */
    private fun triggerBird() {
        val n = rnd.nextInt(2, 5)
        var gap = 0.0
        val base = rnd.nextDouble(2200.0, 3600.0)
        for (k in 0 until n) {
            val f0 = base * rnd.nextDouble(0.9, 1.15)
            val f1 = if (rnd.nextBoolean()) f0 * rnd.nextDouble(1.15, 1.45) else f0 * rnd.nextDouble(0.7, 0.9)
            val dur = rnd.nextDouble(0.07, 0.16)
            val c = Chirp(f0, f1, dur, BIRD_AMP * rnd.nextDouble(0.7, 1.0))
            c.age = -gap
            chirps.add(c)
            gap += dur + rnd.nextDouble(0.05, 0.12)
        }
    }

    private companion object {
        const val LIFE_MIN = 6.0   // vida mínima de una nota (s)
        const val RELEASE = 0.25   // fundido final para evitar clics
        const val BELL_NORM = 1.12
        const val BIRD_AMP = 0.18
        const val NOISE_GAIN = 22.0 // el ruido filtrado tiene RMS ≈ 0,044: esto lo deja en ≈ 1
    }
}
