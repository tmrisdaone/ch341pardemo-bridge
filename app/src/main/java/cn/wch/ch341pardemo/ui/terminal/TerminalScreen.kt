package cn.wch.ch341pardemo.ui.terminal

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.wch.ch341pardemo.data.HexUtil
import cn.wch.ch341pardemo.data.TerminalLine
import cn.wch.ch341pardemo.data.UartConfig
import cn.wch.ch341pardemo.ui.theme.StatusConnected
import cn.wch.ch341pardemo.ui.theme.StatusDisconnected
import cn.wch.ch341pardemo.ui.theme.StatusError
import cn.wch.ch341pardemo.ui.theme.StatusTx
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as android.app.Application
    val vm: TerminalViewModel = viewModel(
        factory = TerminalViewModel.Factory(app)
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new lines
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.size - 1)
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("UART Terminal", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = state.connectedDevice?.productName
                                ?: "No device selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { vm.setShowTimestamps(!state.showTimestamps) }) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = "Toggle timestamps",
                            tint = if (state.showTimestamps)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.setHexMode(!state.hexMode) }) {
                        Icon(
                            if (state.hexMode) Icons.Rounded.Code else Icons.Rounded.TextSnippet,
                            contentDescription = "Toggle hex mode",
                            tint = if (state.hexMode)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.clearLog() }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Clear log")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Device Selection
            DevicePicker(
                devices = state.availableDevices,
                selectedDevice = state.connectedDevice,
                onSelect = { vm.selectDevice(it) },
                onRefresh = { vm.refreshDevices() }
            )

            // Status + baud row
            StatusRow(
                isOpen = state.isOpen,
                isReading = state.isReading,
                baud = state.config.baudRate,
                hexMode = state.hexMode,
                onBaudChange = { vm.setBaud(it) },
                onConnect = { vm.openConnection() },
                onDisconnect = { vm.closeConnection() }
            )

            HorizontalDivider()

            // Log
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                if (state.lines.isEmpty()) {
                    EmptyLog(onExportSample = { vm.sendBytes(byteArrayOf(0x41, 0x42, 0x43)) })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.lines) { line ->
                            TerminalLineRow(
                                line = line,
                                showTimestamps = state.showTimestamps,
                                hexMode = state.hexMode
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Input + send
            InputBar(
                value = state.pendingInput,
                hexMode = state.hexMode,
                enabled = state.isOpen,
                onValueChange = { vm.updateInput(it) },
                onSend = { vm.send() },
                onSave = {
                    val text = vm.exportLog()
                    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "ch341_terminal_${System.currentTimeMillis()}.log")
                    file.writeText(text)
                    scope.launch {
                        snackbar.showSnackbar("Saved to ${file.name}")
                    }
                    val uri = FileProvider.getUriForFile(
                        ctx,
                        ctx.packageName + ".fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Share terminal log"))
                }
            )
        }
    }
}

@Composable
private fun StatusRow(
    isOpen: Boolean,
    isReading: Boolean,
    baud: Int,
    hexMode: Boolean,
    onBaudChange: (Int) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isReading -> StatusTx
                        isOpen -> StatusConnected
                        else -> StatusDisconnected
                    }
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                isReading -> "Receiving"
                isOpen -> "Connected"
                else -> "Disconnected"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.width(16.dp))
        BaudPicker(baud = baud, onBaudChange = onBaudChange, enabled = !isOpen)

        Spacer(modifier = Modifier.weight(1f))

        if (isOpen) {
            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Close") }
        } else {
            Button(onClick = onConnect) {
                Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Connect")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaudPicker(baud: Int, onBaudChange: (Int) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = "$baud",
            onValueChange = {},
            readOnly = true,
            label = { Text("Baud") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .width(140.dp),
            enabled = enabled,
            singleLine = true
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            UartConfig.CommonBaudRates.forEach { b ->
                DropdownMenuItem(
                    text = { Text("$b") },
                    onClick = {
                        onBaudChange(b)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TerminalLineRow(
    line: TerminalLine,
    showTimestamps: Boolean,
    hexMode: Boolean
) {
    val color = when (line.direction) {
        TerminalLine.Direction.RX -> MaterialTheme.colorScheme.primary
        TerminalLine.Direction.TX -> MaterialTheme.colorScheme.tertiary
        TerminalLine.Direction.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        TerminalLine.Direction.ERROR -> StatusError
    }
    val icon = when (line.direction) {
        TerminalLine.Direction.RX -> Icons.Rounded.ArrowDownward
        TerminalLine.Direction.TX -> Icons.Rounded.ArrowUpward
        else -> null
    }
    val text = when {
        hexMode -> HexUtil.hexDump(line.bytes).trimEnd()
        else -> String(line.bytes, Charsets.UTF_8).trimEnd()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (showTimestamps) {
            Text(
                text = HexUtil.formatTimestamp(line.timestampMs),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(12.dp)
                    .padding(top = 1.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (hexMode) FontFamily.Monospace else FontFamily.Monospace
            ),
            color = color
        )
    }
}

@Composable
private fun EmptyLog(onExportSample: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Cable,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Text(
                "No traffic yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Connect a device and start sending or receiving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    hexMode: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            placeholder = {
                Text(
                    if (hexMode) "AA BB CC …" else "Type a message…",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSave, enabled = value.isNotEmpty() || true) {
            Icon(Icons.Rounded.Save, contentDescription = "Save log")
        }
        Button(
            onClick = onSend,
            enabled = enabled && value.isNotEmpty(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Send")
        }
    }
}
