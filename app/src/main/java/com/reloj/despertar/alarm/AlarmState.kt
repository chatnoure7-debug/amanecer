package com.reloj.despertar.alarm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Ringing(
    val alarmId: Int,
    val label: String,
    val hour: Int,
    val minute: Int,
    val startedAtMs: Long,
    val rampSeconds: Int,
    val snoozeMinutes: Int,
)

/** Estado compartido servicio ↔ pantalla de alarma (mismo proceso). null = no está sonando nada. */
object AlarmState {
    var ringing: Ringing? by mutableStateOf(null)
}
