package com.lito.planify.dashboard.ui

import com.google.gson.Gson
import com.lito.planify.dashboard.data.ShoppingList
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenNewsTest {
    private val now = LocalDateTime.of(2026, 11, 11, 18, 0)
    private fun iso(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
    private fun cocina(json: String) = Gson().fromJson("""{"cocina": $json}""", ShoppingList::class.java).cocina

    @Test
    fun nothingToTellKeepsTheWholeWeek() {
        // Tal como lo exporta compra_export.py antes de conectar los aparatos
        val list = cocina("""[{"id": "secadora", "tipo": "secadora", "nombre": "Secadora", "disponible": false, "en_marcha": false},
            {"id": "frigorifico", "tipo": "frigorifico", "nombre": "Frigorífico", "disponible": true, "en_marcha": false, "temp_nevera": 4.0}]""")
        assertTrue(kitchenNews(list, now).isEmpty())
        assertTrue(kitchenNews(null, now).isEmpty())
    }

    @Test
    fun runningShowsTheTimeLeftFromTheEndTime() {
        val list = cocina("""[{"id": "secadora", "tipo": "secadora", "nombre": "Secadora", "disponible": true, "en_marcha": true,
            "restante_min": 50, "fin": "${iso(now.plusMinutes(35))}"}]""")
        assertEquals(listOf("quedan 35 min"), kitchenNews(list, now).map { it.second })
        assertEquals(listOf("quedan 1 h 5 min"), kitchenNews(list, now.minusMinutes(30)).map { it.second })
    }

    @Test
    fun finishedShowsForThreeHoursAndWarningsAlways() {
        val list = cocina("""[{"id": "lavavajillas", "tipo": "lavavajillas", "nombre": "Lavavajillas", "disponible": true, "en_marcha": false,
            "terminado_desde": "${iso(now.minusMinutes(20))}", "avisos": ["🧂 Falta sal"]},
            {"id": "frigorifico", "tipo": "frigorifico", "nombre": "Frigorífico", "disponible": true, "en_marcha": false,
            "avisos": ["🚪 Puerta del congelador abierta"]}]""")
        assertEquals(listOf("✅ Terminado a las 17:40", "🧂 Falta sal", "🚪 Puerta del congelador abierta"), kitchenNews(list, now).map { it.second })
        // A las 3 horas el «terminado» desaparece aunque el servidor no lo haya quitado; los avisos siguen
        assertEquals(listOf("🧂 Falta sal", "🚪 Puerta del congelador abierta"), kitchenNews(list, now.plusHours(3)).map { it.second })
    }
}
