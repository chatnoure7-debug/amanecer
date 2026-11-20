package com.reloj.despertar.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.max

/**
 * Reproduce el sintetizador por AudioTrack en un hilo propio.
 * Solo existe mientras suena: no hay nada ejecutándose el resto del día (batería ≈ 0).
 */
class WakeSoundPlayer(
    private val rampSeconds: Int,
    private val preset: String = Sounds.DEFAULT,
    private val offsetSeconds: Double = 0.0,
) : RingSound {
    @Volatile private var stopRequested = false
    private var thread: Thread? = null

    override fun isAlive(): Boolean = thread?.isAlive == true

    @Synchronized
    override fun start() {
        if (thread != null) return
        stopRequested = false
        val t = Thread({ run() }, "wake-sound")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** No bloquea: el hilo hace un fundido de 0,2 s y se libera solo. */
    @Synchronized
    override fun stop() {
        stopRequested = true
        thread = null
    }

    /** Si algo falla (p. ej. el audio del sistema), el hilo termina sin tumbar la app y el vigilante lo reinicia. */
    private fun run() {
        try {
            play()
        } catch (_: Throwable) {
        }
    }

    private fun play() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, SAMPLE_RATE / 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val synth = WakeSynth(rampSeconds.toDouble(), SAMPLE_RATE, preset, offsetSeconds)
        val buf = FloatArray(CHUNK)
        var fadeLeft = -1
        try {
            track.play()
            while (true) {
                synth.render(buf, CHUNK)
                if (stopRequested && fadeLeft < 0) fadeLeft = FADE_SAMPLES
                if (fadeLeft >= 0) {
                    for (i in 0 until CHUNK) {
                        val g = if (fadeLeft > 0) fadeLeft.toFloat() / FADE_SAMPLES else 0f
                        buf[i] *= g
                        if (fadeLeft > 0) fadeLeft--
                    }
                }
                val written = track.write(buf, 0, CHUNK, AudioTrack.WRITE_BLOCKING)
                if (written < 0 || fadeLeft == 0) break
            }
        } finally {
            try { track.stop() } catch (_: IllegalStateException) { }
            track.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val CHUNK = 2048
        const val FADE_SAMPLES = 8820 // 0,2 s
    }
}
