package com.hien.rtkmultidevice.ui.screens.gnss

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.core.connection.ConnectionState
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.NtripState
import com.hien.rtkmultidevice.domain.model.SatelliteInfo
import com.hien.rtkmultidevice.ui.components.SatelliteSignalBar

/**
 * GnssScreen — Màn hình chính hiển thị dữ liệu GNSS live.
 *
 * Bố cục gồm 3 tab:
 *   [Toạ độ]    — Vị trí, độ cao, Fix quality, NTRIP
 *   [Vệ tinh]   — Bar chart SNR, số lượng theo constellation
 *   [Chuyển động] — Vận tốc, hướng, la bàn đơn giản
 *
 * Thiết kế theo chuẩn field software:
 *   - Fix Status nổi bật, màu theo chuẩn ngành
 *   - Toạ độ font monospace, dễ đọc ngoài trời
 *   - Biểu đồ vệ tinh rõ ràng, màu theo constellation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GnssScreen(
    onNavigateToNtripConfig   : () -> Unit,
    onNavigateToCoordSettings : () -> Unit,
    onNavigateToSurvey        : (projectId: Int) -> Unit = {},
    onNavigateToStakeout      : (projectId: Int) -> Unit = {},
    onNavigateToMap           : (projectId: Int) -> Unit = {},
    onDisconnect              : () -> Unit,
    viewModel                 : GnssViewModel = hiltViewModel()
) {
    val gnssStatus      by viewModel.gnssStatus.collectAsStateWithLifecycle()
    val satellites      by viewModel.satellites.collectAsStateWithLifecycle()
    val ntripState      by viewModel.ntripState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val activeProjectId by viewModel.activeProjectId.collectAsStateWithLifecycle()
    val context         = LocalContext.current

    // Tab state — 0=Toạ độ, 1=Vệ tinh, 2=Chuyển động
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Toạ độ", "Vệ tinh", "Chuyển động")

    // Overflow menu
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Dialog hướng dẫn cấu hình Smart Receiver (CHCNav M6 Pro) qua WiFi
    var showSmartReceiverDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("RTK Field Software", fontSize = 16.sp)
                            Text(
                                text = when (val cs = connectionState) {
                                    is ConnectionState.Connected -> cs.deviceName
                                    else                         -> "Không kết nối"
                                },
                                fontSize = 11.sp,
                                color    = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor         = MaterialTheme.colorScheme.primary,
                        titleContentColor      = Color.White,
                        actionIconContentColor = Color.White
                    ),
                    actions = {
                        // Cài đặt toạ độ VN-2000
                        IconButton(onClick = onNavigateToCoordSettings) {
                            Icon(Icons.Default.GpsFixed, "Cài đặt toạ độ")
                        }
                        // Cài đặt NTRIP
                        IconButton(onClick = onNavigateToNtripConfig) {
                            Icon(Icons.Default.Settings, "Cài đặt NTRIP")
                        }
                        // Overflow menu (Cắm mốc + Ngắt kết nối)
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, "Menu")
                            }
                            DropdownMenu(
                                expanded         = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                // Menu chỉ hiện khi đã có dự án
                                if (activeProjectId > 0) {
                                    DropdownMenuItem(
                                        text         = { Text("Cắm mốc (Stakeout)") },
                                        leadingIcon  = { Icon(Icons.Default.Adjust, null) },
                                        onClick      = {
                                            showOverflowMenu = false
                                            onNavigateToStakeout(activeProjectId)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text         = { Text("Bản đồ") },
                                        leadingIcon  = { Icon(Icons.Default.Map, null) },
                                        onClick      = {
                                            showOverflowMenu = false
                                            onNavigateToMap(activeProjectId)
                                        }
                                    )
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text         = { Text("Cấu hình Smart Receiver") },
                                    leadingIcon  = { Icon(Icons.Default.Wifi, null) },
                                    onClick      = {
                                        showOverflowMenu = false
                                        showSmartReceiverDialog = true
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text         = { Text("Ngắt kết nối") },
                                    leadingIcon  = { Icon(Icons.Default.LinkOff, null) },
                                    onClick      = {
                                        showOverflowMenu = false
                                        viewModel.disconnect()
                                        onDisconnect()
                                    }
                                )
                            }
                        }
                    }
                )

                // ── Fix Status Banner (luôn hiển thị phía trên tabs) ──
                FixStatusBanner(gnssStatus)

                // ── Tab Row ────────────────────────────────────────────
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor   = MaterialTheme.colorScheme.surface
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick  = { selectedTab = index },
                            text     = { Text(title, fontSize = 13.sp) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // FAB "Đo điểm" — chỉ hiển thị khi đã chọn dự án
            if (activeProjectId > 0) {
                ExtendedFloatingActionButton(
                    onClick            = { onNavigateToSurvey(activeProjectId) },
                    icon               = { Icon(Icons.Default.MyLocation, null) },
                    text               = { Text("Đo điểm") },
                    containerColor     = if (gnssStatus.isRtk)
                        Color(0xFF1B5E20) else MaterialTheme.colorScheme.primary,
                    contentColor       = Color.White
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> CoordinateTabContent(
                gnssStatus        = gnssStatus,
                ntripState        = ntripState,
                modifier          = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onStartNtrip      = { viewModel.startNtrip() },
                onStopNtrip       = { viewModel.stopNtrip() },
                onStartProxy      = { viewModel.startNtripProxy() },
                onStopProxy       = { viewModel.stopNtripProxy() },
                proxyPhoneIp      = viewModel.getProxyPhoneIp(),
                proxyPort         = viewModel.getProxyPort(),
                onNtripSettings   = onNavigateToNtripConfig,
                onCoordSettings   = onNavigateToCoordSettings
            )

            1 -> SatelliteTabContent(
                gnssStatus = gnssStatus,
                satellites = satellites,
                modifier   = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )

            2 -> MovementTabContent(
                gnssStatus = gnssStatus,
                modifier   = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }

    // ── Dialog hướng dẫn cấu hình Smart Receiver ────────────
    if (showSmartReceiverDialog) {
        SmartReceiverSetupDialog(
            onOpenWebInterface = {
                // Mở trình duyệt tại 192.168.1.1
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://192.168.1.1"))
                context.startActivity(intent)
            },
            onDismiss = { showSmartReceiverDialog = false }
        )
    }
}

// ════════════════════════════════════════════════════════════
// Tab 0 — Toạ độ
// ════════════════════════════════════════════════════════════

@Composable
private fun CoordinateTabContent(
    gnssStatus      : GnssStatus,
    ntripState      : NtripState,
    modifier        : Modifier = Modifier,
    onStartNtrip    : () -> Unit,
    onStopNtrip     : () -> Unit,
    onStartProxy    : () -> Unit = {},
    onStopProxy     : () -> Unit = {},
    proxyPhoneIp    : String = "",
    proxyPort       : Int = 2101,
    onNtripSettings : () -> Unit,
    onCoordSettings : () -> Unit = {}
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // WGS-84 + VN-2000 trong một card
        CoordinateCard(gnssStatus, onCoordSettings)
        DetailCard(gnssStatus)
        NtripCard(
            ntripState   = ntripState,
            onStart      = onStartNtrip,
            onStop       = onStopNtrip,
            onStartProxy = onStartProxy,
            onStopProxy  = onStopProxy,
            proxyPhoneIp = proxyPhoneIp,
            proxyPort    = proxyPort,
            onSettings   = onNtripSettings
        )
    }
}

// ════════════════════════════════════════════════════════════
// Tab 1 — Vệ tinh
// ════════════════════════════════════════════════════════════

@Composable
private fun SatelliteTabContent(
    gnssStatus : GnssStatus,
    satellites : List<SatelliteInfo>,
    modifier   : Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Tóm tắt vệ tinh ───────────────────────────────
        SatelliteSummaryCard(gnssStatus, satellites)

        // ── Biểu đồ cột SNR ───────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Tín hiệu vệ tinh (SNR)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "● Chấm trắng = đang dùng tính vị trí  •  Độ mờ = không dùng",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SatelliteSignalBar(
                    satellites = satellites,
                    modifier   = Modifier.fillMaxWidth(),
                    maxHeight  = 130f
                )
            }
        }

        // ── Bảng chi tiết vệ tinh ─────────────────────────
        if (satellites.isNotEmpty()) {
            SatelliteDetailTable(satellites)
        }
    }
}

@Composable
private fun SatelliteSummaryCard(
    gnssStatus : GnssStatus,
    satellites : List<SatelliteInfo>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DetailItem(
                label = "Nhìn thấy",
                value = "${gnssStatus.satellitesInView}"
            )
            DetailItem(
                label = "Đang dùng",
                value = "${gnssStatus.satelliteCount}"
            )
            DetailItem(
                label = "HDOP",
                value = "%.1f".format(gnssStatus.hdop)
            )
            DetailItem(
                label = "PDOP",
                value = "%.1f".format(gnssStatus.pdop)
            )
        }
    }
}

@Composable
private fun SatelliteDetailTable(satellites: List<SatelliteInfo>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Chi tiết vệ tinh",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("PRN", "Hệ thống", "Elev", "Az", "SNR", "").forEach { header ->
                    Text(
                        header,
                        modifier  = Modifier.weight(1f),
                        fontSize  = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // Rows
            satellites.forEach { sat ->
                val conColor = parseHexColor(sat.constellation.colorHex)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "${sat.prn}",
                        modifier   = Modifier.weight(1f),
                        fontSize   = 11.sp,
                        fontWeight = if (sat.isUsed) FontWeight.Bold else FontWeight.Normal,
                        textAlign  = TextAlign.Center
                    )
                    Text(
                        sat.constellation.label,
                        modifier  = Modifier.weight(1f),
                        fontSize  = 10.sp,
                        color     = conColor,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "${sat.elevation}°",
                        modifier  = Modifier.weight(1f),
                        fontSize  = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "${sat.azimuth}°",
                        modifier  = Modifier.weight(1f),
                        fontSize  = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "${sat.snr}",
                        modifier  = Modifier.weight(1f),
                        fontSize  = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color     = snrColor(sat.snr),
                        textAlign = TextAlign.Center
                    )
                    // Indicator "dùng"
                    Text(
                        if (sat.isUsed) "✓" else "",
                        modifier  = Modifier.weight(1f),
                        fontSize  = 11.sp,
                        color     = Color(0xFF4CAF50),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// Tab 2 — Chuyển động
// ════════════════════════════════════════════════════════════

@Composable
private fun MovementTabContent(
    gnssStatus : GnssStatus,
    modifier   : Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Vận tốc lớn ───────────────────────────────────
        SpeedCard(gnssStatus)

        // ── La bàn hướng di chuyển ────────────────────────
        CompassCard(gnssStatus)

        // ── Ngày và thời gian ─────────────────────────────
        DateTimeCard(gnssStatus)
    }
}

@Composable
private fun SpeedCard(status: GnssStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Vận tốc",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text       = "%.1f".format(status.speedKmh),
                fontSize   = 56.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color      = if (status.isMoving)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                "km/h",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "%.2f knots".format(status.speedKmh / 1.852),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompassCard(status: GnssStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Hướng di chuyển",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))

            // Mũi tên chỉ hướng (xoay theo courseTrue)
            val arrowColor by animateColorAsState(
                targetValue = if (status.isMoving)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                animationSpec = tween(500),
                label = "arrowColor"
            )

            Text(
                text     = "↑",
                fontSize = 64.sp,
                color    = arrowColor,
                modifier = Modifier.rotate(status.courseTrue.toFloat())
            )

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%.1f°".format(status.courseTrue),
                        fontWeight = FontWeight.Bold,
                        fontSize   = 22.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "True North",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        compassPoint(status.courseTrue),
                        fontWeight = FontWeight.Bold,
                        fontSize   = 22.sp
                    )
                    Text(
                        "La bàn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DateTimeCard(status: GnssStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    status.date,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Ngày (DD/MM/YYYY)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    status.localTime,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Giờ (UTC+7)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// Shared sub-composables
// ════════════════════════════════════════════════════════════

@Composable
private fun FixStatusBanner(status: GnssStatus) {
    val bgColor by animateColorAsState(
        targetValue = when (status.fixQuality) {
            4    -> Color(0xFF2E7D32)   // RTK Fixed → Xanh đậm
            5    -> Color(0xFF558B2F)   // RTK Float → Xanh nhạt
            2    -> Color(0xFF1565C0)   // DGPS → Xanh dương
            1    -> Color(0xFFF57F17)   // Single → Vàng
            else -> Color(0xFFB71C1C)   // No Fix → Đỏ đậm
        },
        animationSpec = tween(600),
        label = "fixBannerColor"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text          = status.fixLabel,
                color         = Color.White,
                fontSize      = 22.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            if (status.hasFix) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text     = "${status.satelliteCount} sat  •  HDOP ${
                        "%.1f".format(status.hdop)
                    }",
                    color    = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun CoordinateCard(
    status          : GnssStatus,
    onCoordSettings : () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── WGS-84 ──────────────────────────────────────
            Text(
                "Toạ độ WGS-84",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            CoordRow("Vĩ độ (Lat)",  "%.8f°".format(status.latitude))
            CoordRow("Kinh độ (Lon)", "%.8f°".format(status.longitude))
            CoordRow("Độ cao (Alt)",  "%.3f m".format(status.altitude))
            CoordRow("Geoid Sep.",    "%.3f m".format(status.geoidSeparation))

            // ── VN-2000 ─────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text  = status.vn2000?.zoneName ?: "VN-2000 (chưa có fix)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                TextButton(
                    onClick       = onCoordSettings,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector        = Icons.Default.Settings,
                        contentDescription = "Cài đặt múi",
                        modifier           = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("Đổi múi", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))

            if (status.vn2000 != null) {
                val vn = status.vn2000
                CoordRow("X (Northing)", vn.northingFormatted + " m")
                CoordRow("Y (Easting)",  vn.eastingFormatted  + " m")
            } else {
                Text(
                    "Đang chờ tín hiệu GPS...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CoordRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp
        )
    }
    HorizontalDivider(
        color     = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp
    )
}

@Composable
private fun DetailCard(status: GnssStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DetailItem("Vệ tinh",  "${status.satelliteCount}")
            DetailItem("HDOP",     "%.1f".format(status.hdop))
            DetailItem("PDOP",     "%.1f".format(status.pdop))
            DetailItem("UTC+7",    status.localTime)
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NtripCard(
    ntripState   : NtripState,
    onStart      : () -> Unit,
    onStop       : () -> Unit,
    onStartProxy : () -> Unit = {},
    onStopProxy  : () -> Unit = {},
    proxyPhoneIp : String = "",
    proxyPort    : Int = 2101,
    onSettings   : () -> Unit
) {
    val isConnected = ntripState is NtripState.Connected
    var showProxyMode by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector        = Icons.Default.Router,
                        contentDescription = null,
                        tint     = if (isConnected) Color(0xFF4CAF50)
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("NTRIP", style = MaterialTheme.typography.titleSmall)
                }
                Row {
                    // Toggle Proxy mode
                    TextButton(onClick = { showProxyMode = !showProxyMode }) {
                        Text(if (showProxyMode) "Direct" else "Proxy")
                    }
                    TextButton(onClick = onSettings) { Text("Cài đặt") }
                }
            }
            Spacer(Modifier.height(4.dp))

            // ── Status ───────────────────────────────────────
            Text(
                ntripState.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) Color(0xFF4CAF50)
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (ntripState is NtripState.Connected) {
                Text(
                    "RTCM nhận: ${ntripState.bytesFormatted}  •  ${ntripState.packetsReceived} gói",
                    style      = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "RTCM gửi máy: ${ntripState.forwardedFormatted}  •  ${ntripState.packetsForwarded} gói",
                    style      = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (ntripState.bytesForwarded > 0)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.error
                )
                Text(
                    "GGA gửi caster: ${ntripState.ggaSentCount}",
                    style      = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                ntripState.forwardError?.let { error ->
                    Text(
                        "RTCM chưa tới receiver: $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ── Proxy mode info ──────────────────────────────
            // Hiện tự động khi proxy đang lắng nghe (ProxyListening state)
            // hoặc khi user bật toggle showProxyMode thủ công
            val proxyState = ntripState as? NtripState.ProxyListening
            val showProxyInfo = proxyState != null || (showProxyMode && proxyPhoneIp.isNotEmpty())
            val displayIp    = proxyState?.phoneIp   ?: proxyPhoneIp
            val displayPort  = proxyState?.port       ?: proxyPort
            val gatewayIp    = proxyState?.gatewayIp  ?: "192.168.1.1"
            val ctx = LocalContext.current
            if (showProxyInfo) {
                Spacer(Modifier.height(6.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (proxyState != null)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "📡 Proxy đang chờ — Cấu hình RTK Client trên T30:",
                            style      = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        // IP:Port row với nút copy
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Host : $displayIp",
                                    style      = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "Port : $displayPort",
                                    style      = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "Mount: (giống cài đặt NTRIP)",
                                    style      = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            // Nút sao chép IP:Port
                            IconButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText(
                                    "proxy_address", "$displayIp:$displayPort"
                                ))
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Sao chép IP:Port",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        // Hướng dẫn + nút mở web T30
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Vào web T30 ($gatewayIp) → IO Settings\n→ Row 1 RTK Client → Detail → nhập Host/Port trên → Connect",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            // Nút mở web interface T30 trong browser
                            IconButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW,
                                    Uri.parse("http://$gatewayIp"))
                                ctx.startActivity(intent)
                            }) {
                                Icon(
                                    Icons.Default.OpenInBrowser,
                                    contentDescription = "Mở web T30",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Action buttons ────────────────────────────────
            if (showProxyMode) {
                Button(
                    onClick  = if (isConnected) onStopProxy else onStartProxy,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) Color(0xFFC62828)
                                         else Color(0xFF7B1FA2)
                    )
                ) {
                    Text(if (isConnected) "Dừng Proxy" else "Bật Proxy (cho thiết bị không SIM)")
                }
            } else {
                Button(
                    onClick  = if (isConnected) onStop else onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) Color(0xFFC62828)
                                         else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isConnected) "Ngừng NTRIP" else "Bật NTRIP")
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// Smart Receiver Setup Dialog
// ════════════════════════════════════════════════════════════

/**
 * SmartReceiverSetupDialog — Hướng dẫn cấu hình NTRIP trên CHCNav smart receiver (M6 Pro).
 *
 * CHCNav M6 Pro là "smart receiver" — nó tự kết nối NTRIP qua modem 4G nội bộ.
 * Để cấu hình, cần truy cập web interface tại http://192.168.1.1 khi kết nối WiFi hotspot máy.
 *
 * Sau khi cấu hình NTRIP xong, thiết bị sẽ tự đạt RTK Fixed và gửi NMEA Fixed qua Bluetooth.
 * App không cần forward RTCM — chỉ cần nhận NMEA từ BT như bình thường.
 */
@Composable
private fun SmartReceiverSetupDialog(
    onOpenWebInterface : () -> Unit,
    onDismiss          : () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text       = "Cấu hình Smart Receiver",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text     = "Dành cho CHCNav M6 Pro và các smart receiver tương tự.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                // Bước 1
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("①", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Bật WiFi hotspot trên máy RTK, kết nối điện thoại vào mạng WiFi đó.")
                }
                // Bước 2
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("②", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Nhấn \"Mở Web Interface\" để truy cập trang cấu hình.")
                }
                // Bước 3
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("③", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Trong trang web, vào RTK work mode → Auto Rover NTRIP:")
                        Text(
                            text  = "• Nhập Server IP + Port\n• Nhập User + Password\n• Chọn Mountpoint",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Bước 4
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("④", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Ngắt WiFi điện thoại, kết nối lại Bluetooth → máy sẽ tự RTK Fixed.")
                }
                HorizontalDivider()
                Text(
                    text  = "Login mặc định: admin / password",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onOpenWebInterface()
                onDismiss()
            }) {
                Icon(Icons.Default.Wifi, null, modifier = Modifier.padding(end = 6.dp))
                Text("Mở Web Interface (192.168.1.1)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
            }
        }
    )
}

// ════════════════════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════════════════════

/** Màu SNR theo chất lượng tín hiệu */
@Composable
private fun snrColor(snr: Int): Color = when {
    snr <= 0  -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    snr <= 20 -> Color(0xFFF44336)   // Đỏ — yếu
    snr <= 30 -> Color(0xFFFF9800)   // Cam — trung bình
    snr <= 40 -> Color(0xFF4CAF50)   // Xanh lá — tốt
    else      -> Color(0xFF1565C0)   // Xanh đậm — rất tốt (RTK ideal)
}

/** Chuyển đổi hex "#FFRRGGBB" → Compose Color */
private fun parseHexColor(hex: String): Color = try {
    val clean = hex.removePrefix("#")
    when (clean.length) {
        8    -> Color(clean.toLong(16).toInt())
        6    -> Color(("FF$clean").toLong(16).toInt())
        else -> Color.Gray
    }
} catch (e: Exception) { Color.Gray }

/** Hướng la bàn 8 điểm từ góc True North */
private fun compassPoint(course: Double): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx  = ((course + 22.5) / 45.0).toInt() % 8
    return dirs[idx]
}
