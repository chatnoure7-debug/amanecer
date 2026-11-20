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

    @Test
    fun everyPresetIsFiniteBoundedAndGetsLouder() {
        val sr = 22050
        for (preset in listOf("amanecer", "arpa", "campanas", "calido", "bosque", "olas")) {
            val synth = WakeSynth(30.0, sr, preset)
            val buf = FloatArray(sr)
            val rms = DoubleArray(30)
            for (sec in 0 until 30) {
                synth.render(buf, buf.size)
                var sum = 0.0
                for (v in buf) {
                    assertFalse(v.isNaN())
                    assertTrue(abs(v) <= 1.0f)
                    sum += v * v
                }
                rms[sec] = sqrt(sum / buf.size)
            }
            assertTrue("${preset}: arranca casi en silencio", rms[1] < 0.01)
            assertTrue("${preset}: acaba claramente audible", rms[28] > 0.02)
        }
    }

    @Test
    fun resumingWithOffsetContinuesWhereItWas() {
        val sr = 22050
        val fresh = WakeSynth(60.0, sr, "amanecer", 0.0)
        val resumed = WakeSynth(60.0, sr, "amanecer", 50.0)
        val a = FloatArray(sr)
        val b = FloatArray(sr)
        fresh.render(a, sr)
        resumed.render(b, sr)
        var sa = 0.0
        var sb = 0.0
        for (i in 0 until sr) { sa += a[i] * a[i]; sb += b[i] * b[i] }
        assertTrue("retomar a los 50 s suena mucho más fuerte que empezar de cero", sb > sa * 100)
    }

    @Test
    fun spokenTextAddsTheTimeOnlyIfAsked() {
        val a = Alarm(id = 1, hour = 5, minute = 30, greetingText = "Buenos días", speakTime = true)
        assertEquals("Buenos días. Son las 5 y 30.", a.spokenText())
        assertEquals("Hola", a.copy(greetingText = "Hola", speakTime = false).spokenText())
        assertEquals("Buenos días. Es la 1 en punto.", a.copy(hour = 1, minute = 0).spokenText())
    }
}
