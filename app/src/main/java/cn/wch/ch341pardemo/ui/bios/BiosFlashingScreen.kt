package cn.wch.ch341pardemo.ui.bios

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilePresent
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.wch.ch341pardemo.data.Ch341DeviceInfo
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiosFlashingScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val vm: BiosFlashingViewModel = viewModel(factory = BiosFlashingViewModel.Factory(app))
    val state by vm.state.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.selectFile(it) }
        }



    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let { vm.onBackupUriSelected(it) }
        }



    LaunchedEffect(state.isRequestingBackupUri) {
        if (state.isRequestingBackupUri) {
            backupPicker.launch("bios_backup.bin")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BIOS Flashing", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Flash Device Firmware", style = MaterialTheme.typography.titleMedium)

            // Device Selection
            DeviceSelectionCard(
                devices = state.availableDevices,
                selectedDevice = state.selectedDevice,
                onSelect = { vm.selectDevice(it) },
                onRefresh = { vm.refreshDevices() }
            )

            // File Selection
            FileSelectionCard(
                selectedFileUri = state.selectedFileUri,
                onSelect = { filePicker.launch(arrayOf("application/octet-stream")) }
            )

            // Operations Section
            Text("Operations", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { vm.detectChip() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Detect")
                }
                OutlinedButton(
                    onClick = { vm.readBios() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Read")
                }
                OutlinedButton(
                    onClick = { vm.backupBios() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Backup")
                }
            }

            // Flash Action
            Button(
                onClick = { vm.startFlashing() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedDevice != null && state.selectedFileUri != null && !state.isFlashing,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.isFlashing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Flashing...")
                } else {
                    Text("Start Flashing")
                }
            }

            // Progress & Logs
            if (state.isFlashing || state.progress > 0f) {
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                )
                Text("${(state.progress * 100).toInt()}% complete", style = MaterialTheme.typography.labelSmall)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                LazyColumn {
                    items(state.log) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceSelectionCard(
    devices: List<Ch341DeviceInfo>,
    selectedDevice: Ch341DeviceInfo?,
    onSelect: (Ch341DeviceInfo) -> Unit,
    onRefresh: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select Device", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh devices")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Text("No CH341 devices found", style = MaterialTheme.typography.bodySmall)
            } else {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(device) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = device.productName ?: "Unknown Device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (device == selectedDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileSelectionCard(
    selectedFileUri: Uri?,
    onSelect: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.FilePresent, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("BIOS Firmware File", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = selectedFileUri?.toString()?.takeLast(30) ?: "No file selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onSelect) {
                Text("Select")
            }
        }
    }
}
