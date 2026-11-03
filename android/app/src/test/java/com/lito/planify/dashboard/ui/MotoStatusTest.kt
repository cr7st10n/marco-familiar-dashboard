package com.lito.planify.dashboard.ui

import com.google.gson.Gson
import com.lito.planify.dashboard.data.CarStatus
import com.lito.planify.dashboard.data.Motorbike
import com.lito.planify.dashboard.data.ShoppingList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotoStatusTest {
    @Test
    fun readsTheMotoFromCompraJson() {
        // Tal como lo exporta compra_export.py con la Silence S01+
        val json = """{"moto": {"modelo": "Silence S01+", "bateria": 30.0, "autonomia_km": 39.0, "cargando": false,
            "km": 11965.0, "alarma": false, "estado": "IDLE", "informado": "2026-11-03T17:34:51.286Z"}}"""
        val moto = Gson().fromJson(json, ShoppingList::class.java).moto!!
        assertEquals(30.0, moto.bateria!!, 0.0)
        assertEquals("🅿️" to "Aparcada", moto.statusLabel())
        assertNull(Gson().fromJson("{}", ShoppingList::class.java).moto)
    }

    @Test
    fun statusLabels() {
        assertEquals("⚡" to "Cargando", Motorbike(estado = "IDLE", cargando = true).statusLabel())
        assertEquals("⚡" to "Cargando", Motorbike(estado = "CHARGING").statusLabel())
        assertEquals("🛵" to "En marcha", Motorbike(estado = "MOVING").statusLabel())
        assertEquals("🛵" to "Desconocido", Motorbike(estado = "DESCONOCIDO").statusLabel())
        assertNull(Motorbike().statusLabel())
    }

    @Test
    fun asleepCarKeepsItsLastData() {
        val json = """{"coche": {"modelo": "Tesla", "bateria": 51.0, "autonomia_km": 247.8, "dormido": true,
            "dormido_desde": "2026-11-03T17:46:29+00:00"}}"""
        val car = Gson().fromJson(json, ShoppingList::class.java).coche!!
        assertEquals(51.0, car.bateria!!, 0.0)
        assertTrue(car.asleepText()!!.startsWith("💤 Dormido desde las "))
        assertEquals("💤 Dormido", CarStatus(dormido = true).asleepText())
        assertNull(CarStatus(bateria = 51.0).asleepText())
    }
}
