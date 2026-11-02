package com.lito.planify.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lito.planify.dashboard.data.Chores
import com.lito.planify.dashboard.data.Kid
import com.lito.planify.dashboard.data.Penalty
import com.lito.planify.dashboard.data.Reward
import com.lito.planify.dashboard.data.RewardClaim
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type

private val RewardTint = Color(0xFF8E5BC8)

/** Las recompensas que [kid] puede elegir y en cuya hucha aún cabe alguna estrella. */
fun Chores.rewardsWithRoom(kid: Kid): List<Reward> =
    recompensas.orEmpty().filter { it.disponible && kid.id in it.para.orEmpty() && kid.saved(it.id) < it.costFor(kid.id) }

/**
 * Recompensas de TaskMate (cada una con una hucha por niño): primero los canjes pedidos, con «Aprobar» y «Rechazar» (piden el PIN de padres, [onAsk]);
 * debajo, cada recompensa con lo que le falta a cada niño o, si ya le llega, el botón «Pedir».
 */
@Composable
fun RewardsPanel(chores: Chores, busy: String?, onClaim: (String, String) -> Unit, onAsk: (RewardClaim, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val kids = chores.ninos.orEmpty().associateBy { it.id }
    val claims = chores.pedidas.orEmpty()
    val rewards = chores.recompensas.orEmpty()
    if (rewards.isEmpty() && claims.isEmpty()) return
    Panel(modifier, compact = true) {
        CardHeader("Recompensas", Icons.Outlined.CardGiftcard, RewardTint, count = claims.size, compact = true)
        Column(Modifier.padding(top = 4.dp)) {
            claims.forEach { ClaimRow(it, kids[it.nino], busy, onAsk) }
            rewards.forEach { RewardRow(it, kids, claims, busy, onClaim) }
        }
    }
}

@Composable
private fun ClaimRow(claim: RewardClaim, kid: Kid?, busy: String?, onAsk: (RewardClaim, Boolean) -> Unit) {
    val color = parseColor(kid?.color, RewardTint)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(color.copy(alpha = 0.10f), RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 8.dp).alpha(if (busy == claim.id) 0.4f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(claim.emoji ?: "🎁", style = Type.Large, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text("${claim.nombre ?: kid?.nombre.orEmpty()} pide", style = Type.Tiny.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold))
            Text(claim.titulo.orEmpty(), style = Type.Small.copy(color = Palette.Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SmallButton("Aprobar", Palette.Online, filled = true, enabled = busy == null) { onAsk(claim, true) }
        SmallButton("Rechazar", Palette.InkMuted, filled = false, enabled = busy == null, modifier = Modifier.padding(start = 10.dp)) { onAsk(claim, false) }
    }
}

@Composable
private fun RewardRow(reward: Reward, kids: Map<String, Kid>, claims: List<RewardClaim>, busy: String?, onClaim: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).alpha(if (busy == reward.id) 0.4f else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(reward.emoji ?: "🎁", style = Type.Large, modifier = Modifier.width(56.dp))
            Text(reward.nombre, style = Type.Small.copy(color = Palette.Ink), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${reward.coste} ⭐", style = Type.Small.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold))
        }
        if (!reward.disponible) {
            Text("Agotada por ahora", style = Type.Tiny.copy(color = Palette.InkMuted), modifier = Modifier.padding(start = 56.dp, top = 4.dp))
            return@Column
        }
        Row(Modifier.padding(start = 56.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            reward.para.orEmpty().mapNotNull { kids[it] }.forEach { kid ->
                KidProgress(kid, reward.id, reward.costFor(kid.id), asked = claims.any { it.recompensa == reward.id && it.nino == kid.id }, enabled = busy == null) {
                    onClaim(reward.id, kid.id)
                }
            }
        }
    }
}

/** La hucha de un niño para una recompensa: barra y «faltan N», o «Pedir» si ya está llena (o «Pedida»). */
@Composable
private fun KidProgress(kid: Kid, reward: String, cost: Int, asked: Boolean, enabled: Boolean, onClaim: () -> Unit) {
    val color = parseColor(kid.color, RewardTint)
    val have = kid.saved(reward).coerceAtLeast(0)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(24.dp)).padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(color, CircleShape)) {
            Text(kid.nombre.take(1).uppercase(Spanish), style = Type.Tiny.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp))
        }
        when {
            asked -> Text("Pedida ⏳", style = Type.Tiny.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 10.dp))
            have >= cost -> SmallButton("Pedir", color, filled = true, enabled = enabled, modifier = Modifier.padding(start = 10.dp), onClick = onClaim)
            else -> {
                Box(Modifier.padding(start = 10.dp).width(90.dp).height(10.dp).background(Palette.Line, RoundedCornerShape(5.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(if (cost > 0) have.toFloat() / cost else 1f).background(color, RoundedCornerShape(5.dp)))
                }
                Text("faltan ${cost - have}", style = Type.Tiny.copy(color = Palette.InkSoft), modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, color: Color, filled: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        style = Type.Tiny.copy(color = if (filled) Color.White else color, fontWeight = FontWeight.SemiBold),
        modifier = modifier
            .background(if (filled) color else color.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

/**
 * Teclado del PIN de padres para [title] (aprobar o rechazar un canje, poner una penalización...). Al cuarto número se
 * envía solo; si el servidor dice que no ([error]), se borra y se puede repetir. Lo cierra quien lo muestra al
 * hacerse, o «Cancelar» o tocar fuera.
 */
@Composable
fun PinOverlay(title: String, tint: Color, key: String, busy: Boolean, error: String?, onPin: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember(key) { mutableStateOf("") }
    LaunchedEffect(error) { if (error != null) pin = "" }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .width(620.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .background(Palette.Card, RoundedCornerShape(36.dp))
                .padding(horizontal = 48.dp, vertical = 36.dp)
        ) {
            Text(
                title,
                style = Type.Body.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center
            )
            Text("PIN de padres", style = Type.Small.copy(color = Palette.InkMuted))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                repeat(PIN_LENGTH) { i -> Box(Modifier.size(22.dp).background(if (i < pin.length) tint else Palette.Line, CircleShape)) }
            }
            Text(error ?: " ", style = Type.Small.copy(color = Palette.Accent))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { key ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = 132.dp, height = 84.dp)
                                .background(if (key.isEmpty()) Color.Transparent else Palette.Highlight, RoundedCornerShape(22.dp))
                                .clickable(enabled = key.isNotEmpty() && !busy) {
                                    pin = if (key == "⌫") pin.dropLast(1) else (pin + key).take(PIN_LENGTH)
                                    if (pin.length == PIN_LENGTH) onPin(pin)
                                }
                        ) { Text(key, style = Type.Large.copy(fontWeight = FontWeight.Medium)) }
                    }
                }
            }
            Text(
                "Cancelar",
                style = Type.Small.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 24.dp, vertical = 10.dp)
            )
        }
    }
}

private const val PIN_LENGTH = 4

/**
 * «¿Dónde guardas tu estrella?»: las recompensas de [kid] con su hucha. Cada toque en una con sitio guarda una estrella
 * ([onSave] con la recompensa). Quien lo muestra lo cierra cuando ya no le quedan libres; también «Luego» o tocar fuera
 * (las estrellas se quedan por guardar).
 */
@Composable
fun StarPicker(kid: Kid, rewards: List<Reward>, busy: Boolean, error: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val color = parseColor(kid.color, RewardTint)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .width(820.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .background(Palette.Card, RoundedCornerShape(36.dp))
                .padding(horizontal = 48.dp, vertical = 36.dp)
        ) {
            Text(
                "${kid.nombre}, ¿dónde guardas ${if (kid.libres == 1) "tu estrella" else "tus ${kid.libres} estrellas"}? ⭐",
                style = Type.Body.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center
            )
            Text(
                if (kid.libres == 1) "Toca la recompensa para la que quieres ahorrar" else "Cada toque guarda una estrella en esa hucha",
                style = Type.Small.copy(color = Palette.InkMuted),
                textAlign = TextAlign.Center
            )
            if (error != null) Text(error, style = Type.Small.copy(color = Palette.Accent))
            rewards.forEach { reward ->
                val cost = reward.costFor(kid.id)
                val have = kid.saved(reward.id).coerceAtMost(cost)
                val full = have >= cost
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (full || busy) 0.5f else 1f)
                        .background(color.copy(alpha = 0.10f), RoundedCornerShape(26.dp))
                        .clickable(enabled = !full && !busy) { onSave(reward.id) }
                        .padding(horizontal = 22.dp, vertical = 16.dp)
                ) {
                    Text(reward.emoji ?: "🎁", style = Type.Large, modifier = Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(reward.nombre, style = Type.Body.copy(color = Palette.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.8f).height(12.dp).background(Palette.Line, RoundedCornerShape(6.dp))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(if (cost > 0) have.toFloat() / cost else 1f).background(color, RoundedCornerShape(6.dp)))
                        }
                    }
                    Text(
                        if (full) "¡Llena!" else "$have / $cost ⭐",
                        style = Type.Small.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
            Text(
                "Luego",
                style = Type.Small.copy(color = Palette.InkSoft, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 24.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * Penalizaciones de TaskMate: cada una con lo que quita y un botón por niño. Tocarlo pide el PIN de padres ([onAsk]),
 * para que solo las pongan ellos.
 */
@Composable
fun PenaltiesPanel(chores: Chores, busy: String?, onAsk: (Penalty, Kid) -> Unit, modifier: Modifier = Modifier) {
    val penalties = chores.penalizaciones.orEmpty()
    if (penalties.isEmpty()) return
    val kids = chores.ninos.orEmpty().associateBy { it.id }
    Panel(modifier, compact = true) {
        CardHeader("Penalizaciones", Icons.Outlined.ReportProblem, Palette.Accent, compact = true)
        Column(Modifier.padding(top = 4.dp)) {
            penalties.forEach { penalty ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).alpha(if (busy == penalty.id) 0.4f else 1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(penalty.emoji ?: "⚠️", style = Type.Large, modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(penalty.nombre, style = Type.Small.copy(color = Palette.Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("−${penalty.estrellas} ⭐ · con el PIN de padres", style = Type.Tiny.copy(color = Palette.InkMuted))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        penalty.para.orEmpty().mapNotNull { kids[it] }.forEach { kid ->
                            val color = parseColor(kid.color, Palette.Accent)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(color.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
                                    .clickable(enabled = busy == null) { onAsk(penalty, kid) }
                                    .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(color, CircleShape)) {
                                    Text(kid.nombre.take(1).uppercase(Spanish), style = Type.Tiny.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp))
                                }
                                Text(kid.nombre, style = Type.Tiny.copy(color = Palette.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
