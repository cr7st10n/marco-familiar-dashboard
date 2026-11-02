package com.lito.planify.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import com.lito.planify.dashboard.data.Frequent
import kotlinx.coroutines.delay
import androidx.compose.runtime.key
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lito.planify.dashboard.data.RewardClaim
import com.lito.planify.dashboard.data.Kid
import com.lito.planify.dashboard.data.Penalty
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lito.planify.dashboard.data.FamilyList
import com.lito.planify.dashboard.data.ShoppingItem
import com.lito.planify.dashboard.data.ShoppingList
import java.time.LocalDate
import java.time.format.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lito.planify.dashboard.UiState
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type

/**
 * Todas las listas del dashboard, una columna por lista (con desplazamiento lateral si no caben). Con calendarios iCal,
 * la lista de la compra de Bring! ([UiState.shopping]) en lugar de las tareas de Planify.
 */
@Composable
fun TasksScreen(state: UiState, chores: ChoreCallbacks) {
    state.shopping?.let { FamilyListsScreen(it.all(), state.now.toLocalDate(), state, chores); return }
    val lists = state.content?.tasks.orEmpty().groupBy { it.task_list_id }.values.toList()
    ScreenPadding {
        if (lists.isEmpty()) {
            EmptyState(Icons.Outlined.TaskAlt, Palette.Online, "Todo hecho", "No quedan tareas pendientes en las listas del dashboard", Modifier.fillMaxSize(), large = true)
            return@ScreenPadding
        }
        val width = if (lists.size <= 4) null else 480
        Row(
            Modifier.fillMaxSize().then(if (width != null) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            lists.forEach { tasks ->
                val first = tasks.first()
                Panel((if (width != null) Modifier.width(width.dp) else Modifier.weight(1f)).fillMaxHeight()) {
                    CardHeader(first.task_list_name.orEmpty(), Icons.Outlined.Checklist, parseColor(first.task_list_color), count = tasks.size)
                    Column(Modifier.padding(top = 8.dp).fadingScroll(rememberScrollState())) {
                        tasks.forEach { TaskRow(it) }
                    }
                }
            }
        }
    }
}

/**
 * Listas de la familia, una columna por lista aunque esté vacía: la compra (Bring!) y las tareas (Todoist «Casa»).
 * Las tareas con fecha llevan una píldora («hoy», «mañana», «15 nov»), roja si ya ha vencido. Con TaskMate, en dos
 * bloques: a la izquierda las tareas de los niños (pocas, que ellos mismos marcan) y debajo las demás listas; a la
 * derecha, la compra a toda altura.
 */
@Composable
private fun FamilyListsScreen(lists: List<FamilyList>, today: LocalDate, state: UiState, chores: ChoreCallbacks) {
    val kids = state.chores
    if (kids != null) {
        val (shopping, others) = lists.partition { it.nombre == ShoppingList.SHOPPING_NAME }
        // Canje que se va a aprobar (true) o rechazar con el PIN de padres
        var asking by remember { mutableStateOf<Pair<RewardClaim, Boolean>?>(null) }
        // Penalización que un padre va a poner (penalización, niño), con el PIN; [punishSent]: ya se mandó el PIN
        var punishing by remember { mutableStateOf<Pair<Penalty, Kid>?>(null) }
        var punishSent by remember { mutableStateOf(false) }
        LaunchedEffect(state.choreBusy) {
            // Hecha (sin error): se cierra sola. Con error (PIN malo), se queda para repetir
            if (state.choreBusy == null && punishSent) {
                punishSent = false
                if (state.choreError == null) punishing = null
            }
        }
        // Niño que elige en qué hucha guarda sus estrellas
        var saving by remember { mutableStateOf<String?>(null) }
        // Tarea recién marcada (tarea, niño): al llegar el estado con ella hecha, se le pregunta dónde guarda la estrella
        var justDone by remember { mutableStateOf<Pair<String, String>?>(null) }
        LaunchedEffect(kids, state.choreBusy) {
            val (chore, kid) = justDone ?: return@LaunchedEffect
            if (state.choreBusy != null) return@LaunchedEffect
            justDone = null
            if (kids.tareas.orEmpty().firstOrNull { it.id == chore }?.hechas.orEmpty().none { it.nino == kid }) return@LaunchedEffect
            val who = kids.ninos.orEmpty().firstOrNull { it.id == kid } ?: return@LaunchedEffect
            if (who.libres > 0 && kids.rewardsWithRoom(who).isNotEmpty()) saving = kid
        }
        Box(Modifier.fillMaxSize()) {
            ScreenPadding {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    // Tarjetas compactas apiladas: tareas y recompensas de los niños ocupan lo justo; lo que sobra, las de casa
                    Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        ChoresPanel(
                            kids, state.choreBusy, { chore, kid -> justDone = chore to kid; chores.onDone(chore, kid) }, chores.onUndo, Modifier.fillMaxWidth(),
                            onSaveStars = { kid -> chores.onClearError(); saving = kid }
                        )
                        RewardsPanel(kids, state.choreBusy, chores.onClaim, { claim, approve -> chores.onClearError(); asking = claim to approve }, Modifier.fillMaxWidth())
                        others.forEach { FamilyListPanel(it, today, Modifier.fillMaxWidth().weight(1f), compact = true) }
                    }
                    // A la derecha, la compra con lo que sobra y, debajo, las penalizaciones (solo con el PIN de padres)
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        shopping.forEach { FamilyListPanel(it, today, Modifier.fillMaxWidth().weight(1f), chores.onShoppingRemove, usuals = state.shopping?.frecuentes, onAdd = chores.onShoppingAdd) }
                        PenaltiesPanel(kids, state.choreBusy, { penalty, kid -> chores.onClearError(); punishing = penalty to kid }, Modifier.fillMaxWidth())
                    }
                }
            }
            asking?.let { (claim, approve) ->
                // Hecho (ya no está pendiente): se cierra solo
                LaunchedEffect(kids.pedidas) { if (kids.pedidas.orEmpty().none { it.id == claim.id }) asking = null }
                PinOverlay(
                    "${if (approve) "Aprobar" else "Rechazar"} «${claim.titulo.orEmpty()}» para ${claim.nombre.orEmpty()}",
                    if (approve) Palette.Online else Palette.Accent, claim.id,
                    busy = state.choreBusy != null, error = state.choreError, onPin = { chores.onDecide(claim.id, it, approve) }
                ) { asking = null }
            }
            punishing?.let { (penalty, kid) ->
                PinOverlay(
                    "${penalty.emoji ?: "⚠️"} «${penalty.nombre}» a ${kid.nombre}: −${penalty.estrellas} ⭐",
                    Palette.Accent, penalty.id + kid.id,
                    busy = state.choreBusy != null, error = state.choreError,
                    onPin = { punishSent = true; chores.onPenalize(penalty.id, kid.id, it) }
                ) { punishing = null }
            }
            saving?.let { kidId ->
                val kid = kids.ninos.orEmpty().firstOrNull { it.id == kidId }
                val rewards = kids.recompensas.orEmpty().filter { it.disponible && kidId in it.para.orEmpty() }
                // Sin estrellas por guardar o sin sitio en ninguna hucha: se cierra solo
                if (kid == null || kid.libres <= 0 || kids.rewardsWithRoom(kid).isEmpty()) {
                    LaunchedEffect(Unit) { saving = null }
                } else {
                    StarPicker(kid, rewards, busy = state.choreBusy != null, error = state.choreError, onSave = { chores.onSave(kidId, it) }) { saving = null }
                }
            }
        }
        return
    }
    ScreenPadding {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            lists.forEach { FamilyListPanel(it, today, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@Composable
private fun FamilyListPanel(
    list: FamilyList, today: LocalDate, modifier: Modifier, onRemove: ((String) -> Unit)? = null, compact: Boolean = false,
    usuals: List<Frequent>? = null, onAdd: (String) -> Unit = {}
) {
    val color = parseColor(list.color, Palette.Online)
    val items = list.items.orEmpty()
    val shopping = list.nombre == ShoppingList.SHOPPING_NAME
    Panel(modifier, compact = compact) {
        CardHeader(list.nombre, if (shopping) Icons.Outlined.ShoppingCart else Icons.Outlined.Checklist, color, count = items.size, compact = compact)
        if (items.isEmpty()) {
            EmptyState(
                Icons.Outlined.TaskAlt, color, if (shopping) "Nada que comprar" else "Nada pendiente",
                if (shopping) "Añade en Bring! o «Alexa, añade… a la lista de la compra»" else "Añade en Todoist o «Alexa, añade… a mis tareas»",
                Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            Column(Modifier.weight(1f).padding(top = 8.dp).fadingScroll(rememberScrollState())) {
                items.forEach { item -> key(item.producto) { FamilyItemRow(item, today, onRemove) } }
            }
        }
        if (!usuals.isNullOrEmpty()) UsualsRow(usuals, color, onAdd)
    }
}

/**
 * Habituales de la compra al pie de la lista: los 3 productos más pedidos como pastillas. Tocar uno lo añade; si ya
 * está en la lista sale oscuro con «✓ En la lista».
 */
@Composable
private fun UsualsRow(usuals: List<Frequent>, color: Color, onAdd: (String) -> Unit) {
    Hairline(Modifier.padding(top = 12.dp, bottom = 14.dp))
    Text("HABITUALES · TOCA PARA AÑADIR", style = Type.Tiny.copy(color = Palette.InkMuted, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        usuals.forEach { u ->
            Column(
                Modifier
                    .weight(1f)
                    .background(if (u.en_lista) Palette.Ink else color.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                    .clickable(enabled = !u.en_lista) { onAdd(u.producto) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(u.producto, style = Type.Small.copy(color = if (u.en_lista) Color.White else Palette.Ink, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (u.en_lista) "✓ En la lista" else "+ Añadir",
                    style = Type.Tiny.copy(color = if (u.en_lista) Color.White.copy(alpha = 0.8f) else color, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Un elemento de una lista. Con [onRemove] (la compra), tocarlo enseña «Quitar» 8 segundos y tocar «Quitar» lo
 * quita: dos toques, para no borrar nada sin querer.
 */
@Composable
private fun FamilyItemRow(item: ShoppingItem, today: LocalDate, onRemove: ((String) -> Unit)? = null) {
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) {
            delay(8_000)
            asking = false
        }
    }
    Row(
        Modifier.fillMaxWidth().then(if (onRemove != null) Modifier.clickable { asking = !asking } else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.producto, style = Type.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.detalle?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        item.dueDate?.let { due ->
            val late = due < today
            Text(
                when (due) {
                    today -> "hoy"
                    today.plusDays(1) -> "mañana"
                    else -> "${due.dayOfMonth} ${due.month.getDisplayName(TextStyle.SHORT, Spanish).trimEnd('.')}"
                },
                style = Type.Tiny.copy(color = if (late) Color.White else Palette.InkSoft, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 12.dp).background(if (late) Palette.Accent else Palette.Highlight, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 3.dp)
            )
        }
        if (asking && onRemove != null) {
            Text(
                "Quitar",
                style = Type.Small.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .background(Palette.Accent, RoundedCornerShape(20.dp))
                    .clickable { asking = false; onRemove(item.producto) }
                    .padding(horizontal = 22.dp, vertical = 6.dp)
            )
        }
    }
}
