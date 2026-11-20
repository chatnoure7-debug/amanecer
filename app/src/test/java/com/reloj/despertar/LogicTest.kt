package com.reloj.despertar

import com.reloj.despertar.data.Alarm
import com.reloj.despertar.data.Schedule
import com.reloj.despertar.sound.WakeCurve
import com.reloj.despertar.sound.WakeSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.sqrt

class LogicTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    @Test
    fun rampStartsQuietAndEndsAtFullLevel() {
        assertEquals(-50.0, WakeCurve.gainDb(0.0, 60.0), 1e-9)
        assertEquals(0.0, WakeCurve.gainDb(60.0, 60.0), 1e-9)
        assertEquals(0.0, WakeCurve.gainDb(300.0, 60.0), 1e-9)
        var prev = -1000.0
        for (t in 0..60) {
            val g = WakeCurve.gainDb(t.toDouble(), 60.0)
            assertTrue(g >= prev)
            prev = g
        }
    }

    @Test
    fun synthIsFiniteBoundedAndGetsLouder() {
        val sr = 22050
        val synth = WakeSynth(60.0, sr)
        val buf = FloatArray(sr) // 1 s
        val rms = DoubleArray(60)
        for (sec in 0 until 60) {
            synth.render(buf, buf.size)
            var sum = 0.0
            for (v in buf) {
                assertFalse(v.isNaN())
                assertTrue(abs(v) <= 1.0f)
                sum += v * v
            }
            rms[sec] = sqrt(sum / buf.size)
        }
        assertTrue("primer segundo casi inaudible", rms[1] < 0.003)
        assertTrue("último segundo claramente audible", rms[58] > 0.05)
        assertTrue(rms[58] > rms[30] && rms[30] > rms[5])
    }

    @Test
    fun nextTriggerPicksTodayOrNextAllowedDay() {
        val alarm = Alarm(id = 1, hour = 5, minute = 30, days = Alarm.WEEKDAYS)
        // viernes 2026-11-20 12:45 → siguiente laborable = lunes 23 a las 05:30
        val now = ZonedDateTime.of(2026, 11, 20, 12, 45, 0, 0, madrid)
        val next = Schedule.nextTrigger(alarm, now)
        assertEquals(23, next.dayOfMonth)
        assertEquals(5, next.hour)
        assertEquals(30, next.minute)
        // viernes 03:00 → suena ese mismo día
        val early = ZonedDateTime.of(2026, 11, 20, 3, 0, 0, 0, madrid)
        assertEquals(20, Schedule.nextTrigger(alarm, early).dayOfMonth)
    }

    @Test
    fun oneShotAlarmGoesToTomorrowIfTimePassed() {
        val alarm = Alarm(id = 1, hour = 6, minute = 0, days = 0)
        val now = ZonedDateTime.of(2026, 11, 20, 6, 0, 0, 0, madrid) // justo ahora: ya no es futuro
        assertEquals(21, Schedule.nextTrigger(alarm, now).dayOfMonth)
    }
}
