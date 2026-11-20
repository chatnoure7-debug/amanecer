package com.reloj.despertar.data

import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * @param days bitmask: lunes = 1, martes = 2, ... domingo = 64. 0 = una sola vez.
 * @param rampSeconds duración de la subida suave de volumen (por defecto 60 s).
 * @param volumePercent volumen del canal "alarma" del móvil al que se llega (0-100).
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
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 5,
    val speakGreeting: Boolean = false,
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
        .put("vibrate", vibrate)
        .put("snoozeMinutes", snoozeMinutes)
        .put("speakGreeting", speakGreeting)

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
            vibrate = o.optBoolean("vibrate", true),
            snoozeMinutes = o.optInt("snoozeMinutes", 5),
            speakGreeting = o.optBoolean("speakGreeting", false),
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
