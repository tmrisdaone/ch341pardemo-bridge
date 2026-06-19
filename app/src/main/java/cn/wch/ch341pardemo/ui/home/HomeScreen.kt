package cn.wch.ch341pardemo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.wch.ch341pardemo.MyApplication
import cn.wch.ch341pardemo.data.Ch341DeviceInfo
import cn.wch.ch341pardemo.data.HexUtil
import cn.wch.ch341pardemo.ui.theme.StatusConnected
import cn.wch.ch341pardemo.ui.theme.StatusDisconnected
import cn.wch.ch341pardemo.ui.theme.StatusError
import cn.wch.ch341pardemo.ui.theme.StatusTx

@Composable
fun HomeScreen(
    onOpenTerminal: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as MyApplication
    val vm: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(app)
    )
    val state by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Header() }

        item { BridgeStatusCard(bridgeEnabled = state.bridgeEnabled) }

        item {
            QuickActionsRow(
                onOpenTerminal = onOpenTerminal,
                onOpenDevices = onOpenDevices,
                onOpenSettings = onOpenSettings
            )
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Detected CH341 devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                }
            }
        }

        if (state.devices.isEmpty()) {
            item { NoDevicesCard() }
        } else {
            items(state.devices, key = { it.deviceId }) { d ->
                DeviceCard(d)
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "CH341 Bridge",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "USB ↔ Serial / SPI / I²C / GPIO bridge",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BridgeStatusCard(bridgeEnabled: Boolean) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (bridgeEnabled) StatusConnected
                        else StatusDisconnected
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Termux bridge",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (bridgeEnabled) "Active — exposing USB to Termux at /data/local/tmp/ch341_bridge"
                    else "Disabled — toggle in Settings to expose USB to Termux",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Rounded.SettingsRemote,
                contentDescription = null,
                tint = if (bridgeEnabled) StatusTx else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickActionsRow(
    onOpenTerminal: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ActionCard(
            label = "Terminal",
            subtitle = "UART RX/TX",
            icon = Icons.Rounded.Terminal,
            tint = MaterialTheme.colorScheme.primary,
            onClick = onOpenTerminal,
            modifier = Modifier.weight(1f)
        )
        ActionCard(
            label = "Devices",
            subtitle = "USB picker",
            icon = Icons.Rounded.Cable,
            tint = MaterialTheme.colorScheme.tertiary,
            onClick = onOpenDevices,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionCard(
    label: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoDevicesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "No CH341 plugged in",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Plug a CH341 USB-serial adapter into your device. " +
                    "You'll need to grant USB permission when prompted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeviceCard(d: Ch341DeviceInfo) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = d.productName ?: "CH341 device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = buildString {
                        append("VID ")
                        append(HexUtil.bytesToHex(byteArrayOf(0, 0).also {
                            it[0] = (d.vendorId ushr 8).toByte()
                            it[1] = (d.vendorId and 0xFF).toByte()
                        }, ""))
                        append(" · PID ")
                        append(HexUtil.bytesToHex(byteArrayOf(0, 0).also {
                            it[0] = (d.productId ushr 8).toByte()
                            it[1] = (d.productId and 0xFF).toByte()
                        }, ""))
                        if (d.serialNumber != null) append(" · ${d.serialNumber}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = "Ready",
                style = MaterialTheme.typography.labelSmall,
                color = StatusConnected
            )
        }
    }
}
