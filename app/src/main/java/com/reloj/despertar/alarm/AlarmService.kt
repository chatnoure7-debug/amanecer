package com.reloj.despertar.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.reloj.despertar.R
import com.reloj.despertar.data.Alarm
import com.reloj.despertar.data.AlarmStore
import com.reloj.despertar.sound.FileSoundPlayer
import com.reloj.despertar.sound.RingSound
import com.reloj.despertar.sound.Sounds
import com.reloj.despertar.sound.WakeSoundPlayer
import com.reloj.despertar.ui.AlarmActivity
import java.util.Locale

/**
 * Servicio en primer plano que vive SOLO mientras suena la alarma.
 *
 * NO se para solo: sigue sonando hasta que se pulsa Parar (o Posponer). Para que no falle:
 *  - anota en disco que está sonando (RingGuard) y programa una alarma de respaldo del sistema cada pocos segundos;
 *    si Android mata el servicio, esa alarma lo vuelve a levantar y retoma la subida por donde iba;
 *  - cada 20 s comprueba que el sonido sigue vivo y, si no, lo reinicia;
 *  - mantiene el móvil despierto con un wake lock que se renueva.
 * Al parar libera todo: audio, vibración, wake lock, voz, respaldo y volumen.
 */
class AlarmService : Service() {

    private var player: RingSound? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focusRequest: AudioFocusRequest? = null
    private var originalVolume = -1
    private var current: Alarm? = null
    private var startedAtMs = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val vibrateRunnable = Runnable { startVibration() }
    private val greetingRunnable = object : Runnable {
        override fun run() {
            speakGreeting()
            handler.postDelayed(this, GREETING_EVERY_MS)
        }
    }
    private val healthRunnable = object : Runnable {
        override fun run() {
            healthCheck()
            handler.postDelayed(this, HEALTH_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
                val alarm = AlarmStore(this).get(id)
                if (alarm == null) {
                    // Hay que llamar a startForeground igualmente (se arrancó con startForegroundService)
                    enterForeground(fallbackNotification())
                    stopRinging()
                } else {
                    startRinging(alarm, System.currentTimeMillis(), null)
                }
            }
            ACTION_DISMISS -> stopRinging()
            ACTION_SNOOZE -> {
                current?.let { if (it.snoozeMinutes > 0) AlarmScheduler.scheduleSnooze(this, it) }
                stopRinging()
            }
            // ACTION_GUARD (alarma de respaldo) o reinicio del servicio por parte del sistema (intent nulo)
            else -> guard()
        }
        return START_STICKY
    }

    private fun guard() {
        val rec = RingGuard.current(this)
        val alarm = rec?.let { AlarmStore(this).get(it.alarmId) }
        if (rec == null || alarm == null) {
            // Nada que vigilar
            enterForeground(fallbackNotification())
            stopRinging()
            return
        }
        val running = current
        if (running == null) {
            // El servicio había muerto: retomar la alarma por donde iba
            startRinging(alarm, rec.startedAtMs, rec.originalVolume)
        } else {
            enterForeground(buildNotification(running))
            healthCheck()
        }
    }

    private fun startRinging(alarm: Alarm, startedAt: Long, savedVolume: Int?) {
        releaseResources() // por si había otra sonando
        current = alarm
        startedAtMs = startedAt

        AlarmState.ringing = Ringing(
            alarmId = alarm.id,
            label = alarm.label,
            hour = alarm.hour,
            minute = alarm.minute,
            startedAtMs = startedAt,
            rampSeconds = alarm.rampSeconds,
            snoozeMinutes = alarm.snoozeMinutes,
        )
        enterForeground(buildNotification(alarm))

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amanecer:ring").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_MS)
        }

        val old = AlarmVolume.raiseTo(this, alarm.volumePercent)
        originalVolume = savedVolume ?: old
        RingGuard.begin(this, alarm.id, startedAt, originalVolume)
        requestAudioFocus()

        startSound(alarm)

        if (alarm.vibLevel > 0) {
            // La vibración entra cuando la subida llega a su punto elegido
            val startMs = alarm.rampSeconds * alarm.vibStartPercent * 10L
            val delayMs = (startMs - (elapsedSeconds() * 1000).toLong()).coerceAtLeast(0L)
            handler.postDelayed(vibrateRunnable, delayMs)
        }
        if (alarm.speakGreeting) initTts(alarm)

        AlarmScheduler.scheduleGuard(this, alarm.id, GUARD_MS)
        handler.postDelayed(healthRunnable, HEALTH_EVERY_MS)
    }

    /** Solo se llama al pulsar Parar o Posponer. */
    private fun stopRinging() {
        val id = current?.id ?: RingGuard.current(this)?.alarmId
        RingGuard.clear(this)
        if (id != null) AlarmScheduler.cancelGuard(this, id)
        releaseResources()
        AlarmState.ringing = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        handler.removeCallbacksAndMessages(null)
        player?.stop()
        player = null
        getSystemService(Vibrator::class.java)?.cancel()
        tts?.shutdown()
        tts = null
        ttsReady = false
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
        if (originalVolume >= 0) {
            AlarmVolume.restore(this, originalVolume)
            originalVolume = -1
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseResources()
        AlarmState.ringing = null
        super.onDestroy()
    }

    // ---------- vigilante ----------

    private fun elapsedSeconds(): Double =
        ((System.currentTimeMillis() - startedAtMs) / 1000.0).coerceAtLeast(0.0)

    private fun healthCheck() {
        val a = current ?: return
        val p = player
        if (p == null || !p.isAlive()) startSound(a)
        wakeLock?.acquire(WAKELOCK_MS)
        AlarmScheduler.scheduleGuard(this, a.id, GUARD_MS)
    }

    // ---------- audio / vibración / voz ----------

    private fun startSound(alarm: Alarm) {
        player?.stop()
        val offset = elapsedSeconds()
        val p: RingSound = if (alarm.sound == Sounds.FILE && alarm.soundUri.isNotBlank()) {
            // Si el archivo falla, suena el sonido de la app: la alarma nunca queda muda
            FileSoundPlayer(applicationContext, Uri.parse(alarm.soundUri), alarm.rampSeconds, offset) {
                handler.post { fallbackSound() }
            }
        } else {
            WakeSoundPlayer(alarm.rampSeconds, alarm.sound, offset)
        }
        player = p
        p.start()
    }

    private fun fallbackSound() {
        val a = current ?: return
        player?.stop()
        val p = WakeSoundPlayer(a.rampSeconds, Sounds.DEFAULT, elapsedSeconds())
        player = p
        p.start()
    }

    private fun requestAudioFocus() {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Se ignora cualquier pérdida de foco: la alarma sigue sonando pase lo que pase
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        focusRequest = req
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val a = current ?: return
        val v = getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        val effect = Vibe.repeating(a.vibLevel, v.hasAmplitudeControl()) ?: return
        v.vibrate(
            effect,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
    }

    private fun initTts(alarm: Alarm) {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let {
                    it.setLanguage(Locale("es", "ES"))
                    it.setPitch(alarm.voicePitch / 100f)
                    it.setSpeechRate(alarm.voiceRate / 100f)
                    it.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                ttsReady = true
                // La voz entra cuando la rampa va por el 85 %
                val startMs = alarm.rampSeconds * 850L
                val delayMs = (startMs - (elapsedSeconds() * 1000).toLong()).coerceAtLeast(0L)
                handler.postDelayed(greetingRunnable, delayMs)
            }
        }
    }

    private fun speakGreeting() {
        val a = current ?: return
        if (!ttsReady) return
        tts?.speak(a.spokenText(), TextToSpeech.QUEUE_FLUSH, Bundle(), "greeting")
    }

    // ---------- notificaciones ----------

    private fun enterForeground(notification: Notification) {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun buildNotification(alarm: Alarm): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = alarm.label.ifBlank { "Despertar" }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText("%02d:%02d".format(alarm.hour, alarm.minute))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
        if (alarm.snoozeMinutes > 0) {
            b.addAction(0, "Posponer ${alarm.snoozeMinutes} min", servicePending(ACTION_SNOOZE, 1))
        }
        b.addAction(0, "Parar", servicePending(ACTION_DISMISS, 2))
        return b.build()
    }

    private fun fallbackNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Alarma")
            .build()

    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Alarma sonando", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)          // el sonido lo genera la app
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        const val ACTION_START = "com.reloj.despertar.START"
        const val ACTION_DISMISS = "com.reloj.despertar.DISMISS"
        const val ACTION_SNOOZE = "com.reloj.despertar.SNOOZE"
        const val ACTION_GUARD = "com.reloj.despertar.GUARD_SERVICE"
        private const val CHANNEL_ID = "ringing"
        private const val NOTIF_ID = 1001
        private const val GREETING_EVERY_MS = 25_000L
        private const val HEALTH_EVERY_MS = 20_000L
        private const val GUARD_MS = 60_000L
        private const val WAKELOCK_MS = 10 * 60 * 1000L

        fun stopIntent(context: Context): Intent =
            Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS)

        fun snoozeIntent(context: Context): Intent =
            Intent(context, AlarmService::class.java).setAction(ACTION_SNOOZE)
    }
}
