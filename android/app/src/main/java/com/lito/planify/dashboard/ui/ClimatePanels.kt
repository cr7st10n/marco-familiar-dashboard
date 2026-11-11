package com.lito.planify.dashboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lito.planify.dashboard.data.WaterHeater
import com.lito.planify.dashboard.data.Zone
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type
import kotlin.math.roundToInt

/** Azul de la climatización y naranja del agua caliente. */
internal val ClimateBlue = Color(0xFF2F80ED)
internal val HotWater = Color(0xFFE8743B)
private val Cooling = Color(0xFF2F9FD9)
private val HeatingZone = Color(0xFFE8604C)

/** «Calentando», «Enfriando»… de una zona, con su emoji y color; null si no hay nada que decir. */
internal fun Zone.activity(): Pair<String, Color>? = when {
    accion == "heating" -> "🔥 Calentando" to HeatingZone
    accion == "cooling" -> "❄️ Enfriando" to Cooling
    accion == "drying" -> "💧 Secando" to Cooling
    accion == "fan" -> "🌀 Ventilando" to Palette.InkSoft
    modo == "off" || accion == "off" -> "Apagado" to Palette.InkMuted
    accion == "idle" -> "En reposo" to Palette.InkMuted
    else -> null
}

/** Nombre bonito del modo del termo (Ariston: IMEMORY, GREEN, PROGRAM, BOOST…). */
internal fun heaterMode(mode: String?): String? = when (mode?.uppercase()) {
    null, "" -> null
    "IMEMORY", "I_MEMORY" -> "iMemory"
    "GREEN" -> "Green"
    "BOOST" -> "Boost"
    "PROGRAM", "PROG", "PROGRAMMING" -> "Programado"
    "MANUAL" -> "Manual"
    "OFF" -> "Apagado"
    else -> mode.lowercase().replaceFirstChar { it.uppercase() }
}

/** «3 duchas» / «1 ducha». */
internal fun showers(n: Double): String = n.roundToInt().let { "$it ${if (it == 1) "ducha" else "duchas"}" }

/**
 * Pestaña Casa: las zonas de la climatización (Airzone) en una cuadrícula de 2 columnas, cada una con su temperatura
 * grande, la consigna y si calienta o enfría. Solo para mirar. Sin zonas configuradas, el aviso de que falta conectarla.
 */
@Composable
internal fun ZonesPanel(zones: List<Zone>?, modifier: Modifier) {
    Panel(modifier, compact = true) {
        CardHeader("Clima", Icons.Outlined.AcUnit, ClimateBlue, compact = true)
        if (zones.isNullOrEmpty()) {
            Pending("Aquí saldrá la temperatura de cada zona", "Airzone · 4 zonas")
            return@Panel
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            zones.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { ZoneBox(it, Modifier.weight(1f).fillMaxHeight()) }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ZoneBox(z: Zone, modifier: Modifier) {
    val activity = z.activity()
    Column(
        modifier.background(Palette.Highlight, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(z.nombre, style = Type.Small.copy(color = Palette.InkSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(z.temp?.let { "${it.decimal()}°" } ?: "—", style = Type.Large.copy(fontWeight = FontWeight.SemiBold, color = if (z.temp == null) Palette.InkMuted else Palette.Ink), maxLines = 1)
            z.consigna?.takeIf { z.modo != "off" }?.let {
                Text("→ ${it.decimal()}°", style = Type.Tiny.copy(color = Palette.InkSoft), maxLines = 1, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            }
        }
        when {
            z.temp == null -> Text("Sin datos", style = Type.Tiny.copy(color = Palette.InkMuted), maxLines = 1)
            activity != null -> Text(activity.first, style = Type.Tiny.copy(color = activity.second, fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/**
 * Pestaña Casa: el termo. A la izquierda un depósito que se llena según la temperatura del agua (entre sus límites), con
 * los grados grandes, las duchas que quedan y el modo; abajo la temperatura pedida con − / + y el botón Boost.
 * Sin datos todavía (no está en HA o no responde), solo el aviso.
 */
@Composable
internal fun WaterHeaterPanel(t: WaterHeater?, actions: HomeCallbacks, modifier: Modifier) {
    Panel(modifier, compact = true) {
        CardHeader("Agua caliente", Icons.Outlined.WaterDrop, HotWater, compact = true)
        if (t == null || !t.disponible) {
            Pending("Aquí saldrá la temperatura del agua y las duchas que quedan", "Termo Ariston Lydos Hybrid · 100 l")
            return@Panel
        }
        val lo = t.min ?: 40.0
        val hi = t.max ?: 65.0
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Tank(t.temp?.let { ((it - lo) / (hi - lo)).toFloat() } ?: 0f, t.calentando == true || t.boost, Modifier.width(54.dp).height(96.dp))
            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(t.temp.degrees(), style = Type.Title.copy(fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 60.sp), maxLines = 1)
                    t.duchas?.let { Text("🚿 ${showers(it)}", style = Type.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp)) }
                }
                val status = listOfNotNull(
                    heaterMode(t.modo)?.let { "Modo $it" },
                    if (t.calentando == true) "calentando" else if (t.calentando == false) "en reposo" else null
                ).joinToString(" · ")
                if (status.isNotEmpty()) Text(status, style = Type.Small.copy(color = if (t.calentando == true || t.boost) HotWater else Palette.InkSoft), maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Objetivo", style = Type.Small.copy(color = Palette.InkSoft), modifier = Modifier.padding(end = 14.dp))
            RoundButton("−", HotWater, size = 56) { t.consigna?.let { if (it - 1 >= lo) actions.onHeaterTemp(it - 1) } }
            Text(t.consigna.degrees(), style = Type.Large.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp))
            RoundButton("+", HotWater, size = 56) { t.consigna?.let { if (it + 1 <= hi) actions.onHeaterTemp(it + 1) } }
            Box(Modifier.weight(1f))
            Chip(if (t.boost) "⚡ Boost activo" else "⚡ Boost", selected = t.boost, color = HotWater, modifier = Modifier.width(200.dp)) { actions.onHeaterBoost(!t.boost) }
        }
    }
}

/** El depósito del termo: contorno redondeado y el agua caliente hasta [level] (0–1); más intensa si calienta. */
@Composable
private fun Tank(level: Float, heating: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val r = CornerRadius(size.width / 2.6f)
        drawRoundRect(HotWater.copy(alpha = 0.10f), cornerRadius = r)
        val pad = stroke + 4.dp.toPx()
        val inner = Size(size.width - 2 * pad, size.height - 2 * pad)
        val h = inner.height * level.coerceIn(0.08f, 1f)
        drawRoundRect(
            HotWater.copy(alpha = if (heating) 0.95f else 0.7f),
            topLeft = Offset(pad, pad + inner.height - h),
            size = Size(inner.width, h),
            cornerRadius = CornerRadius(inner.width / 3f)
        )
        drawRoundRect(HotWater, cornerRadius = r, style = Stroke(stroke))
    }
}

/** Lo que aún no está conectado: el aviso en gris y, debajo, qué aparato es. */
@Composable
internal fun Pending(what: String, device: String) {
    Text("Pendiente de conectar", style = Type.Body.copy(color = Palette.InkMuted, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 14.dp))
    Text(what, style = Type.Small.copy(color = Palette.InkMuted), modifier = Modifier.padding(top = 4.dp))
    Text(device, style = Type.Tiny.copy(color = Palette.InkMuted), modifier = Modifier.padding(top = 8.dp))
}
