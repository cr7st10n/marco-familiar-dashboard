package com.lito.planify.dashboard.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lito.planify.dashboard.R

// Medidas en píxeles del panel (1920×1200): la app usa Density(1f), así que 1 dp = 1 sp = 1 px.

/**
 * Colores de la app. [dark] cambia al modo noche (fondo casi negro, tarjetas oscuras y letras claras); los colores de
 * los calendarios, de la luz o del coche no están aquí y se quedan igual. Es un estado de Compose: al cambiarlo, todo
 * lo que usa la paleta se vuelve a pintar.
 */
object Palette {
    var dark by mutableStateOf(false)

    private fun pick(light: Long, night: Long) = Color(if (dark) night else light)

    val Background get() = pick(0xFFF7EEE2, 0xFF15120F)
    val Rail get() = pick(0xFFF0E3D2, 0xFF1C1814)
    val Card get() = pick(0xFFFFFDF9, 0xFF25201B)
    val CardBorder get() = pick(0xFFEFE2D0, 0xFF332C25)
    val Line get() = pick(0xFFEADBC6, 0xFF3A322A)
    val Ink get() = pick(0xFF221D18, 0xFFF3EBE1)
    val InkSoft get() = pick(0xFF6E6358, 0xFFC2B6A8)
    val InkMuted get() = pick(0xFFA6998A, 0xFF8A7F73)
    /** Títulos de sección (SectionLabel). */
    val Accent get() = pick(0xFFC8243B, 0xFFEF5D70)
    val Rain get() = pick(0xFF2F80ED, 0xFF5C9DF2)
    val Warm get() = pick(0xFFF5A623, 0xFFF5AE3A)
    val Night get() = pick(0xFF5B6B9A, 0xFF97A6D6)
    val Highlight get() = pick(0xFFF6ECDD, 0xFF2E2822)
    val Offline get() = pick(0xFFB5A99A, 0xFF6F665C)
    val Online get() = pick(0xFF3BA55C, 0xFF52C276)
}

val Geist = FontFamily(
    Font(R.font.geist_thin, FontWeight.Thin),
    Font(R.font.geist_extralight, FontWeight.ExtraLight),
    Font(R.font.geist_light, FontWeight.Light),
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold)
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_bold, FontWeight.Bold)
)

/** Estilos de texto; se crean al leerlos, para que lleven el color del modo (claro u oscuro) de ese momento. */
object Type {
    /** Etiquetas de sección en mayúsculas: SIGUIENTE, PRÓXIMAS HORAS… */
    val Label get() = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = 2.5.sp, color = Palette.Accent)
    val Mono get() = TextStyle(fontFamily = GeistMono, fontSize = 24.sp, color = Palette.InkSoft)
    val Clock get() = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Light, fontSize = 196.sp, lineHeight = 196.sp, letterSpacing = (-8).sp, color = Palette.Ink)
    val Huge get() = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 140.sp, lineHeight = 140.sp, letterSpacing = (-5).sp, color = Palette.Ink)
    val Title get() = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 46.sp, lineHeight = 54.sp, color = Palette.Ink)
    val Large get() = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 42.sp, color = Palette.Ink)
    val Body get() = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 29.sp, lineHeight = 37.sp, color = Palette.Ink)
    val Small get() = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp, color = Palette.InkSoft)
    val Tiny get() = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp, color = Palette.InkSoft)
}
