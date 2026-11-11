package com.lito.planify.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Microwave
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lito.planify.dashboard.UiState
import com.lito.planify.dashboard.data.Appliance
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** Aparatos con programa: los que salen en la portada mientras funcionan o acaban de terminar. */
private val WASHING = setOf("lavavajillas", "secadora", "lavadora", "lavasecadora")

/** Lo que dura el «terminado» en el marco aunque el servidor no lo haya quitado (p. ej. sin conexión). */
private const val FINISHED_HOURS = 3L

internal val Appliance.washing: Boolean get() = tipo in WASHING

private fun iso(s: String?): LocalDateTime? =
    s?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() }

/** Hora a la que acaba (de «fin»; si no, ahora + los minutos que quedan). */
internal fun Appliance.endsAt(now: LocalDateTime): LocalDateTime? =
    iso(fin) ?: restante_min?.takeIf { it > 0 }?.let { now.plusMinutes(it.roundToInt().toLong()) }

/** Minutos que quedan, contando desde la hora de acabar (el marco descuenta solo entre descargas). */
internal fun Appliance.minutesLeft(now: LocalDateTime): Int? =
    endsAt(now)?.let { Duration.between(now, it).toMinutes().toInt().coerceAtLeast(0) }

/** Cuándo terminó, si fue hace menos de [FINISHED_HOURS] horas. */
internal fun Appliance.finishedAt(now: LocalDateTime): LocalDateTime? =
    iso(terminado_desde)?.takeIf { Duration.between(it, now).toHours() < FINISHED_HOURS }

/** «35 min», «1 h 20 min». */
internal fun minutesText(m: Int): String = if (m < 60) "$m min" else "${m / 60} h${if (m % 60 > 0) " ${m % 60} min" else ""}"

/**
 * Lo que la portada cuenta de la cocina, una línea por cosa: los aparatos de lavado en marcha («quedan 35 min») o recién
 * terminados, y los avisos de cualquier aparato (falta sal, puerta del congelador abierta). Vacío: nada que contar
 * (la semana se queda en 7 días).
 */
internal fun kitchenNews(appliances: List<Appliance>?, now: LocalDateTime): List<Pair<Appliance, String>> {
    val out = mutableListOf<Pair<Appliance, String>>()
    appliances.orEmpty().filter { it.disponible }.forEach { a ->
        if (a.washing) {
            if (a.en_marcha) out += a to (a.minutesLeft(now)?.let { "quedan ${minutesText(it)}" } ?: "en marcha")
            else a.finishedAt(now)?.let { out += a to "✅ Terminado a las ${it.format(HourMinute)}" }
        }
        a.avisos.orEmpty().forEach { out += a to it }
    }
    return out
}

internal fun Appliance.icon(): ImageVector = when (tipo) {
    "lavavajillas" -> Icons.Outlined.Restaurant
    "secadora", "lavadora", "lavasecadora" -> Icons.Outlined.LocalLaundryService
    "frigorifico" -> Icons.Outlined.Kitchen
    "placa" -> Icons.Outlined.LocalFireDepartment
    "horno" -> Icons.Outlined.Microwave
    else -> Icons.Outlined.Kitchen
}

internal fun Appliance.tint(): Color = when (tipo) {
    "lavavajillas" -> Palette.Rain
    "secadora", "lavadora", "lavasecadora" -> Palette.Night
    "frigorifico" -> ClimateBlue
    "placa" -> HotWater
    "horno" -> Amber
    else -> Palette.InkSoft
}

internal fun Appliance.emoji(): String = when (tipo) {
    "lavavajillas" -> "🍽️"
    "secadora", "lavadora", "lavasecadora" -> "🧺"
    "frigorifico" -> "🧊"
    "placa" -> "🔥"
    "horno" -> "♨️"
    else -> "🏠"
}

private fun Appliance.pendingText(): String = when (tipo) {
    "lavavajillas" -> "Aquí saldrá cuánto le queda y si falta sal o abrillantador"
    "secadora", "lavadora", "lavasecadora" -> "Aquí saldrá cuánto le queda y cuándo termina"
    "frigorifico" -> "Aquí saldrán las temperaturas y si se queda una puerta abierta"
    "placa" -> "Aquí saldrá si está encendida o aún caliente"
    "horno" -> "Aquí saldrá si está en marcha y cuánto le queda"
    else -> "Aquí saldrá su estado"
}

/** Programa o fase tal cual los da la integración, solo si son texto (algunas dan códigos numéricos). */
private fun readable(s: String?): String? =
    s?.trim()?.takeIf { it.isNotEmpty() && it.any(Char::isLetter) }?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

/**
 * Pestaña Cocina: cada electrodoméstico en su tarjeta, de tres en tres. Solo para mirar. Lo que aún no tiene datos en
 * Home Assistant pone «pendiente de conectar».
 */
@Composable
fun KitchenScreen(state: UiState) {
    val appliances = state.shopping?.cocina.orEmpty()
    val now = state.now
    ScreenPadding {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            appliances.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { ApplianceCard(it, now, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ApplianceCard(a: Appliance, now: LocalDateTime, modifier: Modifier) {
    Panel(modifier, compact = true) {
        CardHeader(a.nombre, a.icon(), a.tint(), compact = true)
        if (!a.disponible) {
            Pending(a.pendingText(), a.modelo.orEmpty())
            return@Panel
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp)) {
            when (a.tipo) {
                "frigorifico" -> Fridge(a)
                "placa" -> Hob(a)
                else -> Program(a, now)
            }
        }
        a.avisos.orEmpty().forEach {
            Text(it, style = Type.Small.copy(color = Palette.Accent, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Lavavajillas, secadora, horno: lo que queda en grande, o terminado, o parado. */
@Composable
private fun Program(a: Appliance, now: LocalDateTime) {
    val detail = listOfNotNull(readable(a.programa), readable(a.fase)).distinct().joinToString(" · ")
    val finished = if (a.washing) a.finishedAt(now) else null
    when {
        a.en_marcha -> {
            val left = a.minutesLeft(now)
            Text(left?.let { minutesText(it) } ?: "En marcha", style = Type.Title.copy(fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 60.sp, color = a.tint()), maxLines = 1)
            a.endsAt(now)?.let { Text("Acaba a las ${it.format(HourMinute)}", style = Type.Body.copy(color = Palette.Ink), maxLines = 1) }
            a.temp?.let { Text("${it.roundToInt()}°", style = Type.Body.copy(color = Palette.InkSoft), maxLines = 1) }
        }
        finished != null -> {
            Text("✅ Terminado", style = Type.Large.copy(fontWeight = FontWeight.SemiBold, color = Palette.Online), maxLines = 1)
            Text("A las ${finished.format(HourMinute)}", style = Type.Body.copy(color = Palette.InkSoft), maxLines = 1)
        }
        else -> Text(if (a.tipo == "horno") "Apagado" else "Parado", style = Type.Large.copy(fontWeight = FontWeight.SemiBold, color = Palette.InkMuted), maxLines = 1)
    }
    if (detail.isNotEmpty() && (a.en_marcha || finished != null)) Text(detail, style = Type.Small.copy(color = Palette.InkSoft), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    if (a.puerta == true && !a.en_marcha) Text("🚪 Puerta abierta", style = Type.Small.copy(color = Palette.InkSoft), modifier = Modifier.padding(top = 4.dp))
}

/** Frigorífico: nevera y congelador, cada uno en su recuadro. */
@Composable
private fun Fridge(a: Appliance) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Nevera" to a.temp_nevera, "Congelador" to a.temp_congelador).forEach { (name, t) ->
            Column(Modifier.weight(1f).background(Palette.Highlight, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(name, style = Type.Small.copy(color = Palette.InkSoft), maxLines = 1)
                Text(t?.let { "${it.roundToInt()}°" } ?: "—", style = Type.Large.copy(fontWeight = FontWeight.SemiBold, color = if (t == null) Palette.InkMuted else Palette.Ink), maxLines = 1)
            }
        }
    }
}

/** Placa: encendida o apagada y, si quema, el aviso. */
@Composable
private fun Hob(a: Appliance) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (a.encendida == true) "🔥 Encendida" else "Apagada", style = Type.Large.copy(fontWeight = FontWeight.SemiBold, color = if (a.encendida == true) HotWater else Palette.InkMuted), maxLines = 1)
    }
    if (a.caliente == true) Text("⚠️ Aún caliente", style = Type.Body.copy(color = HotWater, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp))
}
