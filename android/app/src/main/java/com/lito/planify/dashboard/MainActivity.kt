package com.lito.planify.dashboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lito.planify.dashboard.admin.Kiosk
import com.lito.planify.dashboard.admin.PowerMenuService
import com.lito.planify.dashboard.admin.ReturnButton
import com.lito.planify.dashboard.power.ScreenSchedule
import com.lito.planify.dashboard.ui.ChoreCallbacks
import com.lito.planify.dashboard.ui.DashboardActions
import com.lito.planify.dashboard.ui.DashboardApp
import com.lito.planify.dashboard.ui.HomeCallbacks
import com.lito.planify.dashboard.ui.ProvideIconClock
import com.lito.planify.dashboard.ui.SettingsCallbacks
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La alarma de encendido trae la actividad al frente con la pantalla apagada.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        val actions = DashboardActions(
            // La luna: con el reloj nocturno activo pasa a él; si no, apaga la pantalla
            sleep = {
                if (!viewModel.nightNow() && !Kiosk.sleep(this)) {
                    Toast.makeText(this, "Apagar requiere el modo quiosco (Device Owner)", Toast.LENGTH_LONG).show()
                }
            },
            openWifi = { openSystemSettings(Settings.ACTION_WIFI_SETTINGS) },
            openSettings = { openSystemSettings(Settings.ACTION_SETTINGS) },
            powerMenu = { if (!PowerMenuService.open()) Toast.makeText(this, "Falta activar el servicio de apagado (scripts/install.sh)", Toast.LENGTH_LONG).show() }
        )
        val settings = SettingsCallbacks(
            onSearch = viewModel::searchLocation,
            onSelectLocation = viewModel::selectLocation,
            onSchedule = viewModel::setSchedule,
            onBrightness = viewModel::setBrightness,
            onLogout = viewModel::logout
        )
        val chores = ChoreCallbacks(
            onDone = viewModel::choreDone,
            onUndo = viewModel::choreUndo,
            onClaim = viewModel::rewardClaim,
            onSave = viewModel::starSave,
            onPenalize = viewModel::penalize,
            onDecide = viewModel::rewardDecide,
            onClearError = viewModel::clearChoreError,
            onShoppingRemove = viewModel::shoppingRemove,
            onShoppingAdd = viewModel::shoppingAdd
        )
        val home = HomeCallbacks(
            onSetpoint = viewModel::homeSetpoint,
            onPreset = viewModel::homePreset,
            onAwning = viewModel::homeAwning,
            onMusic = viewModel::homeMusic,
            onHeaterTemp = viewModel::heaterSetpoint,
            onHeaterBoost = viewModel::heaterBoost
        )

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            // Se diseña en píxeles nativos del panel (1920×1200): 1 dp = 1 sp = 1 px.
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                ProvideIconClock {
                    DashboardApp(state, actions, viewModel::login, viewModel::loadWeek, viewModel::syncAll, settings, viewModel::peek, chores, home)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.run() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // El reloj nocturno va con el brillo al mínimo; el resto, con el elegido en Ajustes (o el del sistema).
                // Siempre poco a poco y sin pasar de MAX_BRIGHTNESS: los saltos bruscos de brillo (07:00, 23:00, al
                // arrancar) coincidían con los apagados del marco (21-10-2026).
                viewModel.state.map { if (it.night) NIGHT_BRIGHTNESS else it.brightness }.distinctUntilChanged().collectLatest { value ->
                    val target = (value ?: systemBrightness()).coerceIn(NIGHT_BRIGHTNESS, MAX_BRIGHTNESS)
                    val from = window.attributes.screenBrightness.takeIf { it >= 0f } ?: systemBrightness().coerceAtMost(MAX_BRIGHTNESS)
                    for (step in 1..RAMP_STEPS) {
                        val level = from + (target - from) * step / RAMP_STEPS
                        window.attributes = window.attributes.apply { screenBrightness = level }
                        delay(RAMP_MILLIS / RAMP_STEPS)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReturnButton.hide(this)
        Kiosk.enter(this)
        hideSystemBars()
        // Si se ha encendido con el botón fuera de horario, programa el apagado de cortesía.
        ScreenSchedule.reschedule(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Ajustes de Android, con el botón flotante para volver (el marco no tiene barra de navegación). */
    private fun openSystemSettings(action: String) {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ReturnButton.show(this)
    }

    private companion object {
        /**
         * Brillo del reloj nocturno: tenue pero visible (10 de 255). Con 1/255 este panel queda casi negro, parecía
         * apagado y, al pulsar el botón para «encenderlo», una pulsación larga lo apagaba de verdad (20-10-2026).
         */
        const val NIGHT_BRIGHTNESS = 10f / 255f

        /** Brillo máximo (180 de 255, ~70 %): menos consumo de pico y de calor; apenas se nota de día. */
        const val MAX_BRIGHTNESS = 180f / 255f

        /** Los cambios de brillo se hacen en [RAMP_STEPS] pasos repartidos en [RAMP_MILLIS]. */
        const val RAMP_STEPS = 30
        const val RAMP_MILLIS = 3_000L
    }

    /** El brillo del sistema (0–1), el que se usa con «Brillo automático» en Ajustes. */
    private fun systemBrightness(): Float =
        runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }
            .getOrDefault(MAX_BRIGHTNESS)

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
