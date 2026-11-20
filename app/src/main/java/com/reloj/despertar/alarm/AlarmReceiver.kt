package com.reloj.despertar.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.reloj.despertar.data.AlarmStore

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        if (id < 0) return

        if (intent.action == AlarmScheduler.ACTION_GUARD) {
            // Vigilante: solo actúa si la alarma sigue sin parar
            if (RingGuard.current(context)?.alarmId == id) {
                val guard = Intent(context, AlarmService::class.java)
                    .setAction(AlarmService.ACTION_GUARD)
                    .putExtra(AlarmScheduler.EXTRA_ID, id)
                ContextCompat.startForegroundService(context, guard)
            }
            return
        }

        val snoozed = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZED, false)
        val store = AlarmStore(context)
        val alarm = store.get(id) ?: return

        if (!snoozed) {
            if (alarm.days == 0) {
                // Alarma de una sola vez: se apaga tras sonar
                store.save(alarm.copy(enabled = false))
            } else {
                // Repetitiva: programar ya la siguiente
                AlarmScheduler.schedule(context, alarm)
            }
        }

        val service = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START)
            .putExtra(AlarmScheduler.EXTRA_ID, id)
        ContextCompat.startForegroundService(context, service)
    }
}
