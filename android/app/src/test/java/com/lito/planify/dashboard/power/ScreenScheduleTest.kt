package com.lito.planify.dashboard.power

import com.lito.planify.dashboard.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ScreenScheduleTest {
    // Laborables 07:00–23:00, fin de semana 08:30–23:30
    private val schedule = Schedule(enabled = true)
    private val wednesday = LocalDateTime.of(2026, 10, 14, 0, 0)
    private val saturday = LocalDateTime.of(2026, 10, 17, 0, 0)

    @Test
    fun disabledScheduleIsAlwaysOn() {
        assertTrue(ScreenSchedule.isOn(Schedule(enabled = false), wednesday.withHour(3)))
    }

    @Test
    fun windowIncludesOnAndExcludesOff() {
        assertFalse(ScreenSchedule.isOn(schedule, wednesday.withHour(6).withMinute(59)))
        assertTrue(ScreenSchedule.isOn(schedule, wednesday.withHour(7)))
        assertTrue(ScreenSchedule.isOn(schedule, wednesday.withHour(22).withMinute(59)))
        assertFalse(ScreenSchedule.isOn(schedule, wednesday.withHour(23)))
    }

    @Test
    fun weekendUsesItsOwnWindow() {
        assertFalse(ScreenSchedule.isOn(schedule, saturday.withHour(8)))
        assertTrue(ScreenSchedule.isOn(schedule, saturday.withHour(23).withMinute(15)))
    }

    @Test
    fun nextChangeIsTheNextBoundary() {
        assertEquals(wednesday.withHour(7), ScreenSchedule.nextChange(schedule, wednesday.withHour(3)))
        assertEquals(wednesday.withHour(23), ScreenSchedule.nextChange(schedule, wednesday.withHour(7)))
        // Viernes noche → sábado 08:30
        assertEquals(saturday.withHour(8).withMinute(30), ScreenSchedule.nextChange(schedule, saturday.minusDays(1).withHour(23)))
    }

    @Test
    fun nightClockReplacesTheScreenOffOutsideTheWindow() {
        assertTrue(ScreenSchedule.isNight(schedule, wednesday.withHour(23)))
        assertFalse(ScreenSchedule.isNight(schedule, wednesday.withHour(12)))
        assertFalse(ScreenSchedule.isNight(schedule.copy(screenOff = true), wednesday.withHour(23)))
        assertFalse(ScreenSchedule.isNight(Schedule(enabled = false), wednesday.withHour(3)))
    }

    // Apagado pasada la medianoche: laborables hasta la 1:30 (25 × 60 + 30), fin de semana hasta las 03:00
    private val late = schedule.copy(weekdayOff = 25 * 60 + 30, weekendOff = ScreenSchedule.LATEST_OFF)

    @Test
    fun offAfterMidnightKeepsTheNightOn() {
        assertTrue(ScreenSchedule.isOn(late, wednesday.withHour(23).withMinute(30)))
        // Jueves de madrugada: sigue la noche del miércoles
        assertTrue(ScreenSchedule.isOn(late, wednesday.plusDays(1).withHour(0).withMinute(30)))
        assertTrue(ScreenSchedule.isOn(late, wednesday.plusDays(1).withHour(1).withMinute(15)))
        assertFalse(ScreenSchedule.isOn(late, wednesday.plusDays(1).withHour(1).withMinute(30)))
        assertFalse(ScreenSchedule.isOn(late, wednesday.plusDays(1).withHour(6)))
        assertTrue(ScreenSchedule.isNight(late, wednesday.plusDays(1).withHour(2)))
        assertFalse(ScreenSchedule.isNight(late, wednesday.plusDays(1).withHour(1)))
    }

    @Test
    fun eachScheduleOwnsItsNight() {
        // Viernes noche (laborable) hasta la 1:30 del sábado; sábado noche (fin de semana) hasta las 03:00 del domingo
        assertTrue(ScreenSchedule.isOn(late, saturday.withHour(1)))
        assertFalse(ScreenSchedule.isOn(late, saturday.withHour(2)))
        assertTrue(ScreenSchedule.isOn(late, saturday.plusDays(1).withHour(2).withMinute(45)))
        assertFalse(ScreenSchedule.isOn(late, saturday.plusDays(1).withHour(3)))
        // Domingo noche (fin de semana) hasta las 03:00 del lunes
        assertTrue(ScreenSchedule.isOn(late, saturday.plusDays(2).withHour(2)))
        // Sin apagado tardío, la madrugada sigue apagada
        assertFalse(ScreenSchedule.isOn(schedule, saturday.withHour(0).withMinute(15)))
    }

    @Test
    fun nextChangeFindsTheOffAfterMidnight() {
        assertEquals(wednesday.plusDays(1).withHour(1).withMinute(30), ScreenSchedule.nextChange(late, wednesday.withHour(23)))
        // Ya de madrugada, el apagado pendiente es el de la noche de ayer
        assertEquals(wednesday.plusDays(1).withHour(1).withMinute(30), ScreenSchedule.nextChange(late, wednesday.plusDays(1).withHour(0).withMinute(10)))
        assertEquals(wednesday.plusDays(1).withHour(7), ScreenSchedule.nextChange(late, wednesday.plusDays(1).withHour(1).withMinute(30)))
    }

    @Test
    fun nextOnIsTheNextWindowStart() {
        assertEquals(wednesday.plusDays(1).withHour(7), ScreenSchedule.nextOn(schedule, wednesday.withHour(15)))
        assertEquals(wednesday.withHour(7), ScreenSchedule.nextOn(schedule, wednesday.withHour(3)))
        // Viernes por la tarde → sábado 08:30
        assertEquals(saturday.withHour(8).withMinute(30), ScreenSchedule.nextOn(schedule, saturday.minusDays(1).withHour(18)))
    }
}
