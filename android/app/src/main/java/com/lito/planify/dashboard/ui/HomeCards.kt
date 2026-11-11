package com.lito.planify.dashboard.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lito.planify.dashboard.UiState
import com.lito.planify.dashboard.data.CarStatus
import com.lito.planify.dashboard.data.Energy
import com.lito.planify.dashboard.data.Event
import com.lito.planify.dashboard.data.HourPrice
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Hora de un evento para las listas: «Todo el día» si es de día completo. */
fun Event.timeLabel(): String = if (all_day) "Todo el día" else at.format(HourMinute)

/** Hora de inicio y fin («15:00–22:00»); de día completo, nada. */
fun Event.rangeLabel(): String? = if (all_day) null else listOfNotNull(at.format(HourMinute), end?.format(HourMinute)).joinToString("–")

/** Texto legible sobre un fondo de color: tinta oscura en los claros (los turnos de Shifter son pastel). */
/** Letras sobre un color: oscuras (las del modo claro, también en modo noche) sobre colores claros; blancas sobre oscuros. */
fun onColor(background: Color): Color = if (background.luminance() > 0.45f) InkOnLight else Color.White

/** Tinta del modo claro, para letras sobre fondos de color claros en cualquier modo. */
internal val InkOnLight = Color(0xFF221D18)

internal val Green = Color(0xFF3BA55C)
/** Azul «eléctrico» de la batería del coche mientras carga, para que no se confunda con el verde de batería alta. */
internal val Charging = Color(0xFF1E90E8)
internal val Amber = Color(0xFFF5A623)
internal val Red = Color(0xFFD9483B)

/** Verde → ámbar → rojo según el precio entre el mínimo y el máximo del día. */
internal fun priceColor(price: Double, min: Double, max: Double): Color {
    val t = if (max > min) ((price - min) / (max - min)).toFloat().coerceIn(0f, 1f) else 0f
    return if (t < 0.5f) lerp(Green, Amber, t * 2) else lerp(Amber, Red, (t - 0.5f) * 2)
}

internal fun euros(value: Double) = String.format(Locale.forLanguageTag("es-ES"), "%.3f", value)

/** Tarjeta compacta (comparten columna): cabecera de una línea con lo que se quiera a la derecha. */
@Composable
internal fun CompactCard(
    title: String, icon: ImageVector, tint: Color, modifier: Modifier,
    trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.background(Palette.Card, RoundedCornerShape(32.dp)).padding(horizontal = 30.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(42.dp).background(tint.copy(alpha = 0.13f), RoundedCornerShape(13.dp))) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Text(title, style = Type.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.padding(start = 14.dp).weight(1f))
            trailing()
        }
        content()
    }
}

/**
 * Hoy en casa: el turno de hoy y de mañana (con el color de Shifter) y el menú del cole de hoy o, si hoy no
 * hay cole, el del siguiente día que lo tenga.
 */
@Composable
fun HouseCard(state: UiState, modifier: Modifier) {
    val today = state.now.toLocalDate()
    val shifts = state.content?.sessions.orEmpty()
    val menu = state.content?.menu.orEmpty()
    val shopping = state.shopping?.shopping()
    CompactCard(
        "Hoy en casa", Icons.Outlined.Home, Palette.Warm, modifier,
        trailing = {
            // Lo que queda en la lista de la compra (Bring!) y las tareas de los niños sin hacer; todo, en la pestaña de tareas
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.chores?.pending()?.takeIf { it > 0 }?.let { left ->
                    Text(
                        "🐾 $left",
                        style = Type.Small.copy(color = Palette.Warm, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.background(Palette.Warm.copy(alpha = 0.13f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 2.dp)
                    )
                }
                if (!shopping.isNullOrEmpty()) {
                    Text(
                        "🛒 ${shopping.size}",
                        style = Type.Small.copy(color = Palette.Online, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.background(Palette.Online.copy(alpha = 0.13f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 2.dp)
                    )
                }
            }
        }
    ) {
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val todayShift = shifts.firstOrNull { it.at.toLocalDate() == today }
            val tomorrowShift = shifts.firstOrNull { it.at.toLocalDate() == today.plusDays(1) }
            Row {
                Text((todayShift ?: tomorrowShift)?.calendar_name?.uppercase(Spanish) ?: "TURNO", style = Type.Tiny.copy(color = Palette.InkMuted, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp), modifier = Modifier.width(96.dp).padding(top = 6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (todayShift != null) ShiftPill(todayShift) else Text("Sin turno", style = Type.Small.copy(color = Palette.InkMuted))
                    if (tomorrowShift != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(parseColor(tomorrowShift.calendar_color), 12)
                            Text(
                                // Con el día de la semana, no «mañana»: uno de los turnos se llama «Mañana»
                                "${today.plusDays(1).weekdayName(TextStyle.SHORT).lowercase(Spanish).trimEnd('.')} · " +
                                    listOfNotNull(tomorrowShift.title, tomorrowShift.rangeLabel()).joinToString(" "),
                                style = Type.Tiny.copy(color = Palette.InkSoft),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            }
            val todayMenu = menu.firstOrNull { it.at.toLocalDate() == today }
            val nextMenu = todayMenu ?: menu.firstOrNull { it.at.toLocalDate() > today }
            Row {
                Text("MENÚ", style = Type.Tiny.copy(color = Palette.InkMuted, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp), modifier = Modifier.width(96.dp).padding(top = 4.dp))
                Column {
                    when {
                        nextMenu == null -> Text("Sin menú cargado", style = Type.Small.copy(color = Palette.InkMuted))
                        else -> {
                            if (todayMenu == null) {
                                Text(relativeDay(nextMenu.at.toLocalDate(), today).uppercase(Spanish), style = Type.Tiny.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp))
                            }
                            // «1.º · 2.º (Nombre: …)»: lo de otro niño (otro menú), en su propia línea
                            val other = Regex("""\s*\(([^():]+):\s*([^()]+)\)\s*$""").find(nextMenu.title)
                            Text(
                                other?.let { nextMenu.title.substring(0, it.range.first) } ?: nextMenu.title,
                                style = Type.Small.copy(color = Palette.Ink),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (other != null) {
                                Text(
                                    "${other.groupValues[1]}: ${other.groupValues[2]}",
                                    style = Type.Tiny.copy(color = Palette.InkSoft),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiftPill(shift: Event) {
    val color = parseColor(shift.calendar_color, Palette.Night)
    Text(
        listOfNotNull(shift.title, shift.rangeLabel()).joinToString("  "),
        style = Type.Small.copy(color = onColor(color), fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        modifier = Modifier.background(color, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

/**
 * Precio de la luz (PVPC): el de esta hora en la cabecera, del color de su franja (verde barato, rojo caro); 24 barras
 * del día con la hora actual marcada, y la hora más barata que queda hoy. Desde que REE publica mañana (≈20:30),
 * también la más barata de mañana.
 */
@Composable
fun EnergyCard(energy: Energy?, car: CarStatus?, now: LocalDateTime, modifier: Modifier) {
    val today = now.toLocalDate()
    val day = energy?.prices.orEmpty().filter { it.time.toLocalDate() == today }
    val current = day.lastOrNull { !it.time.isAfter(now) }
    val min = day.minOfOrNull { it.eur_kwh } ?: 0.0
    val max = day.maxOfOrNull { it.eur_kwh } ?: 0.0
    CompactCard(
        if (car?.bateria != null) "Luz y coche" else "Luz", Icons.Outlined.Bolt, Amber, modifier,
        trailing = {
            if (current != null) {
                val color = priceColor(current.eur_kwh, min, max)
                Text(
                    "${euros(current.eur_kwh)} €/kWh",
                    style = Type.Small.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.background(color, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    ) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            car?.bateria?.let { BatteryGauge(it, car, Modifier.width(170.dp).fillMaxHeight().padding(end = 22.dp)) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (day.isEmpty()) {
                    Text("Sin precios todavía", style = Type.Small.copy(color = Palette.InkMuted))
                } else {
                    PriceBars(day, now, min, max, Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp))
                    Text(cheapestLabel(energy!!.prices, now, today), style = Type.Tiny.copy(color = Palette.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Batería del coche como los «gauge» de Home Assistant: semicírculo grueso, la parte llena del color del nivel (verde
 * > 50 %, ámbar 20–50 %, rojo < 20 %; azul mientras carga) y el resto en gris claro; el % dentro y debajo la
 * autonomía. Mientras carga, la potencia en pequeño encima y el relleno parpadea.
 */
@Composable
internal fun BatteryGauge(level: Double, car: CarStatus, modifier: Modifier) {
    val pct = (level / 100.0).toFloat().coerceIn(0f, 1f)
    val color = when {
        car.cargando -> Charging
        level > 50 -> Green
        level >= 20 -> Amber
        else -> Red
    }
    val pulse = chargePulse(car.cargando)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            // Mientras carga, la potencia en pequeño justo encima del arco (no arriba del todo, junto al título)
            if (car.cargando) Text(
                "⚡ " + (car.potencia_kw?.let(::kilowatts) ?: "cargando"),
                style = Type.Tiny.copy(color = Charging, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.padding(bottom = (minOf(maxWidth, maxHeight * 2) - 18.dp) / 2 + 9.dp + 16.dp)
            )
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 18.dp.toPx()
                // El semicírculo más grande que cabe: ancho = 2·radio, alto = radio
                val diameter = minOf(size.width, size.height * 2) - stroke
                val left = (size.width - diameter) / 2
                val top = size.height - diameter / 2 - stroke / 2
                val arc = Size(diameter, diameter)
                drawArc(Palette.Line, 180f, 180f, false, Offset(left, top), arc, style = Stroke(stroke))
                drawArc(color.copy(alpha = chargeFillAlpha(pulse)), 180f, 180f * pct, false, Offset(left, top), arc, style = Stroke(stroke))
                if (pulse > 0f) drawArc(color.copy(alpha = pulse), 180f + 180f * pct, 180f * chargeSpan(pct), false, Offset(left, top), arc, style = Stroke(stroke))
            }
            Text("${level.toInt()} %", style = Type.Large.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        }
        Text(
            car.autonomia_km?.let { "${it.toInt()} km" } ?: "",
            style = Type.Tiny.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold),
            maxLines = 1
        )
    }
}

/** «Más barata: 03:00 · 0,152» de lo que queda de hoy y, si ya está, «mañana 04:00 · 0,140». */
private fun cheapestLabel(prices: List<HourPrice>, now: LocalDateTime, today: LocalDate): String {
    val rest = prices.filter { it.time.toLocalDate() == today && it.time.plusHours(1).isAfter(now) }.minByOrNull { it.eur_kwh }
    val tomorrow = prices.filter { it.time.toLocalDate() == today.plusDays(1) }.minByOrNull { it.eur_kwh }
    return listOfNotNull(
        rest?.let { "Más barata hoy ${it.time.format(HourMinute)} · ${euros(it.eur_kwh)}" },
        tomorrow?.let { "mañana ${it.time.format(HourMinute)} · ${euros(it.eur_kwh)}" }
    ).joinToString("   ")
}

/** Una barra por hora, del color de su precio; las pasadas, atenuadas; la actual, con un contorno. */
@Composable
internal fun PriceBars(day: List<HourPrice>, now: LocalDateTime, min: Double, max: Double, modifier: Modifier) {
    Canvas(modifier) {
        val n = day.size
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val floor = min * 0.85 // las barras arrancan algo por debajo del mínimo para que se vea la diferencia
        day.forEachIndexed { i, p ->
            val h = (size.height * ((p.eur_kwh - floor) / (max - floor).coerceAtLeast(1e-6))).toFloat().coerceIn(6f, size.height)
            val x = i * (w + gap)
            val isNow = !p.time.isAfter(now) && p.time.plusHours(1).isAfter(now)
            val past = p.time.plusHours(1) <= now
            val color = priceColor(p.eur_kwh, min, max).copy(alpha = if (past) 0.3f else 1f)
            drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(4.dp.toPx()))
            if (isNow) drawRoundRect(Palette.Ink, Offset(x - 2, size.height - h - 2), Size(w + 4, h + 4), CornerRadius(5.dp.toPx()), style = Stroke(3.dp.toPx()))
        }
    }
}

/** Mientras el coche carga, un brillo que sube y baja cada ~1,5 s para el tramo que se está llenando; 0 si no carga. */
@Composable
internal fun chargePulse(charging: Boolean): Float {
    if (!charging) return 0f
    val transition = rememberInfiniteTransition(label = "carga")
    return transition.animateFloat(0.2f, 0.8f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "pulso").value
}

/** Mientras carga, todo el relleno parpadea suave con [chargePulse] (de ~60 % a 100 % de opacidad); si no, opaco. */
internal fun chargeFillAlpha(pulse: Float) = if (pulse > 0f) (0.4f + pulse * 0.75f).coerceAtMost(1f) else 1f

/** Lo que ocupa el tramo «llenándose»: un 12 % por delante del nivel, sin pasar del 100 %. */
internal fun chargeSpan(pct: Float) = minOf(0.12f, 1f - pct)

internal fun kilowatts(kw: Double) = String.format(java.util.Locale("es", "ES"), "%.1f kW", kw)
