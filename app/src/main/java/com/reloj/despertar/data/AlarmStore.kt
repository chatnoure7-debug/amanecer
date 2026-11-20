package com.reloj.despertar.data

import android.content.Context
import org.json.JSONArray

/** Persistencia mínima en SharedPreferences (JSON). Sin base de datos, sin servicios residentes. */
class AlarmStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun all(): List<Alarm> = synchronized(LOCK) {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
            .sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun get(id: Int): Alarm? = all().firstOrNull { it.id == id }

    fun save(alarm: Alarm) = synchronized(LOCK) {
        val list = all().filter { it.id != alarm.id } + alarm
        write(list)
    }

    fun delete(id: Int) = synchronized(LOCK) {
        write(all().filter { it.id != id })
    }

    fun nextId(): Int = (all().maxOfOrNull { it.id } ?: 0) + 1

    /** Primera ejecución: deja una alarma de ejemplo a las 05:30, apagada. */
    fun seedIfFirstRun() = synchronized(LOCK) {
        if (prefs.getBoolean(KEY_SEEDED, false)) return@synchronized
        write(listOf(Alarm(id = 1, hour = 5, minute = 30, days = Alarm.ALL_DAYS, enabled = false, label = "Despertar")))
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private fun write(list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private companion object {
        val LOCK = Any()
        const val KEY_LIST = "list"
        const val KEY_SEEDED = "seeded"
    }
}
