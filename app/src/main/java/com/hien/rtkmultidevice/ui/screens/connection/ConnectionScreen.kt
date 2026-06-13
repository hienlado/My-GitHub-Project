package com.hien.rtkmultidevice.ui.screens.connection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.core.connection.ConnectionState
import com.hien.rtkmultidevice.core.permission.BluetoothPermissionState
import com.hien.rtkmultidevice.domain.model.DeviceInfo

/**
 * ConnectionScreen — Phase 2 update.
 *
 * Bổ sung so với Phase 1:
 *   ✅ Permission request với rationale hướng dẫn rõ ràng
 *   ✅ Lịch sử thiết bị gần đây (tap 1 lần để kết nối lại)
 *   ✅ Long press trên thiết bị lịch sử → xoá khỏi danh sách
 *   ✅ Tách nhóm thiết bị: RTK / Thiết bị khác
 *   ✅ Hiển thị bước đang thực hiện khi connecting
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected : () -> Unit,
    onSkip      : () -> Unit = {},    // Bỏ qua kết nối, vào app offline
    viewModel   : ConnectionViewModel = hiltViewModel()
) {
    val permissionState by viewModel.permissionState.collectAsStateWithLifecycle()
    val rtkDevices      by viewModel.rtkDevices.collectAsStateWithLifecycle()
    val otherDevices    by viewModel.otherDevices.collectAsStateWithLifecycle()
    val recentDevices   by viewModel.recentDevices.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isLoading       by viewModel.isLoading.collectAsStateWithLifecycle()
    val connectingStep  by viewModel.connectingStep.collectAsStateWithLifecycle()
    val tcpHost         by viewModel.tcpHost.collectAsStateWithLifecycle()
    val tcpPort         by viewModel.tcpPort.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }

    // Launcher xin permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Sau khi user xử lý permission dialog → check lại
        viewModel.checkPermissions()
    }

    // Navigate khi kết nối thành công
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) onConnected()
    }

    LaunchedEffect(Unit) {
        viewModel.checkExistingConnection()
        viewModel.checkPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kết nối thiết bị RTK") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    TextButton(onClick = onSkip) {
                        Text("Bỏ qua", color = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier             = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement  = Arrangement.spacedBy(10.dp),
            contentPadding       = PaddingValues(vertical = 12.dp)
        ) {

            // ── Trạng thái kết nối ──────────────────────────
            item { ConnectionStatusCard(connectionState, connectingStep, isLoading) }

            // ── Yêu cầu permission (nếu chưa có) ────────────
            if (permissionState !is BluetoothPermissionState.Granted) {
                item {
                    PermissionCard {
                        permissionLauncher.launch(viewModel.getMissingPermissions())
                    }
                }
            } else {

                // ── Lịch sử thiết bị ─────────────────────────
                if (recentDevices.isNotEmpty()) {
                    item {
                        SectionHeader(
                            icon  = Icons.Default.History,
                            title = "Gần đây (nhấn giữ để xoá)"
                        )
                    }
                    items(recentDevices, key = { it.address }) { device ->
                        RecentDeviceCard(
                            device    = device,
                            onClick   = { viewModel.reconnectDevice(device) },
                            onDelete  = { viewModel.deleteFromHistory(device.address) }
                        )
                    }
                }

                // ── Tabs BT / TCP ─────────────────────────────
                item {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick  = { selectedTab = 0 },
                            text     = { Text("Bluetooth") },
                            icon     = { Icon(Icons.Default.Bluetooth, null) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick  = { selectedTab = 1 },
                            text     = { Text("WiFi / TCP") },
                            icon     = { Icon(Icons.Default.Wifi, null) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> {
                        // ── Thiết bị RTK được nhận diện ─────────
                        if (rtkDevices.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    icon  = Icons.Default.GpsFixed,
                                    title = "Thiết bị RTK / GNSS (${rtkDevices.size})"
                                )
                            }
                            items(rtkDevices, key = { "rtk_${it.address}" }) { device ->
                                DeviceCard(device = device, isRtk = true) {
                                    viewModel.connectBluetooth(device)
                                }
                            }
                        }

                        // ── Thiết bị BT khác ──────────────────
                        item {
                            SectionHeader(
                                icon  = Icons.Default.Bluetooth,
                                title = if (rtkDevices.isEmpty())
                                    "Thiết bị đã ghép đôi"
                                else
                                    "Thiết bị khác (${otherDevices.size})"
                            )
                        }

                        if (rtkDevices.isEmpty() && otherDevices.isEmpty()) {
                            item { EmptyBluetoothHint() }
                        }

                        items(otherDevices, key = { "other_${it.address}" }) { device ->
                            DeviceCard(device = device, isRtk = false) {
                                viewModel.connectBluetooth(device)
                            }
                        }

                        item {
                            TextButton(
                                onClick  = { viewModel.loadPairedDevices() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Làm mới danh sách")
                            }
                        }
                    }
                    1 -> {
                        item {
                            TcpPanel(
                                host         = tcpHost,
                                port         = tcpPort,
                                isLoading    = isLoading,
                                onHostChange = viewModel::onTcpHostChanged,
                                onPortChange = viewModel::onTcpPortChanged,
                                onConnect    = viewModel::connectTcp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
// Sub-composables
// ────────────────────────────────────────────────────────────

@Composable
private fun ConnectionStatusCard(
    state         : ConnectionState,
    connectingStep: String,
    isLoading     : Boolean
) {
    val (label, color) = when (state) {
        is ConnectionState.Disconnected -> "Chưa kết nối"                     to Color(0xFF9E9E9E)
        is ConnectionState.Connecting   -> (connectingStep.ifBlank { "Đang kết nối..." }) to Color(0xFFFF9800)
        is ConnectionState.Connected    -> "✓ ${state.deviceName}"             to Color(0xFF4CAF50)
        is ConnectionState.Error        -> "✗ ${state.message}"                to Color(0xFFF44336)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        border   = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier  = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color     = color
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color, shape = MaterialTheme.shapes.extraSmall)
                )
            }
            Text(label, color = color, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Cần quyền Bluetooth",
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Ứng dụng cần quyền Bluetooth để quét và kết nối " +
                "thiết bị RTK. Nhấn nút bên dưới để cấp quyền.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick  = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Cấp quyền Bluetooth")
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon  : androidx.compose.ui.graphics.vector.ImageVector,
    title : String
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier              = Modifier.padding(top = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            title,
            style      = MaterialTheme.typography.labelLarge,
            color      = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentDeviceCard(
    device   : DeviceInfo,
    onClick  : () -> Unit,
    onDelete : () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title   = { Text("Xoá lịch sử") },
            text    = { Text("Xoá \"${device.name}\" khỏi danh sách gần đây?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("Xoá", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Huỷ") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick     = onClick,
                onLongClick = { showDeleteDialog = true }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector        = if (device.type == DeviceInfo.ConnectionType.BLUETOOTH)
                    Icons.Default.Bluetooth else Icons.Default.Wifi,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.secondary,
                modifier           = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Kết nối lại",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device  : DeviceInfo,
    isRtk   : Boolean,
    onClick : () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick  = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border   = if (isRtk)
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
        else null
    ) {
        Row(
            modifier          = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint     = if (isRtk) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (isRtk) {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                            Text("RTK", fontSize = 9.sp)
                        }
                    }
                }
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Kết nối →",
                color      = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                fontSize   = 13.sp
            )
        }
    }
}

@Composable
private fun EmptyBluetoothHint() {
    Column(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.BluetoothSearching,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(
            "Chưa có thiết bị nào ghép đôi",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Vào Cài đặt → Bluetooth → ghép đôi thiết bị RTK trước",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TcpPanel(
    host         : String,
    port         : String,
    isLoading    : Boolean,
    onHostChange : (String) -> Unit,
    onPortChange : (String) -> Unit,
    onConnect    : () -> Unit
) {
    Column(
        modifier            = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = "Dùng khi máy RTK phát WiFi hotspot (thường port 2000 hoặc 5000). " +
                       "Điện thoại phải kết nối cùng mạng WiFi với máy RTK.",
                modifier = Modifier.padding(12.dp),
                style    = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedTextField(
            value         = host,
            onValueChange = onHostChange,
            label         = { Text("Địa chỉ IP máy RTK") },
            placeholder   = { Text("VD: 192.168.1.1") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            leadingIcon   = { Icon(Icons.Default.Wifi, null) }
        )
        OutlinedTextField(
            value           = port,
            onValueChange   = onPortChange,
            label           = { Text("Cổng TCP (Port)") },
            placeholder     = { Text("VD: 2000") },
            modifier        = Modifier.fillMaxWidth(),
            singleLine      = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        AnimatedVisibility(visible = isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick  = onConnect,
            enabled  = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Đang kết nối...")
            } else {
                Icon(Icons.Default.Wifi, null)
                Spacer(Modifier.width(8.dp))
                Text("Kết nối TCP/WiFi", fontSize = 15.sp)
            }
        }
    }
}
