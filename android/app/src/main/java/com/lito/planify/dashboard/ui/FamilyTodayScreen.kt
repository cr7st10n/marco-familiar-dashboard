package com.lito.planify.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lito.planify.dashboard.UiState
import com.lito.planify.dashboard.data.Event
import com.lito.planify.dashboard.data.Motorbike
import com.lito.planify.dashboard.data.Security
import com.lito.planify.dashboard.data.alert
import com.lito.planify.dashboard.data.emoji
import com.lito.planify.dashboard.data.isArmed
import com.lito.planify.dashboard.data.stateText
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** Días que enseña la franja de la semana (hoy incluido). */
private const val WEEK_DAYS = 7

/** Días de la semana cuando hay algo de la cocina que contar: los otros 2 los ocupa su tarjeta. */
private const val WEEK_DAYS_WITH_KITCHEN = 5

/**
 * Portada de la familia (calendarios iCal), pensada para verse de lejos:
 * - arriba, un bloque ancho con la hora, la fecha y el tiempo de hoy y de los próximos días, y a la derecha lo
 *   siguiente que toca (con cuánto falta);
 * - en el centro, la semana: hoy y los 6 días siguientes, cada uno con los turnos (Shifter) en una pastilla, todas las
 *   citas (familia, fútbol, Barça…), como la pestaña Calendario, y abajo el menú del cole de ese día;
 * - abajo, tres resúmenes que llevan a su pestaña: tareas de los niños, compra, y luz y coche.
 */
@Composable
fun FamilyTodayScreen(state: UiState, onOpen: (Page) -> Unit) {
    val now = state.now
    val today = now.toLocalDate()
    val events = state.content?.events.orEmpty()
    val shifts = state.content?.sessions.orEmpty()
    val menu = state.content?.menu.orEmpty()
    // Lo que está pasando ahora (con hora, empezado y sin terminar) va antes que lo próximo
    val live = events.firstOrNull { !it.all_day && !it.at.isAfter(now) && (it.end ?: it.at.plusHours(1)).isAfter(now) }
    val next = live ?: events.firstOrNull { !it.at.isBefore(now) && it.at.toLocalDate() < today.plusDays(WEEK_DAYS.toLong()) }
    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().weight(0.25f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Hora, fecha y tiempo en un solo bloque ancho (dos tercios); lo siguiente, a la derecha
            HeaderBlock(state, Modifier.weight(2f).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (next != null) EventTapTarget(next, Modifier.fillMaxSize()) { NextCompact(next, now, live = next === live) }
                else Box(Modifier.fillMaxSize().background(Palette.Card, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                    Text("Semana sin citas", style = Type.Body.copy(color = Palette.InkMuted))
                }
            }
        }
        // Con seguridad o coche: calendario a media altura y una fila de tarjetas (Seguridad, Coche, Clima) debajo
        val extras = state.shopping?.seguridad != null || state.shopping?.coche != null
        // Lavadora/secadora/lavavajillas en marcha o recién terminados, o un aviso (sal, puerta abierta): 5 días y la cocina
        val kitchen = kitchenNews(state.shopping?.cocina, now)
        Row(Modifier.fillMaxWidth().weight(if (extras) 0.29f else 0.53f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            (0 until if (kitchen.isEmpty()) WEEK_DAYS else WEEK_DAYS_WITH_KITCHEN).forEach { i ->
                val day = today.plusDays(i.toLong())
                WeekDay(
                    day, today, now,
                    events.filter { it.at.toLocalDate() == day },
                    shifts.filter { it.at.toLocalDate() == day },
                    menu.firstOrNull { it.at.toLocalDate() == day },
                    Modifier.weight(1f).fillMaxHeight(),
                    compact = extras
                )
            }
            // Más o menos el ancho de los 2 días que deja libres
            if (kitchen.isNotEmpty()) KitchenTile(kitchen, Modifier.weight((WEEK_DAYS - WEEK_DAYS_WITH_KITCHEN).toFloat()).fillMaxHeight()) { onOpen(Page.Kitchen) }
        }
        if (extras) Row(Modifier.fillMaxWidth().weight(0.23f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SecurityTile(state.shopping?.seguridad, now, Modifier.weight(1f).fillMaxHeight()) { onOpen(Page.Home) }
            CarTile(state, Modifier.weight(1f).fillMaxHeight()) { onOpen(Page.Home) }
            ClimateTile(state, Modifier.weight(1.25f).fillMaxHeight()) { onOpen(Page.Home) }
        }
        Row(Modifier.fillMaxWidth().weight(if (extras) 0.23f else 0.22f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            KidsTile(state, Modifier.weight(1f).fillMaxHeight()) { onOpen(Page.Tasks) }
            ShoppingTile(state, Modifier.weight(1f).fillMaxHeight()) { onOpen(Page.Tasks) }
            // Con la tarjeta del coche arriba, aquí solo la luz
            EnergyTile(state, Modifier.weight(1.25f).fillMaxHeight(), withCar = !extras) { onOpen(Page.Home) }
        }
    }
}

// --- Arriba ---

/** Lo próximo (o, mientras dura, lo de ahora), del color de su calendario: cuánto falta o hasta cuándo, título y cuándo. */
@Composable
private fun NextCompact(event: Event, now: LocalDateTime, live: Boolean) {
    val color = parseColor(event.calendar_color, Palette.Accent)
    val ink = onColor(color)
    val left = countdown(now, event.at, maxMinutes = 24 * 60)?.removePrefix("en ") ?: relativeBadge(now, event.at).lowercase(Spanish)
    Column(
        Modifier.fillMaxSize().background(color, RoundedCornerShape(28.dp)).padding(horizontal = 30.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (live) "● AHORA" else "LO PRÓXIMO", style = Type.Label.copy(color = ink.copy(alpha = 0.9f), fontSize = 17.sp), modifier = Modifier.weight(1f))
            Text(
                if (live) "hasta ${(event.end ?: event.at.plusHours(1)).format(HourMinute)}" else "en $left",
                style = Type.Small.copy(color = ink, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(ink.copy(alpha = 0.18f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
        Text(event.title, style = Type.Large.copy(color = ink, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(
            "${relativeDay(event.at.toLocalDate(), now.toLocalDate())} · ${event.rangeLabel() ?: event.timeLabel()}",
            style = Type.Small.copy(color = ink.copy(alpha = 0.85f)),
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// --- Centro: la semana ---

/** Un día de la semana: cabecera (hoy resaltado), el turno en una pastilla y las citas con su color. */
@Composable
internal fun WeekDay(
    day: LocalDate, today: LocalDate, now: LocalDateTime, events: List<Event>, shifts: List<Event>, menu: Event?, modifier: Modifier,
    forecast: com.lito.planify.dashboard.data.Forecast? = null,
    small: Boolean = false,
    /** Portada a media altura: cabecera menor, hoy sin lo ya pasado, 2 citas como mucho («+N más») y menú en 2 líneas. */
    compact: Boolean = false
) {
    val isToday = day == today
    Column(modifier.background(if (isToday) Palette.Card else Palette.Card.copy(alpha = 0.6f), RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp).alpha(if (day < today) 0.55f else 1f)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (isToday) "Hoy" else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Spanish).trimEnd('.').capitalized(),
                style = Type.Small.copy(color = if (isToday) Palette.Accent else Palette.InkSoft, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f)
            )
            // Calendario: el mes en el lunes de cada semana y, resaltado, en el día 1 (donde cambia de mes)
            if (small && (day.dayOfWeek == DayOfWeek.MONDAY || day.dayOfMonth == 1)) Text(
                day.month.getDisplayName(TextStyle.SHORT, Spanish).trimEnd('.'),
                style = Type.Small.copy(color = if (day.dayOfMonth == 1) Palette.Accent else Palette.InkSoft, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(end = 8.dp, bottom = 3.dp)
            )
            Text("${day.dayOfMonth}", style = (if (compact) Type.Body else Type.Large).copy(color = if (isToday) Palette.Accent else Palette.Ink, fontWeight = FontWeight.SemiBold))
        }
        // Calendario: el tiempo del día (icono, máx/mín y lluvia) bajo la cabecera, si hay previsión
        forecast?.dayIndex(day)?.takeIf { it >= 0 }?.let { k ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                DayWeatherIcon(forecast, day, today, 38)
                Text(
                    "${forecast.daily.temperature_2m_max[k].degrees()} ${forecast.daily.temperature_2m_min[k].degrees()}",
                    style = Type.Tiny.copy(color = Palette.InkSoft, fontSize = 17.sp),
                    modifier = Modifier.padding(start = 6.dp).weight(1f)
                )
                Text("💧${forecast.daily.precipitation_probability_max[k] ?: 0}%", style = Type.Tiny.copy(color = if ((forecast.daily.precipitation_probability_max[k] ?: 0) >= 40) Palette.Rain else Palette.InkMuted, fontSize = 15.sp))
            }
        }
        shifts.forEach { s ->
            val c = parseColor(s.calendar_color)
            EventTapTarget(s, Modifier.padding(top = 8.dp)) {
                Text(
                    "${s.title} ${s.rangeLabel().orEmpty()}",
                    style = Type.Tiny.copy(color = onColor(c), fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().background(c, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
        Hairline(Modifier.padding(vertical = if (compact) 6.dp else 10.dp))
        val pending = if (compact && isToday) events.filterNot { it.isPast(now) } else events
        // El menú ocupa sitio: con él caben 2 citas enteras; sin él (fines de semana), 3
        val shown = if (compact) pending.take(if (menu != null) COMPACT_EVENTS - 1 else COMPACT_EVENTS) else pending
        Column(Modifier.weight(1f).fadingScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
            if (shown.isEmpty()) Text("—", style = Type.Small.copy(color = Palette.InkMuted))
            shown.forEach { e -> EventTapTarget(e) { WeekItem(e, past = e.isPast(now), small = small, lines = if (compact) 2 else 4, compact = compact) } }
        }
        // Fuera de lo que se desplaza: siempre a la vista, aunque las citas no quepan enteras
        if (pending.size > shown.size) Text("+${pending.size - shown.size} más", style = Type.Tiny.copy(color = Palette.InkSoft, fontSize = 17.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 2.dp))
        menu?.let { MenuPill(it, Modifier.padding(top = if (compact) 4.dp else 8.dp), small = small, lines = if (compact) 2 else 4) }
    }
}

private val MenuGreen = Color(0xFF7CB342)

/** Citas por día en la portada a media altura (una menos si hay menú); el resto, «+N más» (todas en la pestaña Calendario). */
private const val COMPACT_EVENTS = 3

/** El menú del cole de un día: «🍽️ primero · segundo» y, si un niño come otra cosa, «Nombre: …» debajo. */
@Composable
private fun MenuPill(menu: Event, modifier: Modifier, small: Boolean = false, lines: Int = 4) {
    val other = Regex("""\s*\(([^():]+):\s*([^()]+)\)\s*$""").find(menu.title)
    EventTapTarget(menu, modifier) {
        Column(Modifier.fillMaxWidth().background(MenuGreen.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                "🍽️ " + (other?.let { menu.title.substring(0, it.range.first) } ?: menu.title),
                style = Type.Tiny.copy(color = Palette.Ink, fontSize = if (small) 17.sp else 19.sp, lineHeight = if (small) 21.sp else 24.sp),
                maxLines = lines,
                overflow = TextOverflow.Ellipsis
            )
            if (lines > 2) other?.let {
                Text("${it.groupValues[1]}: ${it.groupValues[2]}", style = Type.Tiny.copy(color = Palette.InkSoft, fontSize = 17.sp, lineHeight = 21.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WeekItem(e: Event, past: Boolean, small: Boolean = false, lines: Int = 4, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).alpha(if (past) 0.4f else 1f)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(parseColor(e.calendar_color), RoundedCornerShape(3.dp)))
        Column(Modifier.padding(start = 8.dp)) {
            Text(if (e.all_day) "Todo el día" else e.at.format(HourMinute), style = Type.Tiny.copy(color = Palette.InkSoft, fontSize = if (small) 17.sp else 19.sp, fontWeight = FontWeight.SemiBold))
            // En la portada a media altura, solo el título más pequeño (la hora igual): caben más citas
            Text(e.title, style = Type.Small.copy(color = Palette.Ink, fontSize = if (compact) 19.sp else if (small) 20.sp else 23.sp, lineHeight = if (compact) 23.sp else if (small) 25.sp else 28.sp), maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}

// --- Abajo: resúmenes ---

@Composable
private fun Tile(title: String, emoji: String, tint: Color, modifier: Modifier, onClick: (() -> Unit)? = null, background: Color = Palette.Card, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(background, RoundedCornerShape(28.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Un logo (p. ej. la «T» de Tesla) con sus colores en lugar del emoji, en el mismo hueco (el emoji invisible
            // debajo) para que la tarjeta quede alineada con las de al lado
            Box(contentAlignment = Alignment.Center) {
                Text(emoji, style = Type.Body, modifier = if (icon != null) Modifier.alpha(0f) else Modifier)
                if (icon != null) Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
            }
            Text(title.uppercase(Spanish), style = Type.Label.copy(color = tint, fontSize = 18.sp), modifier = Modifier.padding(start = 10.dp))
        }
        content()
    }
}

@Composable
private fun KidsTile(state: UiState, modifier: Modifier, onClick: () -> Unit) {
    val chores = state.chores
    Tile("Niños", "🐾", Palette.Warm, modifier, onClick) {
        if (chores == null) return@Tile
        val left = chores.pending()
        Text(if (left == 0) "🎉 Todo hecho" else "$left ${if (left == 1) "tarea" else "tareas"} por hacer", style = Type.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            chores.ninos.orEmpty().forEach { k ->
                Text(
                    "${k.nombre} ⭐ ${k.estrellas}",
                    style = Type.Small.copy(color = Palette.Ink, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(parseColor(k.color, Palette.Warm).copy(alpha = 0.14f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
        chores.pedidas?.size?.takeIf { it > 0 }?.let { Text("🎁 $it ${if (it == 1) "canje" else "canjes"} por aprobar", style = Type.Tiny.copy(color = Palette.Accent, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun ShoppingTile(state: UiState, modifier: Modifier, onClick: () -> Unit) {
    val items = state.shopping?.shopping().orEmpty()
    Tile("Compra", "🛒", Palette.Online, modifier, onClick) {
        Text(if (items.isEmpty()) "Nada que comprar" else "${items.size} ${if (items.size == 1) "cosa" else "cosas"}", style = Type.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
        Text(items.take(4).joinToString(" · ") { it.producto }, style = Type.Small.copy(color = Palette.InkSoft), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun EnergyTile(state: UiState, modifier: Modifier, withCar: Boolean = true, onClick: () -> Unit) {
    val now = state.now
    val window = priceWindow(state.energy?.prices.orEmpty(), now)
    val current = window.lastOrNull { !it.time.isAfter(now) }
    val min = window.minOfOrNull { it.eur_kwh } ?: 0.0
    val max = window.maxOfOrNull { it.eur_kwh } ?: 0.0
    val car = state.shopping?.coche?.takeIf { withCar }
    Tile(if (withCar) "Luz y coche" else "Luz", "⚡", Amber, modifier, onClick) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            car?.bateria?.let { BatteryGauge(it, car, Modifier.width(170.dp).fillMaxHeight().padding(end = 18.dp)) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                current?.let {
                    Text(
                        "${euros(it.eur_kwh)} €/kWh",
                        style = Type.Small.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.background(priceColor(it.eur_kwh, min, max), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
                if (window.isNotEmpty()) {
                    PriceBars(window, now, min, max, Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = 4.dp))
                    PriceHours(window, fontSize = 13)
                }
            }
        }
    }
}

// --- Fila de en medio: seguridad, coche y clima ---

/** Cerradura y alarma en una línea cada una; si pasa algo, la tarjeta entera en rojo con el aviso. */
@Composable
private fun SecurityTile(security: Security?, now: LocalDateTime, modifier: Modifier, onClick: () -> Unit) {
    val alert = security?.alert(now)
    Tile("Seguridad", "🛡️", if (alert != null) Red else Palette.Online, modifier, onClick, background = if (alert != null) Red.copy(alpha = 0.12f) else Palette.Card) {
        if (alert != null) {
            Text(alert, style = Type.Body.copy(color = Red, fontWeight = FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            return@Tile
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            security?.cerradura?.let { lock ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TileLine("${lock.emoji()} ${lock.stateText()}", if (lock.estado == null) Palette.InkMuted else Palette.Ink, Modifier.weight(1f))
                    lock.bateria?.let { Text("🔋 ${it.toInt()} %", style = Type.Small.copy(color = Palette.InkSoft)) }
                }
            }
            security?.alarma?.let { alarm -> TileLine("🛡️ ${alarm.stateText()}", if (alarm.isArmed()) Palette.Online else if (alarm.estado == null) Palette.InkMuted else Palette.Ink) }
        }
    }
}

/** Batería en semicírculo (con autonomía), si está enchufado o cargando y la próxima franja buena para cargar. */
@Composable
private fun CarTile(state: UiState, modifier: Modifier, onClick: () -> Unit) {
    val car = state.shopping?.coche
    val level = car?.bateria
    Tile(car?.modelo?.takeIf { it.isNotBlank() } ?: "Coche", "🚗", if (car?.cargando == true) Charging else Palette.Online, modifier, onClick, icon = carLogo(car)) {
        if (car == null || level == null) {
            TileLine("Sin datos del coche", Palette.InkMuted, Modifier.padding(top = 8.dp))
            Text("Saldrán cuando el coche despierte", style = Type.Small.copy(color = Palette.InkMuted), modifier = Modifier.padding(top = 4.dp))
            state.shopping?.moto?.let { Box(Modifier.weight(1f)); MotoLine(it) }
            return@Tile
        }
        val now = state.now
        val window = cheapestWindow(state.energy?.prices.orEmpty(), now, 4, now.toLocalDate().plusDays(2).atStartOfDay(), state.carCharge)
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BatteryGauge(level, car, Modifier.width(170.dp).fillMaxHeight().padding(end = 18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Dormido: «💤 Dormido» y, debajo, «desde las 18:46» (en una línea no cabe)
                car.asleepText()?.let { text ->
                    TileLine("💤 Dormido", Palette.InkSoft)
                    text.substringAfter("Dormido ", "").takeIf { it.isNotEmpty() }?.let { Text(it, style = Type.Small.copy(color = Palette.InkMuted), maxLines = 1) }
                } ?: TileLine(if (car.cargando) "⚡ Cargando" else if (car.enchufado) "🔌 Enchufado" else "🅿️ Sin enchufar", if (car.cargando) Charging else Palette.Ink)
                window?.let { (from, to, _) ->
                    val day = when (from.toLocalDate()) { now.toLocalDate() -> "hoy"; now.toLocalDate().plusDays(1) -> "mañana"; else -> from.toLocalDate().shortLabel().lowercase(Spanish) }
                    Text("Cargar $day ${from.format(HourMinute)}–${to.format(HourMinute)}", style = Type.Small.copy(color = Palette.InkSoft), maxLines = 2)
                }
            }
        }
        state.shopping?.moto?.let { MotoLine(it) }
    }
}

/** La moto en una línea, debajo del coche: «(logo) Moto 30 % · 39 km» (y «· ⚡ cargando» si carga); 🛵 sin logo. */
@Composable
private fun MotoLine(moto: Motorbike) {
    val logo = motoLogo(moto)
    val text = moto.bateria?.let { b ->
        listOfNotNull("Moto ${b.roundToInt()} %", moto.autonomia_km?.let { "${it.roundToInt()} km" }, "⚡ cargando".takeIf { moto.cargando == true }).joinToString(" · ")
    } ?: "Moto: sin datos"
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) Icon(logo, contentDescription = null, tint = SilenceRed, modifier = Modifier.size(22.dp))
        else Text("🛵", style = Type.Small)
        Text(text, style = Type.Small.copy(color = if (moto.cargando == true) Charging else Palette.InkSoft, fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * Acceso directo al clima de Casa: la temperatura de cada zona (Airzone) en pastillas de dos en dos o, sin zonas, el
 * termostato; abajo, una línea con el termo (grados, duchas y si está en Boost). Lo que falte, «pendiente de conectar».
 */
@Composable
private fun ClimateTile(state: UiState, modifier: Modifier, onClick: () -> Unit) {
    val casa = state.shopping?.casa
    val clima = casa?.clima
    val zones = casa?.zonas?.takeIf { z -> z.any { it.temp != null } }
    val heater = casa?.termo
    Tile("Clima", "❄️", Palette.Rain, modifier, onClick) {
        when {
            zones != null -> Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                zones.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { z ->
                            val tint = z.activity()?.second?.takeIf { z.accion == "heating" || z.accion == "cooling" } ?: Palette.Rain
                            Text(
                                "${z.nombre} ${z.temp.degrees()}",
                                style = Type.Small.copy(color = Palette.Ink, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).background(tint.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            clima != null -> {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(clima.temp.degrees(), style = Type.Large.copy(fontWeight = FontWeight.SemiBold))
                    clima.consigna?.let { Text("→ ${it.degrees()}", style = Type.Small.copy(color = Palette.InkSoft), modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) }
                }
                clima.nombre?.let { Text(it, style = Type.Small.copy(color = Palette.InkSoft), maxLines = 1) }
            }
            else -> {
                TileLine("Pendiente de conectar", Palette.InkMuted, Modifier.padding(top = 8.dp))
                Text("Aquí saldrá la temperatura de cada zona", style = Type.Small.copy(color = Palette.InkMuted), modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (heater != null) {
            Box(Modifier.weight(1f))
            val text = if (!heater.disponible) "🚿 Termo: pendiente de conectar"
            else listOfNotNull("🚿 Termo ${heater.temp.degrees()}", heater.duchas?.let { showers(it) }, if (heater.boost) "⚡ Boost" else null).joinToString(" · ")
            Text(text, style = Type.Small.copy(color = if (heater.disponible) (if (heater.boost) HotWater else Palette.Ink) else Palette.InkMuted, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** La cocina en la portada: cada aparato en marcha o recién terminado y cada aviso, una línea con su emoji. */
@Composable
private fun KitchenTile(news: List<Pair<com.lito.planify.dashboard.data.Appliance, String>>, modifier: Modifier, onClick: () -> Unit) {
    val alert = news.any { (a, text) -> text in a.avisos.orEmpty() }
    Tile("Cocina", "🍳", if (alert) Palette.Accent else Palette.Night, modifier, onClick) {
        Column(Modifier.padding(top = 8.dp).fadingScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            news.forEach { (a, text) ->
                val warning = text in a.avisos.orEmpty()
                Column {
                    Text("${a.emoji()} ${a.nombre}", style = Type.Small.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text,
                        style = Type.Body.copy(color = if (warning) Palette.Accent else Palette.Ink, fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TileLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, style = Type.Body.copy(color = color, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
