package com.hien.rtkmultidevice.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hien.rtkmultidevice.core.connection.ConnectionState

/**
 * MainScreen — Màn hình chính với 4 Tab bottom navigation.
 *
 * Tab 1 Dự án:   Quản lý Job, Hệ toạ độ, Danh sách điểm, Import, Export, Cài đặt
 * Tab 2 Thiết bị: Kết nối, Rover, Base, Thông tin, Đo tĩnh
 * Tab 3 Khảo sát: Đo điểm, Bố trí điểm, Định vị CAD, Bố trí hình học, Bề mặt
 * Tab 4 Công cụ:  Hiệu chỉnh trạm, VN-2000, Diện tích, Khối lượng, COGO
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activeProjectId   : Int,
    connectionState   : ConnectionState,
    onNavigateConnect : () -> Unit,
    onNavigateGnss    : () -> Unit,
    onNavigateNtrip   : () -> Unit,
    onNavigateProject : () -> Unit,
    onNavigateSurvey  : (Int) -> Unit,
    onNavigateStakeout  : (Int) -> Unit,
    onNavigateTraverse  : (Int) -> Unit = {},
    onNavigateMap       : (Int) -> Unit,
    onNavigateCoord   : () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHost = remember { SnackbarHostState() }

    val isConnected = connectionState is ConnectionState.Connected
    val connLabel = when (connectionState) {
        is ConnectionState.Connected    -> connectionState.deviceName
        is ConnectionState.Connecting   -> "Đang kết nối..."
        is ConnectionState.Disconnected -> "Chưa kết nối"
        is ConnectionState.Error        -> "Lỗi kết nối"
    }

    val pendingFeature = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingFeature.value) {
        pendingFeature.value?.let {
            snackbarHost.showSnackbar("🚧 \"$it\" — Sắp ra mắt")
            pendingFeature.value = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RTK Field", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.GpsFixed
                                              else Icons.Default.GpsOff,
                                contentDescription = null,
                                tint   = if (isConnected) Color(0xFF80FF80) else Color.White.copy(0.6f),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                connLabel,
                                fontSize = 11.sp,
                                color    = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("Dự án",   Icons.Outlined.Folder,        Icons.Filled.Folder),
                    Triple("Thiết bị",Icons.Outlined.DeviceHub,     Icons.Filled.DeviceHub),
                    Triple("Khảo sát",Icons.Outlined.EditLocation,  Icons.Filled.EditLocation),
                    Triple("Công cụ", Icons.Outlined.Construction,  Icons.Filled.Construction)
                ).forEachIndexed { index, (label, iconOff, iconOn) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick  = { selectedTab = index },
                        icon = {
                            Icon(if (selectedTab == index) iconOn else iconOff, label)
                        },
                        label = { Text(label, fontSize = 10.sp) }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> ProjectTab(
                modifier         = Modifier.padding(padding),
                activeProjectId  = activeProjectId,
                onProject        = onNavigateProject,
                onCoord          = onNavigateCoord,
                onSurveyList     = { if (activeProjectId > 0) onNavigateSurvey(activeProjectId) else onNavigateProject() },
                onMap            = { if (activeProjectId > 0) onNavigateMap(activeProjectId) else onNavigateProject() },
                onComingSoon     = { pendingFeature.value = it }
            )
            1 -> DeviceTab(
                modifier         = Modifier.padding(padding),
                isConnected      = isConnected,
                onConnect        = onNavigateConnect,
                onRover          = { if (isConnected) onNavigateGnss() else onNavigateConnect() },
                onNtrip          = { if (isConnected) onNavigateNtrip() else onNavigateConnect() },
                onComingSoon     = { pendingFeature.value = it }
            )
            2 -> SurveyTab(
                modifier         = Modifier.padding(padding),
                activeProjectId  = activeProjectId,
                isConnected      = isConnected,
                onMeasure        = { if (activeProjectId > 0) onNavigateSurvey(activeProjectId) else onNavigateProject() },
                onStakeout       = { onNavigateStakeout(activeProjectId.coerceAtLeast(0)) },
                onTraverse       = { if (activeProjectId > 0) onNavigateTraverse(activeProjectId) else onNavigateProject() },
                onMap            = { if (activeProjectId > 0) onNavigateMap(activeProjectId) else onNavigateProject() },
                onComingSoon     = { pendingFeature.value = it }
            )
            3 -> ToolsTab(
                modifier     = Modifier.padding(padding),
                onCoord      = onNavigateCoord,
                onComingSoon = { pendingFeature.value = it }
            )
        }
    }
}

// ════════════════════════════════════════════════════════
// Feature card component
// ════════════════════════════════════════════════════════

@Composable
private fun FeatureCard(
    title     : String,
    subtitle  : String,
    icon      : ImageVector,
    iconColor : Color = MaterialTheme.colorScheme.primary,
    badge     : String? = null,        // "Mới", "Beta", null
    enabled   : Boolean = true,
    onClick   : () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.1f)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surface
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(if (enabled) 2.dp else 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier            = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = if (enabled) iconColor else iconColor.copy(alpha = 0.3f),
                    modifier           = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    title,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign  = TextAlign.Center,
                    color      = if (enabled) MaterialTheme.colorScheme.onSurface
                                 else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        fontSize  = 10.sp,
                        textAlign = TextAlign.Center,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 0.7f else 0.3f
                        ),
                        lineHeight = 13.sp
                    )
                }
            }
            badge?.let {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    color    = when (it) {
                        "Mới"  -> Color(0xFF2E7D32)
                        "Beta" -> Color(0xFF1565C0)
                        else   -> Color(0xFF6D4C41)
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        it,
                        fontSize = 8.sp,
                        color    = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════
// Tab 1 — Dự án
// ════════════════════════════════════════════════════════

@Composable
private fun ProjectTab(
    modifier        : Modifier,
    activeProjectId : Int,
    onProject       : () -> Unit,
    onCoord         : () -> Unit,
    onSurveyList    : () -> Unit,
    onMap           : () -> Unit,
    onComingSoon    : (String) -> Unit
) {
    val hasProject = activeProjectId > 0

    LazyVerticalGrid(
        columns               = GridCells.Fixed(3),
        modifier              = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement   = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            FeatureCard("Quản lý Job", "Tạo/mở dự án",
                Icons.Default.FolderOpen, Color(0xFF1565C0), onClick = onProject)
        }
        item {
            FeatureCard("Hệ toạ độ", "VN-2000\nmúi chiếu",
                Icons.Default.Map, Color(0xFF00695C), onClick = onCoord)
        }
        item {
            FeatureCard("Danh sách điểm", if (hasProject) "Dự án hiện tại" else "Chọn dự án trước",
                Icons.Default.ListAlt, Color(0xFF4527A0),
                enabled = hasProject, onClick = onSurveyList)
        }
        item {
            FeatureCard("Bản đồ", "Xem điểm\ntrên bản đồ",
                Icons.Default.Satellite, Color(0xFF2E7D32),
                enabled = hasProject, onClick = onMap)
        }
        item {
            FeatureCard("Import file", "CSV/TXT/DXF\nSHP",
                Icons.Default.FileOpen, Color(0xFF6D4C41),
                badge = "Mới", onClick = { onComingSoon("Import vào Stakeout") })
        }
        item {
            FeatureCard("Export file", "CSV/TXT",
                Icons.Default.FileDownload, Color(0xFF558B2F),
                enabled = hasProject, onClick = onSurveyList)
        }
        item {
            FeatureCard("Cài đặt", "Ứng dụng",
                Icons.Default.Settings, Color(0xFF546E7A),
                onClick = { onComingSoon("Cài đặt ứng dụng") })
        }
        item {
            FeatureCard("Khác", "",
                Icons.Default.MoreHoriz, Color(0xFF78909C),
                onClick = { onComingSoon("Tính năng khác") })
        }
    }
}

// ════════════════════════════════════════════════════════
// Tab 2 — Thiết bị
// ════════════════════════════════════════════════════════

@Composable
private fun DeviceTab(
    modifier     : Modifier,
    isConnected  : Boolean,
    onConnect    : () -> Unit,
    onRover      : () -> Unit,
    onNtrip      : () -> Unit,
    onComingSoon : (String) -> Unit
) {
    LazyVerticalGrid(
        columns               = GridCells.Fixed(3),
        modifier              = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement   = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            FeatureCard(
                "Kết nối",
                if (isConnected) "Đang kết nối" else "BT / WiFi TCP",
                Icons.Default.BluetoothConnected,
                if (isConnected) Color(0xFF2E7D32) else Color(0xFF1565C0),
                onClick = onConnect
            )
        }
        item {
            FeatureCard("Rover", "GNSS live\nNTRIP",
                Icons.Default.GpsFixed, Color(0xFF2E7D32),
                enabled = isConnected, onClick = onRover)
        }
        item {
            FeatureCard("NTRIP", "Cấu hình\ncorrection",
                Icons.Default.Router, Color(0xFF7B1FA2),
                enabled = isConnected, onClick = onNtrip)
        }
        item {
            FeatureCard("Base", "Máy trạm\nbase RTK",
                Icons.Default.CellTower, Color(0xFF0277BD),
                onClick = { onComingSoon("Cấu hình Base RTK") })
        }
        item {
            FeatureCard("Thông tin", "Firmware\ntrạng thái",
                Icons.Default.Info, Color(0xFF546E7A),
                onClick = { onComingSoon("Thông tin thiết bị") })
        }
        item {
            FeatureCard("Đo tĩnh", "Static\nsurvey",
                Icons.Default.Timer, Color(0xFF6D4C41),
                onClick = { onComingSoon("Đo tĩnh (Static)") })
        }
        item {
            FeatureCard("Khác", "",
                Icons.Default.MoreHoriz, Color(0xFF78909C),
                onClick = { onComingSoon("Tính năng khác") })
        }
    }
}

// ════════════════════════════════════════════════════════
// Tab 3 — Khảo sát
// ════════════════════════════════════════════════════════

@Composable
private fun SurveyTab(
    modifier        : Modifier,
    activeProjectId : Int,
    isConnected     : Boolean,
    onMeasure       : () -> Unit,
    onStakeout      : () -> Unit,
    onTraverse      : () -> Unit = {},
    onMap           : () -> Unit,
    onComingSoon    : (String) -> Unit
) {
    val hasProject = activeProjectId > 0

    LazyVerticalGrid(
        columns               = GridCells.Fixed(3),
        modifier              = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement   = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            FeatureCard("Đo điểm", "Thu thập\ntoạ độ RTK",
                Icons.Default.AddLocation, Color(0xFF2E7D32),
                enabled = hasProject && isConnected, onClick = onMeasure)
        }
        item {
            FeatureCard("Bố trí điểm", "Stakeout\ncắm mốc",
                Icons.Default.NearMe, Color(0xFF1565C0),
                onClick = onStakeout)
        }
        item {
            FeatureCard("Định vị CAD", "DXF/SHP\ntrên bản đồ",
                Icons.Default.Layers, Color(0xFF6D4C41),
                badge = "Mới", enabled = hasProject, onClick = onMap)
        }
        item {
            FeatureCard("Đo tuyến", "Traverse\npolyline",
                Icons.Default.Timeline, Color(0xFF4527A0),
                enabled = hasProject, onClick = onTraverse)
        }
        item {
            FeatureCard("Bố trí bề mặt", "DTM/TIN\ngrading",
                Icons.Default.Terrain, Color(0xFF00695C),
                onClick = { onComingSoon("Bố trí bề mặt") })
        }
        item {
            FeatureCard("Khác", "",
                Icons.Default.MoreHoriz, Color(0xFF78909C),
                onClick = { onComingSoon("Tính năng khác") })
        }
    }
}

// ════════════════════════════════════════════════════════
// Tab 4 — Công cụ
// ════════════════════════════════════════════════════════

@Composable
private fun ToolsTab(
    modifier     : Modifier,
    onCoord      : () -> Unit,
    onComingSoon : (String) -> Unit
) {
    LazyVerticalGrid(
        columns               = GridCells.Fixed(3),
        modifier              = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement   = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            FeatureCard("Hiệu chỉnh trạm", "Station\ncalibration",
                Icons.Default.Tune, Color(0xFF1565C0),
                onClick = { onComingSoon("Hiệu chỉnh trạm") })
        }
        item {
            FeatureCard("VN-2000", "Múi chiếu\nHelmert",
                Icons.Default.Language, Color(0xFF00695C),
                onClick = onCoord)
        }
        item {
            FeatureCard("Tính diện tích", "Polygon\narea",
                Icons.Default.SquareFoot, Color(0xFF2E7D32),
                onClick = { onComingSoon("Tính diện tích") })
        }
        item {
            FeatureCard("Tính khối lượng", "Volume\nDTM",
                Icons.Default.Landscape, Color(0xFF4527A0),
                onClick = { onComingSoon("Tính khối lượng") })
        }
        item {
            FeatureCard("COGO", "Bearing\nDistance",
                Icons.Default.Explore, Color(0xFF6D4C41),
                onClick = { onComingSoon("COGO") })
        }
        item {
            FeatureCard("Khác", "",
                Icons.Default.MoreHoriz, Color(0xFF78909C),
                onClick = { onComingSoon("Tính năng khác") })
        }
    }
}
