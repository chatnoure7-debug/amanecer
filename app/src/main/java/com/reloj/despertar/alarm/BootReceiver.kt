package com.reloj.despertar.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tras reiniciar, actualizar la app o cambiar hora/zona horaria hay que reprogramar las alarmas. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
    }
}
