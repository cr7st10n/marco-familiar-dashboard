package com.lito.planify.dashboard.ui

import com.lito.planify.dashboard.data.Forecast
import com.lito.planify.dashboard.data.THEME_AUTO
import com.lito.planify.dashboard.data.THEME_DARK
import java.time.LocalDateTime
import java.time.LocalTime

/** Sin previsión del tiempo (o sin ubicación), el sol se pone y sale a estas horas. */
private val DEFAULT_SUNRISE: LocalTime = LocalTime.of(8, 0)
private val DEFAULT_SUNSET: LocalTime = LocalTime.of(19, 0)

/** Salida y puesta del sol de hoy, de la previsión de Open-Meteo de la ubicación del tiempo. */
internal fun sunTimes(forecast: Forecast?, now: LocalDateTime): Pair<LocalTime, LocalTime> {
    val today = now.toLocalDate()
    fun parse(s: String?) = s?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    return (parse(forecast?.sunrise(today)) ?: DEFAULT_SUNRISE) to (parse(forecast?.sunset(today)) ?: DEFAULT_SUNSET)
}

/** Modo noche ahora: siempre en «oscuro»; en «auto», de la puesta a la salida del sol; en «claro», nunca. */
internal fun nightTheme(mode: String, now: LocalDateTime, forecast: Forecast?): Boolean = when (mode) {
    THEME_DARK -> true
    THEME_AUTO -> {
        val (rise, set) = sunTimes(forecast, now)
        val t = now.toLocalTime()
        !t.isBefore(set) || t.isBefore(rise)
    }
    else -> false
}
