package cn.wch.ch341pardemo.ui.settings

import android.content.Context
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.wch.ch341pardemo.data.UartConfig
import cn.wch.ch341pardemo.ui.theme.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app))
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Settings", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { SectionHeader(text = "Bridge") }
            item {
                SettingsCard {
                    SettingRow(
                        icon = Icons.Rounded.SettingsRemote,
                        title = "Termux bridge",
                        subtitle = "Expose USB to Termux at abstract socket @ch341_bridge"
                    ) {
                        Switch(
                            checked = state.bridgeEnabled,
                            onCheckedChange = { vm.setBridgeEnabled(it) }
                        )
                    }
                }
            }

            item { SectionHeader(text = "Appearance") }
            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Rounded.Palette, contentDescription = null)
                            Spacer(modifier = Modifier.width(width = 12.dp))
                            Text(
                                text = "Theme",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.size(size = 12.dp))
                        val modes = listOf(
                            AppThemeMode.System to "System",
                            AppThemeMode.Light to "Light",
                            AppThemeMode.Dark to "Dark"
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { idx, (mode, label) ->
                                SegmentedButton(
                                    selected = state.themeMode == mode,
                                    onClick = { vm.setThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = modes.size)
                                ) {
                                    Text(text = label)
                                }
                            }
                        }
                    }
                }
            }

            item { SectionHeader(text = "Terminal defaults") }
            item {
                SettingsCard {
                    SettingRow(
                        icon = Icons.Rounded.Speed,
                        title = "Default baud",
                        subtitle = "${state.terminalBaud} baud (tap to change)"
                    ) {
                        var expanded: Boolean by remember { mutableStateOf(false) }
                        LazyColumn(
                            modifier = Modifier.width(width = 220.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(space = 2.dp)
                        ) {
                            item {
                                TextButton(
                                    onClick = { expanded = !expanded },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "${state.terminalBaud} baud",
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Start
                                    )
                                }
                            }
                            items(items = UartConfig.CommonBaudRates) { rate ->
                                TextButton(
                                    onClick = {
                                        vm.setTerminalBaud(baud = rate)
                                        expanded = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "$rate",
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Start
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Rounded.Schedule,
                        title = "Show timestamps",
                        subtitle = "Prefix each line with HH:mm:ss.SSS"
                    ) {
                        Switch(
                            checked = state.terminalShowTimestamps,
                            onCheckedChange = { vm.setTerminalShowTimestamps(it) }
                        )
                    }
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Rounded.Code,
                        title = "Hex mode by default",
                        subtitle = "Treat input as hex bytes (AA BB CC ...)"
                    ) {
                        Switch(
                            checked = state.terminalHexMode,
                            onCheckedChange = { vm.setTerminalHexMode(it) }
                        )
                    }
                }
            }

            item { SectionHeader(text = "About") }
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "CH341 Bridge v2.0",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Kotlin + Jetpack Compose + Material 3",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "USB Host API • No Shizuku • No root",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        content()
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(width = 12.dp))
        Column(modifier = Modifier.weight(weight = 1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}
