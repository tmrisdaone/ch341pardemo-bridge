package cn.wch.ch341pardemo.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.wch.ch341pardemo.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ch341_settings")

/**
 * Persists user settings (theme, bridge enabled, default baud rate) to
 * a per-app DataStore so they survive process restarts.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BRIDGE_ENABLED = booleanPreferencesKey("bridge_enabled")
        val TERMINAL_BAUD = stringPreferencesKey("terminal_baud")
        val TERMINAL_SHOW_TIMESTAMPS = booleanPreferencesKey("terminal_show_timestamps")
        val TERMINAL_HEX_MODE = booleanPreferencesKey("terminal_hex_mode")
        val UART_PARITY = stringPreferencesKey("uart_parity")
        val UART_STOP_BITS = stringPreferencesKey("uart_stop_bits")
        val UART_FLOW_CONTROL = stringPreferencesKey("uart_flow_control")
        val SPI_CLOCK_SPEED = stringPreferencesKey("spi_clock_speed")
        val SPI_MODE = stringPreferencesKey("spi_mode")
    }

    val themeMode: Flow<AppThemeMode> = context.dataStore.data.map { p: Preferences ->
        when (p[Keys.THEME_MODE]) {
            "light" -> AppThemeMode.Light
            "dark" -> AppThemeMode.Dark
            else -> AppThemeMode.System
        }
    }

    val bridgeEnabled: Flow<Boolean> = context.dataStore.data.map { p ->
        p[Keys.BRIDGE_ENABLED] ?: true
    }

    val terminalBaud: Flow<Int> = context.dataStore.data.map { p ->
        p[Keys.TERMINAL_BAUD]?.toIntOrNull() ?: 115_200
    }

    val terminalShowTimestamps: Flow<Boolean> = context.dataStore.data.map { p ->
        p[Keys.TERMINAL_SHOW_TIMESTAMPS] ?: true
    }

    val terminalHexMode: Flow<Boolean> = context.dataStore.data.map { p ->
        p[Keys.TERMINAL_HEX_MODE] ?: false
    }

    val uartParity: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.UART_PARITY] ?: "None"
    }

    val uartStopBits: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.UART_STOP_BITS] ?: "1"
    }

    val uartFlowControl: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.UART_FLOW_CONTROL] ?: "None"
    }

    val spiClockSpeed: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.SPI_CLOCK_SPEED] ?: "1MHz"
    }

    val spiMode: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.SPI_MODE] ?: "0"
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { p ->
            p[Keys.THEME_MODE] = when (mode) {
                AppThemeMode.System -> "system"
                AppThemeMode.Light -> "light"
                AppThemeMode.Dark -> "dark"
            }
        }
    }

    suspend fun setBridgeEnabled(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.BRIDGE_ENABLED] = enabled }
    }

    suspend fun setTerminalBaud(baud: Int) {
        context.dataStore.edit { p -> p[Keys.TERMINAL_BAUD] = baud.toString() }
    }

    suspend fun setTerminalShowTimestamps(show: Boolean) {
        context.dataStore.edit { p -> p[Keys.TERMINAL_SHOW_TIMESTAMPS] = show }
    }

    suspend fun setTerminalHexMode(hex: Boolean) {
        context.dataStore.edit { p -> p[Keys.TERMINAL_HEX_MODE] = hex }
    }

    suspend fun setUartParity(parity: String) {
        context.dataStore.edit { p -> p[Keys.UART_PARITY] = parity }
    }

    suspend fun setUartStopBits(stopBits: String) {
        context.dataStore.edit { p -> p[Keys.UART_STOP_BITS] = stopBits }
    }

    suspend fun setUartFlowControl(flow: String) {
        context.dataStore.edit { p -> p[Keys.UART_FLOW_CONTROL] = flow }
    }

    suspend fun setSpiClockSpeed(speed: String) {
        context.dataStore.edit { p -> p[Keys.SPI_CLOCK_SPEED] = speed }
    }

    suspend fun setSpiMode(mode: String) {
        context.dataStore.edit { p -> p[Keys.SPI_MODE] = mode }
    }
}
