package com.reloj.despertar.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Reproduce un archivo de audio elegido por el usuario, en bucle, con la misma subida suave de volumen.
 * Si el archivo no se puede abrir (borrado, sin permiso...) avisa con [onFailure] para que suene el sonido de la app.
 * Debe crearse y usarse desde el hilo principal.
 */
class FileSoundPlayer(
    private val context: Context,
    private val uri: Uri,
    private val rampSeconds: Int,
    private val offsetSeconds: Double = 0.0,
    private val onFailure: () -> Unit = {},
) : RingSound {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var startedAt = 0L
    private var failed = false
    private var stopped = false

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val t = (SystemClock.elapsedRealtime() - startedAt) / 1000.0 + offsetSeconds
            val g = WakeCurve.gain(t, rampSeconds.toDouble()).toFloat()
            try {
                p.setVolume(g, g)
            } catch (_: IllegalStateException) {
            }
            handler.postDelayed(this, 150)
        }
    }

    override fun isAlive(): Boolean = player != null && !failed && !stopped

    override fun start() {
        try {
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(context, uri)
            p.isLooping = true
            val g0 = WakeCurve.gain(offsetSeconds, rampSeconds.toDouble()).toFloat()
            p.setVolume(g0, g0)
            p.setOnPreparedListener { mp ->
                if (!stopped && !failed) {
                    startedAt = SystemClock.elapsedRealtime()
                    mp.start()
                    handler.post(tick)
                }
            }
            p.setOnErrorListener { _, _, _ ->
                fail()
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            fail()
        }
    }

    override fun stop() {
        stopped = true
        release()
    }

    private fun fail() {
        if (failed || stopped) return
        failed = true
        release()
        onFailure()
    }

    private fun release() {
        handler.removeCallbacks(tick)
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }
}
