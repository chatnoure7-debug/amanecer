package com.reloj.despertar.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.reloj.despertar.data.Alarm
import com.reloj.despertar.data.AlarmStore
import com.reloj.despertar.data.Schedule
import com.reloj.despertar.ui.MainActivity
import java.time.ZonedDateTime

/**
 * Usa AlarmManager.setAlarmClock: es el mecanismo del reloj del sistema. Lo gestiona Android,
 * está exento de Doze / ahorro de batería y despierta el móvil aunque nuestra app no esté en RAM.
 * Entre alarma y alarma NO corre ningún proceso nuestro.
 */
object AlarmScheduler {
    const val ACTION_FIRE = "com.reloj.despertar.FIRE"
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_SNOOZED = "snoozed"
    private const val SNOOZE_REQUEST_BASE = 100_000

    fun schedule(context: Context, alarm: Alarm) {
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return
        }
        val at = Schedule.nextTrigger(alarm)
        setClock(context, alarm.id, at, snoozed = false)
    }

    fun scheduleSnooze(context: Context, alarm: Alarm) {
        val at = ZonedDateTime.now().plusMinutes(alarm.snoozeMinutes.toLong())
        setClock(context, SNOOZE_REQUEST_BASE + alarm.id, at, snoozed = true, alarmId = alarm.id)
    }

    fun cancel(context: Context, alarmId: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(firePending(context, alarmId, alarmId, snoozed = false))
        am.cancel(firePending(context, SNOOZE_REQUEST_BASE + alarmId, alarmId, snoozed = true))
    }

    fun rescheduleAll(context: Context) {
        AlarmStore(context).all().forEach { schedule(context, it) }
    }

    private fun setClock(
        context: Context,
        requestCode: Int,
        at: ZonedDateTime,
        snoozed: Boolean,
        alarmId: Int = requestCode,
    ) {
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val info = AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), show)
        try {
            am.setAlarmClock(info, firePending(context, requestCode, alarmId, snoozed))
        } catch (e: SecurityException) {
            // Sin permiso de alarmas exactas (la pantalla principal avisa y lo pide)
            Log.w("AlarmScheduler", "No se puede programar alarma exacta", e)
        }
    }

    private fun firePending(context: Context, requestCode: Int, alarmId: Int, snoozed: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("amanecer://alarm/$requestCode"))
            .putExtra(EXTRA_ID, alarmId)
            .putExtra(EXTRA_SNOOZED, snoozed)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
