package com.lito.planify.dashboard.ui

import com.lito.planify.dashboard.data.THEME_AUTO
import com.lito.planify.dashboard.data.THEME_DARK
import com.lito.planify.dashboard.data.THEME_LIGHT
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightThemeTest {
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 11, h, m)

    @Test
    fun manualModesIgnoreTheSun() {
        assertFalse(nightTheme(THEME_LIGHT, at(23, 0), null))
        assertTrue(nightTheme(THEME_DARK, at(12, 0), null))
    }

    @Test
    fun automaticIsDarkFromSunsetToSunrise() {
        // Sin previsión: el sol sale a las 08:00 y se pone a las 19:00
        assertEquals(listOf(true, false, false, true, true), listOf(at(7, 59), at(8, 0), at(18, 59), at(19, 0), at(0, 30)).map { nightTheme(THEME_AUTO, it, null) })
    }
}
