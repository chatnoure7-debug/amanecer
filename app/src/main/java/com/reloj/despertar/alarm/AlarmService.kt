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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.reloj.despertar.R
import com.reloj.despertar.data.Alarm
import com.reloj.despertar.data.AlarmStore
import com.reloj.despertar.sound.WakeSoundPlayer
import com.reloj.despertar.ui.AlarmActivity
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Servicio en primer plano que vive SOLO mientras suena la alarma.
 * Al parar (o a los 20 min) se detiene y libera todo: audio, vibración, wake lock, TTS y volumen.
 */
class AlarmService : Service() {

    private var player: WakeSoundPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focusRequest: AudioFocusRequest? = null
    private var originalVolume = -1
    private var current: Alarm? = null
    private val handler = Handler(Looper.getMainLooper())

    private val timeoutRunnable = Runnable { stopRinging(missed = true) }
    private val vibrateRunnable = Runnable { startVibration() }
    private val greetingRunnable = object : Runnable {
        override fun run() {
            speakGreeting()
            handler.postDelayed(this, GREETING_EVERY_MS)
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
                    stopRinging(missed = false)
                } else {
                    startRinging(alarm)
                }
            }
            ACTION_DISMISS -> stopRinging(missed = false)
            ACTION_SNOOZE -> {
                current?.let { AlarmScheduler.scheduleSnooze(this, it) }
                stopRinging(missed = false)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(alarm: Alarm) {
        releaseResources() // por si había otra sonando
        current = alarm

        AlarmState.ringing = Ringing(
            alarmId = alarm.id,
            label = alarm.label,
            hour = alarm.hour,
            minute = alarm.minute,
            startedAtMs = System.currentTimeMillis(),
            rampSeconds = alarm.rampSeconds,
            snoozeMinutes = alarm.snoozeMinutes,
        )
        enterForeground(buildNotification(alarm))

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amanecer:ring").apply {
            acquire(MAX_RING_MS + 60_000)
        }

        setAlarmVolume(alarm.volumePercent)
        requestAudioFocus()

        player = WakeSoundPlayer(alarm.rampSeconds).also { it.start() }

        if (alarm.vibrate) {
            // La vibración entra al 70 % de la rampa: ya es tiempo de insistir
            handler.postDelayed(vibrateRunnable, (alarm.rampSeconds * 700L))
        }
        if (alarm.speakGreeting) initTts(alarm)

        handler.postDelayed(timeoutRunnable, MAX_RING_MS)
    }

    private fun stopRinging(missed: Boolean) {
        val alarm = current
        releaseResources()
        AlarmState.ringing = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (missed && alarm != null) postMissedNotification(alarm)
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
            try {
                getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            } catch (_: SecurityException) { }
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

    // ---------- audio / vibración / voz ----------

    private fun setAlarmVolume(percent: Int) {
        val am = getSystemService(AudioManager::class.java)
        try {
            originalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (max * percent / 100f).roundToInt().coerceIn(1, max)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (_: SecurityException) {
            originalVolume = -1
        }
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
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        focusRequest = req
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        // 0,4 s vibra, 1,1 s pausa; se repite hasta parar
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 400, 1100), 0)
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
                    it.setPitch(0.9f)         // voz algo más grave
                    it.setSpeechRate(0.85f)   // y pausada
                    it.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                ttsReady = true
                // La voz entra cuando la rampa va por el 85 %
                handler.postDelayed(greetingRunnable, alarm.rampSeconds * 850L)
            }
        }
    }

    private fun speakGreeting() {
        val a = current ?: return
        if (!ttsReady) return
        val minutes = if (a.minute == 0) "en punto" else "y ${a.minute}"
        tts?.speak("Buenos días. Son las ${a.hour} $minutes.", TextToSpeech.QUEUE_FLUSH, Bundle(), "greeting")
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText("%02d:%02d".format(alarm.hour, alarm.minute))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Posponer ${alarm.snoozeMinutes} min", servicePending(ACTION_SNOOZE, 1))
            .addAction(0, "Parar", servicePending(ACTION_DISMISS, 2))
            .build()
    }

    private fun fallbackNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Alarma")
            .build()

    private fun postMissedNotification(alarm: Alarm) {
        ensureChannel()
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Alarma sin parar")
            .setContentText("Sonó a las %02d:%02d".format(alarm.hour, alarm.minute))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

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
        private const val CHANNEL_ID = "ringing"
        private const val NOTIF_ID = 1001
        private const val MAX_RING_MS = 20 * 60 * 1000L
        private const val GREETING_EVERY_MS = 25_000L

        fun stopIntent(context: Context): Intent =
            Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS)

        fun snoozeIntent(context: Context): Intent =
            Intent(context, AlarmService::class.java).setAction(ACTION_SNOOZE)
    }
}
