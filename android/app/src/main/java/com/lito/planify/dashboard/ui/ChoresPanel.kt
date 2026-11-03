package com.lito.planify.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lito.planify.dashboard.data.Chore
import com.lito.planify.dashboard.data.Chores
import com.lito.planify.dashboard.data.Kid
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type

/**
 * Marcar una tarea como hecha por un niño (tarea, niño), deshacer una hecha de hoy (hecha), pedir una recompensa
 * (recompensa, niño) y, con el PIN de padres, aprobar o rechazar un canje (canje, pin, aprobar). También quitar un
 * producto de la compra (producto), que va por el mismo servicio del servidor.
 */
class ChoreCallbacks(
    val onDone: (String, String) -> Unit,
    val onUndo: (String) -> Unit,
    val onClaim: (String, String) -> Unit,
    /** Meter una estrella en una hucha: niño y recompensa. */
    val onSave: (String, String) -> Unit,
    /** Penalizar con el PIN de padres: penalización, niño y PIN. */
    val onPenalize: (String, String, String) -> Unit,
    val onDecide: (String, String, Boolean) -> Unit,
    val onClearError: () -> Unit,
    val onShoppingRemove: (String) -> Unit,
    val onShoppingAdd: (String) -> Unit = {}
)

/**
 * Tareas de casa de los niños (TaskMate): arriba, cada niño con las estrellas de la semana; debajo, una fila por tarea
 * de hoy. Si está por hacer, un botón con el nombre de cada niño que puede hacerla: el que la hace toca el suyo. Hecha,
 * queda quién y a qué hora, con «Deshacer» por si se tocó sin querer. [busy]: lo que se está enviando.
 */
@Composable
fun ChoresPanel(
    chores: Chores, busy: String?, onDone: (String, String) -> Unit, onUndo: (String) -> Unit, modifier: Modifier = Modifier,
    onSaveStars: (String) -> Unit = {}
) {
    val kids = chores.ninos.orEmpty()
    val tasks = chores.tareas.orEmpty()
    val pending = chores.pending()
    Panel(modifier, compact = true) {
        CardHeader("Tareas de los niños", Icons.Outlined.Pets, Palette.Warm, count = pending, compact = true)
        if (kids.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                kids.forEach { kid -> KidStars(kid, canSave = kid.libres > 0 && chores.rewardsWithRoom(kid).isNotEmpty()) { onSaveStars(kid.id) } }
            }
        }
        if (tasks.isEmpty()) {
            // Sin weight: el panel solo ocupa lo que necesita y deja el resto a las tareas de casa
            EmptyState(Icons.Outlined.TaskAlt, Palette.Warm, "Sin tareas hoy", "Se añaden en el panel de TaskMate de Home Assistant", Modifier.fillMaxWidth().padding(vertical = 24.dp))
            return@Panel
        }
        if (pending == 0) {
            Text(
                "🎉 ¡Todo hecho por hoy!",
                style = Type.Body.copy(color = Palette.Online, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        Column(Modifier.padding(top = 4.dp).fadingScroll(rememberScrollState())) {
            tasks.forEach { ChoreRow(it, kids, busy, onDone, onUndo) }
        }
    }
}

/**
 * Un niño con sus estrellas de la semana; si tiene sueltas, «Guardar N ⭐» para elegir hucha, y si debe (saldo negativo
 * por una penalización), «Debe N ⭐».
 */
@Composable
private fun KidStars(kid: Kid, canSave: Boolean, onSave: () -> Unit) {
    val color = parseColor(kid.color, Palette.Warm)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
            .clickable(enabled = canSave, onClick = onSave)
            .padding(start = 6.dp, end = if (canSave || kid.estrellas < 0) 6.dp else 18.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Initial(kid.nombre, color, 40)
        Text(kid.nombre, style = Type.Small.copy(color = Palette.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 10.dp))
        Text("⭐ ${kid.semana}", style = Type.Small.copy(color = Palette.InkSoft), modifier = Modifier.padding(start = 10.dp))
        if (kid.estrellas < 0) {
            Text(
                "Debe ${-kid.estrellas} ⭐",
                style = Type.Tiny.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 12.dp).background(Palette.Accent, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        if (canSave) {
            Text(
                "Guardar ${kid.libres} ⭐",
                style = Type.Tiny.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 12.dp).background(color, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun Initial(name: String, color: Color, size: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp).background(color, CircleShape)) {
        Text(name.take(1).uppercase(Spanish), style = Type.Tiny.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.45f).sp))
    }
}

@Composable
private fun ChoreRow(chore: Chore, kids: List<Kid>, busy: String?, onDone: (String, String) -> Unit, onUndo: (String) -> Unit) {
    val byId = kids.associateBy { it.id }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(chore.emoji ?: "✅", style = Type.Title.copy(fontSize = 38.sp), modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(chore.nombre, style = Type.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("+${chore.estrellas} ⭐", style = Type.Tiny.copy(color = Palette.InkMuted))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chore.hechas.orEmpty().forEach { done ->
                val color = parseColor(byId[done.nino]?.color, Palette.Online)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (busy == done.id) 0.4f else 1f)) {
                    Text(
                        "✓ ${done.nombre ?: byId[done.nino]?.nombre.orEmpty()}${done.hora?.let { " · $it" }.orEmpty()}",
                        style = Type.Small.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.background(color, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                    Text(
                        "Deshacer",
                        style = Type.Tiny.copy(color = Palette.InkMuted),
                        modifier = Modifier.clickable(enabled = busy == null) { onUndo(done.id) }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp)
                    )
                }
            }
            // Un botón por cada niño que aún puede hacerla
            val who = chore.para.orEmpty().mapNotNull { byId[it] }
            if (who.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.alpha(if (busy == chore.id) 0.4f else 1f)) {
                    who.forEach { kid ->
                        val color = parseColor(kid.color, Palette.Warm)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(color.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
                                .clickable(enabled = busy == null) { onDone(chore.id, kid.id) }
                                .padding(start = 6.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Initial(kid.nombre, color, 40)
                            Text(kid.nombre, style = Type.Small.copy(color = Palette.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
    }
}
