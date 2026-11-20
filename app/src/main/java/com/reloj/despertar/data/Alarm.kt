package com.reloj.despertar.data

import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * @param days bitmask: lunes = 1, martes = 2, ... domingo = 64. 0 = una sola vez.
 * @param rampSeconds duración de la subida suave de volumen (por defecto 60 s).
 * @param volumePercent volumen del canal "alarma" del móvil al que se llega (0-100).
 * @param snoozeMinutes minutos de posponer; 0 = sin posponer.
 * @param sound id del sonido ("amanecer", "arpa", ... o "file" para un archivo propio).
 * @param vibLevel 0 apagada, 1 muy suave, 2 suave, 3 media, 4 fuerte.
 * @param vibStartPercent a qué punto de la subida (0-100 %) empieza a vibrar.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val enabled: Boolean = true,
    val label: String = "",
    val rampSeconds: Int = 60,
    val volumePercent: Int = 80,
    val snoozeMinutes: Int = 5,
    val sound: String = "amanecer",
    val soundUri: String = "",
    val soundName: String = "",
    val vibLevel: Int = 1,
    val vibStartPercent: Int = 70,
    val speakGreeting: Boolean = false,
    val greetingText: String = "Buenos días",
    val speakTime: Boolean = true,
    val voicePitch: Int = 90,
    val voiceRate: Int = 85,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hour", hour)
        .put("minute", minute)
        .put("days", days)
        .put("enabled", enabled)
        .put("label", label)
        .put("rampSeconds", rampSeconds)
        .put("volumePercent", volumePercent)
        .put("snoozeMinutes", snoozeMinutes)
        .put("sound", sound)
        .put("soundUri", soundUri)
        .put("soundName", soundName)
        .put("vibLevel", vibLevel)
        .put("vibStartPercent", vibStartPercent)
        .put("speakGreeting", speakGreeting)
        .put("greetingText", greetingText)
        .put("speakTime", speakTime)
        .put("voicePitch", voicePitch)
        .put("voiceRate", voiceRate)

    /** Frase que dice la voz: saludo y, si se quiere, la hora. */
    fun spokenText(): String {
        val base = greetingText.trim().ifBlank { "Buenos días" }
        if (!speakTime) return base
        val verb = if (hour == 1) "Es la" else "Son las"
        val minutes = if (minute == 0) "en punto" else "y $minute"
        return "$base. $verb $hour $minutes."
    }

    companion object {
        const val ALL_DAYS = 127
        const val WEEKDAYS = 31

        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            days = o.optInt("days", 0),
            enabled = o.optBoolean("enabled", true),
            label = o.optString("label", ""),
            rampSeconds = o.optInt("rampSeconds", 60),
            volumePercent = o.optInt("volumePercent", 80),
            snoozeMinutes = o.optInt("snoozeMinutes", 5),
            sound = o.optString("sound", "amanecer"),
            soundUri = o.optString("soundUri", ""),
            soundName = o.optString("soundName", ""),
            // versiones anteriores guardaban solo "vibrate" (sí/no)
            vibLevel = if (o.has("vibLevel")) o.optInt("vibLevel", 1)
            else if (o.optBoolean("vibrate", true)) 1 else 0,
            vibStartPercent = o.optInt("vibStartPercent", 70),
            speakGreeting = o.optBoolean("speakGreeting", false),
            greetingText = o.optString("greetingText", "Buenos días"),
            speakTime = o.optBoolean("speakTime", true),
            voicePitch = o.optInt("voicePitch", 90),
            voiceRate = o.optInt("voiceRate", 85),
        )
    }
}

/** Cálculo puro (sin Android) de cuándo suena la siguiente vez. Fácil de testear. */
object Schedule {
    fun nextTrigger(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val today = now.withHour(alarm.hour).withMinute(alarm.minute).withSecond(0).withNano(0)
        for (offset in 0..7) {
            // plusDays sobre ZonedDateTime conserva la hora local aunque cambie el horario de verano
            val candidate = today.plusDays(offset.toLong())
            val dayBit = 1 shl (candidate.dayOfWeek.value - 1)
            val dayOk = alarm.days == 0 || (alarm.days and dayBit) != 0
            if (dayOk && candidate.isAfter(now)) return candidate
        }
        return today.plusDays(1)
    }

    fun daysSummary(days: Int): String {
        if (days == 0) return "Una vez"
        if (days == Alarm.ALL_DAYS) return "Todos los días"
        if (days == Alarm.WEEKDAYS) return "Lunes a viernes"
        val names = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
        return names.indices.filter { (days and (1 shl it)) != 0 }.joinToString(" ") { names[it] }
    }
}
