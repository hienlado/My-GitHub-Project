package com.hien.rtkmultidevice.ui.screens.survey

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LabelOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import com.hien.rtkmultidevice.core.network.CellularNetworkHelper
import com.hien.rtkmultidevice.domain.model.AveragingSession
import com.hien.rtkmultidevice.export.ExportManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import com.hien.rtkmultidevice.ui.components.CompactActionIcon
import com.hien.rtkmultidevice.ui.components.CompactTopBar
import com.hien.rtkmultidevice.ui.components.FloatingGnssCard
import com.hien.rtkmultidevice.ui.screens.map.CoordAlignDialog
import com.hien.rtkmultidevice.ui.screens.map.MapTileDiagnostics
import com.hien.rtkmultidevice.ui.screens.map.GnssMapView
import com.hien.rtkmultidevice.ui.screens.map.MapLabelConfig
import com.hien.rtkmultidevice.ui.screens.map.MapTileSource
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
import kotlinx.coroutines.launch

/**
 * SurveyScreen — Màn hình thu thập điểm đo RTK.
 *
 * Tab 0 "Đo điểm":
 *   - Hiển thị chất lượng fix + toạ độ live (WGS-84 và VN-2000)
 *   - Form nhập mã điểm (auto-increment) + ghi chú
 *   - Nút "Lưu điểm" (disabled khi chưa có fix)
 *
 * Tab 1 "Danh sách":
 *   - Danh sách điểm đã lưu theo thứ tự đo
 *   - Long press → xoá điểm
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SurveyScreen(
    projectId       : Int,
    onNavigateBack  : () -> Unit,
    viewModel       : SurveyViewModel = hiltViewModel()
) {
    val gnss        by viewModel.gnssStatus.collectAsStateWithLifecycle()
    val project     by viewModel.project.collectAsStateWithLifecycle()
    val savedPoints by viewModel.savedPoints.collectAsStateWithLifecycle()
    val importedMapPoints by viewModel.importedMapPoints.collectAsStateWithLifecycle()
    val importedLineGeo by viewModel.importedLineGeo.collectAsStateWithLifecycle()
    val pointCode   by viewModel.pointCode.collectAsStateWithLifecycle()
    val note        by viewModel.note.collectAsStateWithLifecycle()
    val isSaving    by viewModel.isSaving.collectAsStateWithLifecycle()
    val feedback    by viewModel.savedFeedback.collectAsStateWithLifecycle()
    val error       by viewModel.error.collectAsStateWithLifecycle()

    val exportResult      by viewModel.exportResult.collectAsStateWithLifecycle()
    val isExporting       by viewModel.isExporting.collectAsStateWithLifecycle()
    val averagingSession  by viewModel.averagingSession.collectAsStateWithLifecycle()
    val isAveraging       by viewModel.isAveraging.collectAsStateWithLifecycle()
    val targetEpochs      by viewModel.targetEpochs.collectAsStateWithLifecycle()
    // Feature CAD đang stakeout — highlight + nhãn số hiệu đỉnh trên bản đồ Survey
    val stakeFeatureId    by viewModel.stakeFeatureId.collectAsStateWithLifecycle()
    // Cài đặt thu thập: âm báo fix + ràng buộc chỉ lưu FIXED
    val surveySettings    by viewModel.surveySettings.collectAsStateWithLifecycle()

    var selectedTab      by remember { mutableIntStateOf(0) }
    var deleteTarget     by remember { mutableStateOf<SurveyPoint?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showCustomExport by remember { mutableStateOf(false) }
    var showLabelMenu    by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showTileMenu     by remember { mutableStateOf(false) }
    var selectedTile     by remember { mutableStateOf(MapTileSource.GOOGLE_NORMAL) }   // mặc định Google Maps
    var labelConfig      by remember { mutableStateOf(MapLabelConfig()) }
    var followGps        by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // ── Âm báo xác nhận khi lưu điểm ────────────────────────
    // (Trạng thái Single/Float/Fixed phân biệt bằng MÀU trên thanh trạng thái.)
    val saveBeeper = rememberSaveBeeper()

    // ── Vector layer import ─────────────────────────────────
    // Layer CAD dùng chung (VectorLayerHolder) — import ở Map/Stakeout cũng thấy ở đây
    val importedLayer   by viewModel.sharedVectorLayer.collectAsStateWithLifecycle()
    var isImporting     by remember { mutableStateOf(false) }
    var showAlignDialog by remember { mutableStateOf(false) }
    val importScope = rememberCoroutineScope()

    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val fileName: String = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cur ->
                if (cur.moveToFirst()) {
                    val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cur.getString(idx) else null
                } else null
            }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') }
            ?: "unknown.dxf"
        isImporting = true
        importScope.launch {
            // Truyền KTT của dự án làm gợi ý → import đúng múi chiếu ngay lần đầu
            val hintCm = project?.centralMeridian?.takeIf { it > 0.0 } ?: VectorLayerImporter.DEFAULT_CM
            when (val result = VectorLayerImporter.importFromUri(context, uri, fileName, hintCm)) {
                is VectorLayerImporter.ImportResult.Success -> {
                    viewModel.setSharedVectorLayer(result.layer)
                    val sys = result.layer.coordSystem
                    val cm  = result.layer.detectedCm
                    val sysLabel = when (sys) {
                        VectorLayerImporter.CoordSystem.VN2000_3DEG -> "VN-2000 múi 3° (KTT ${cm}°)"
                        VectorLayerImporter.CoordSystem.VN2000_6DEG -> "VN-2000 múi 6°"
                        VectorLayerImporter.CoordSystem.WGS84        -> "WGS-84"
                        else                                          -> "Không rõ"
                    }
                    snackbarHostState.showSnackbar(
                        "✓ ${result.layer.featureCount} đối tượng — $sysLabel"
                    )
                }
                is VectorLayerImporter.ImportResult.Error ->
                    snackbarHostState.showSnackbar("Lỗi import: ${result.message}")
            }
            isImporting = false
        }
    }

    // Xử lý kết quả export
    LaunchedEffect(exportResult) {
        when (val result = exportResult) {
            is ExportManager.ExportResult.Success -> {
                snackbarHostState.showSnackbar("✓ Đã lưu: ${result.fileName} (${result.rowCount} điểm)")
                // Mở share sheet trực tiếp qua context — không dùng ActivityResultLauncher
                val shareIntent = viewModel.createShareIntent(result)
                context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ file khảo sát"))
                viewModel.clearExportResult()
            }
            is ExportManager.ExportResult.Error -> {
                snackbarHostState.showSnackbar("Lỗi: ${result.message}")
                viewModel.clearExportResult()
            }
            null -> {}
        }
    }

    // Snackbar + âm báo khi lưu điểm thành công
    LaunchedEffect(feedback) {
        feedback?.let {
            saveBeeper.beepSaved(surveySettings.soundEnabled)
            snackbarHostState.showSnackbar("✓ Đã lưu điểm $it")
            viewModel.clearSavedFeedback()
        }
    }

    // Snackbar lỗi
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Cảnh báo WiFi không có internet (T30 hotspot)
    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasAnyInternet = cm.allNetworks.any { network ->
            val c = cm.getNetworkCapabilities(network)
            c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        if (onWifi && !hasAnyInternet) {
            snackbarHostState.showSnackbar(
                "⚠ WiFi không có internet — bản đồ nền có thể không tải được.",
                duration = SnackbarDuration.Long
            )
        }
    }

    // Đảm bảo có Internet: nếu WiFi RTK không có mạng → tự chuyển app sang 4G/5G
    // (kết nối đầu thu không ảnh hưởng — socket rover đã bind tường minh qua WiFi)
    // Sau đó chẩn đoán tile server: tải thử 1 tile, báo lỗi cụ thể nếu thất bại.
    LaunchedEffect(selectedTile) {
        CellularNetworkHelper.ensureInternet(context)?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
        }
        MapTileDiagnostics.probe(selectedTile)?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                // Top bar thu gọn (42dp) — tối đa không gian bản đồ
                CompactTopBar(
                    title    = project?.name ?: "Thu thập điểm",
                    subtitle = "${savedPoints.size} điểm đã lưu",
                    onBack   = onNavigateBack,
                    actions  = {}
                )
                // TabRow thu gọn (34dp, chữ 12sp)
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor   = MaterialTheme.colorScheme.primary,
                    contentColor     = Color.White
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick  = { selectedTab = 0 },
                        modifier = Modifier.height(34.dp)
                    ) { Text("Đo điểm", fontSize = 12.sp) }
                    Tab(
                        selected = selectedTab == 1,
                        onClick  = { selectedTab = 1 },
                        modifier = Modifier.height(34.dp)
                    ) { Text("Danh sách (${savedPoints.size})", fontSize = 12.sp) }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> MapMeasureTab(
                    modifier          = Modifier.fillMaxSize(),
                    gnss              = gnss,
                    savedPoints       = savedPoints,
                    pointCode         = pointCode,
                    note              = note,
                    isSaving          = isSaving,
                    tileSource        = selectedTile,
                    labelConfig       = labelConfig,
                    followGps         = followGps,
                    vectorLayer       = importedLayer,
                    stakeFeatureId    = stakeFeatureId,
                    importedPoints    = importedMapPoints,
                    importedLine      = importedLineGeo,
                    requireFixed      = surveySettings.requireFixed,
                    averagingSession  = averagingSession,
                    isAveraging       = isAveraging,
                    targetEpochs      = targetEpochs,
                    onCodeChange      = viewModel::onPointCodeChange,
                    onNoteChange      = viewModel::onNoteChange,
                    onSave            = viewModel::savePoint,
                    onStartAvg        = viewModel::startAveraging,
                    onStopAvg         = viewModel::stopAveraging,
                    onSaveAvg         = viewModel::saveAveragedPoint,
                    onCancelAvg       = viewModel::cancelAveraging,
                    onSetTargetEpochs = viewModel::setTargetEpochs,
                    onScrolled        = { followGps = false }
                )
                1 -> EnhancedPointListTab(
                    modifier     = Modifier.fillMaxSize(),
                    points       = savedPoints,
                    isExporting  = isExporting,
                    onAdd        = viewModel::addManualPoint,
                    onUpdate     = viewModel::updatePointEdited,
                    onDeleteMany = viewModel::deletePoints,
                    onSwapXY     = viewModel::swapXY,
                    onImportFile = viewModel::importPointsFlexible,
                    onExport     = viewModel::exportPointsFlexible
                )
            }
            // FloatingGnssCard chỉ hiển thị ở tab Danh sách (tab 1) vì map tab có marker riêng
            if (selectedTab == 1) {
                FloatingGnssCard(
                    gnss     = gnss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                )
            }

            // ── Cột icon thao tác dọc bên trái (bán trong suốt) — chỉ ở tab Đo điểm ──
            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        // Follow GPS toggle
                        CompactActionIcon(
                            icon = if (followGps) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                            contentDescription = "Follow GPS",
                            tint = if (followGps) Color(0xFF80FF80) else Color.White
                        ) { followGps = !followGps }
                        // Chọn bản đồ nền
                        Box {
                            CompactActionIcon(Icons.Default.Layers, "Bản đồ nền") { showTileMenu = true }
                            DropdownMenu(expanded = showTileMenu, onDismissRequest = { showTileMenu = false }) {
                                MapTileSource.entries.forEach { source ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(source.label, fontSize = 13.sp, fontWeight = if (source == selectedTile) FontWeight.Bold else FontWeight.Normal)
                                                Text(source.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        onClick = { selectedTile = source; showTileMenu = false }
                                    )
                                }
                            }
                        }
                        // Label visibility toggle
                        Box {
                            CompactActionIcon(
                                icon = if (labelConfig.showPointCode || labelConfig.showElevation)
                                    Icons.Default.Label else Icons.Default.LabelOff,
                                contentDescription = "Nhãn bản đồ"
                            ) { showLabelMenu = true }
                            DropdownMenu(
                                expanded         = showLabelMenu,
                                onDismissRequest = { showLabelMenu = false }
                            ) {
                                listOf(
                                    Triple("Mã điểm",      labelConfig.showPointCode,  { labelConfig = labelConfig.copy(showPointCode  = !labelConfig.showPointCode) }),
                                    Triple("Độ cao (h)",   labelConfig.showElevation,  { labelConfig = labelConfig.copy(showElevation  = !labelConfig.showElevation) }),
                                    Triple("Fix quality",  labelConfig.showFixQuality, { labelConfig = labelConfig.copy(showFixQuality = !labelConfig.showFixQuality) }),
                                    Triple("HDOP",         labelConfig.showHdop,       { labelConfig = labelConfig.copy(showHdop       = !labelConfig.showHdop) }),
                                    Triple("VN-2000 X/Y",  labelConfig.showVn2000,     { labelConfig = labelConfig.copy(showVn2000     = !labelConfig.showVn2000) }),
                                    Triple("Điểm Import",  labelConfig.showImportedPoints, { labelConfig = labelConfig.copy(showImportedPoints = !labelConfig.showImportedPoints) }),
                                    Triple("Tuyến Import", labelConfig.showImportedLine,   { labelConfig = labelConfig.copy(showImportedLine   = !labelConfig.showImportedLine) })
                                ).forEach { (label, checked, toggle) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontSize = 13.sp) },
                                        onClick = { toggle(); showLabelMenu = false },
                                        leadingIcon = {
                                            Checkbox(
                                                checked        = checked,
                                                onCheckedChange = null,
                                                modifier       = Modifier.size(20.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        // Cài đặt thu thập: âm báo + ràng buộc lưu FIXED
                        Box {
                            CompactActionIcon(
                                icon = Icons.Default.Settings,
                                contentDescription = "Cài đặt đo",
                                tint = if (surveySettings.requireFixed) Color(0xFFFFE082) else Color.White
                            ) { showSettingsMenu = true }
                            DropdownMenu(
                                expanded         = showSettingsMenu,
                                onDismissRequest = { showSettingsMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Âm báo khi lưu điểm", fontSize = 13.sp) },
                                    onClick = { viewModel.setSoundEnabled(!surveySettings.soundEnabled) },
                                    leadingIcon = {
                                        Checkbox(
                                            checked = surveySettings.soundEnabled,
                                            onCheckedChange = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Chỉ lưu khi RTK FIXED", fontSize = 13.sp)
                                            Text("Chặn lưu điểm Single/Float/DGPS", fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { viewModel.setRequireFixed(!surveySettings.requireFixed) },
                                    leadingIcon = {
                                        Checkbox(
                                            checked = surveySettings.requireFixed,
                                            onCheckedChange = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }
                        }
                        // Căn chỉnh layer (chỉ hiện khi có layer)
                        if (importedLayer != null) {
                            CompactActionIcon(Icons.Default.Tune, "Căn chỉnh toạ độ", tint = Color(0xFFFFE082)) {
                                showAlignDialog = true
                            }
                        }
                        // Import DXF/SHP
                        IconButton(
                            onClick  = { importFileLauncher.launch(arrayOf("*/*")) },
                            enabled  = !isImporting,
                            modifier = Modifier.size(38.dp)
                        ) {
                            if (isImporting) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(Icons.Default.FolderOpen, "Import DXF/SHP", Modifier.size(19.dp), tint = Color.White)
                        }
                        // Export
                        IconButton(
                            onClick  = { showExportDialog = true },
                            enabled  = !isExporting,
                            modifier = Modifier.size(38.dp)
                        ) {
                            if (isExporting) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(Icons.Default.FileDownload, "Xuất dữ liệu", Modifier.size(19.dp), tint = Color.White)
                        }
                }
            }
        }
    }

    // ── Dialog xác nhận xoá ──────────────────────────────────
    deleteTarget?.let { point ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title  = { Text("Xoá điểm?") },
            text   = { Text("Điểm \"${point.pointCode}\" sẽ bị xoá vĩnh viễn.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePoint(point)
                    deleteTarget = null
                }) {
                    Text("Xoá", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Huỷ") }
            }
        )
    }

    // ── Export format dialog ─────────────────────────────────
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Xuất dữ liệu") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Chọn định dạng file xuất:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ExportManager.Format.entries.forEach { format ->
                        OutlinedButton(
                            onClick = {
                                showExportDialog = false
                                viewModel.exportPoints(format)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(format.label, fontWeight = FontWeight.Medium)
                                Text(
                                    ".${format.extension}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Tuỳ chỉnh: thứ tự trường + dấu phân cách + .csv/.txt
                    OutlinedButton(
                        onClick = {
                            showExportDialog = false
                            showCustomExport = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Tuỳ chỉnh định dạng…", fontWeight = FontWeight.Medium)
                            Text(
                                "Thứ tự trường (P,N,E,H,Code…) + dấu phân cách + .csv/.txt",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Huỷ") }
            }
        )
    }

    // ── Export tuỳ chỉnh (PointFormatDialog) ─────────────────
    if (showCustomExport) {
        PointFormatDialog(
            title         = "Định dạng file Export",
            showExtension = true,
            showHeader    = true,
            onConfirm     = { fmt ->
                showCustomExport = false
                viewModel.exportPointsFlexible(fmt)
            },
            onDismiss = { showCustomExport = false }
        )
    }

    // ── Dialog căn chỉnh toạ độ vector layer ─────────────────
    val layerForAlign = importedLayer
    if (showAlignDialog && layerForAlign != null) {
        CoordAlignDialog(
            layer     = layerForAlign,
            onDismiss = { showAlignDialog = false },
            onApply   = { newLayer ->
                viewModel.setSharedVectorLayer(newLayer)
                showAlignDialog = false
            }
        )
    }
}

// ════════════════════════════════════════════════════════════
// Tab 0 — Đo điểm với bản đồ nền
// ════════════════════════════════════════════════════════════

/**
 * MapMeasureTab — Tab đo điểm với bản đồ OSM làm nền.
 *
 * Layout:
 *   - Tầng dưới (background): GnssMapView toàn màn hình
 *   - Tầng trên (overlay):
 *     • Fix status chip (góc trên trái)
 *     • Form nhập điểm — card bán trong suốt phía dưới (có thể thu gọn)
 */
@Composable
private fun MapMeasureTab(
    modifier          : Modifier,
    gnss              : GnssStatus,
    savedPoints       : List<SurveyPoint>,
    pointCode         : String,
    note              : String,
    isSaving          : Boolean,
    tileSource        : MapTileSource = MapTileSource.GOOGLE_NORMAL,
    labelConfig       : MapLabelConfig,
    followGps         : Boolean,
    vectorLayer       : VectorLayerImporter.VectorLayer? = null,
    /** Feature CAD đang stakeout — highlight + nhãn số hiệu đỉnh */
    stakeFeatureId    : Int? = null,
    importedPoints    : List<SurveyPoint> = emptyList(),
    importedLine      : List<org.osmdroid.util.GeoPoint>? = null,
    /** Chỉ cho lưu khi đạt RTK FIXED — đổi trạng thái nút Lưu */
    requireFixed      : Boolean = false,
    averagingSession  : AveragingSession? = null,
    isAveraging       : Boolean = false,
    targetEpochs      : Int = 30,
    onCodeChange      : (String) -> Unit,
    onNoteChange      : (String) -> Unit,
    onSave            : () -> Unit,
    onStartAvg        : () -> Unit = {},
    onStopAvg         : () -> Unit = {},
    onSaveAvg         : () -> Unit = {},
    onCancelAvg       : () -> Unit = {},
    onSetTargetEpochs : (Int) -> Unit = {},
    onScrolled        : () -> Unit
) {
    var formExpanded by remember { mutableStateOf(true) }
    var showAvgMode  by remember { mutableStateOf(false) }
    // Gộp điểm Import + tuyến Import vào bản đồ theo công tắc ẩn/hiện
    val mapPoints = if (labelConfig.showImportedPoints) savedPoints + importedPoints else savedPoints
    val mapLine   = if (labelConfig.showImportedLine) importedLine else null

    Box(modifier = modifier) {
        // ── Map toàn màn hình ────────────────────────────
        GnssMapView(
            modifier    = Modifier.fillMaxSize(),
            gnss        = gnss,
            points      = mapPoints,
            designLine  = mapLine,
            followGps   = followGps,
            tileSource  = tileSource,
            labelConfig = labelConfig,
            vectorLayer = vectorLayer,
            highlightFeatureId = stakeFeatureId,
            zoomControlsBottomPadding = 180.dp,
            onScrolled  = onScrolled
        )

        // ── Fix status chip (góc trên trái) ─────────────
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            color    = Color(android.graphics.Color.parseColor(gnss.fixColorHex)).copy(alpha = 0.9f),
            shape    = MaterialTheme.shapes.small
        ) {
            Text(
                "${gnss.fixLabel}  •  ${gnss.satelliteCount} sv  •  HDOP ${"%.1f".format(gnss.hdop)}",
                modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color      = Color.White,
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // ── Form nhập điểm — bottom overlay ─────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color  = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
            tonalElevation = 8.dp,
            shape  = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Handle để thu gọn/mở rộng
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .clickable { formExpanded = !formExpanded },
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        modifier = Modifier.size(width = 40.dp, height = 4.dp),
                        color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        shape    = MaterialTheme.shapes.extraSmall
                    ) {}
                }

                if (formExpanded) {
                    Spacer(Modifier.height(8.dp))

                    // VN-2000 hiển thị
                    if (gnss.vn2000 != null) {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("X: ${"%.3f".format(gnss.vn2000!!.northing)} m",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("Y: ${"%.3f".format(gnss.vn2000!!.easting)} m",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Text("h: ${"%.3f".format(gnss.altitude)} m",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    // Mã điểm
                    OutlinedTextField(
                        value         = pointCode,
                        onValueChange = onCodeChange,
                        label         = { Text("Mã điểm") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))

                    // Ghi chú
                    OutlinedTextField(
                        value         = note,
                        onValueChange = onNoteChange,
                        label         = { Text("Ghi chú") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    // ── Nút hành động: Đơn / Trung bình ─────────
                    if (!showAvgMode) {
                        // Chế độ đo đơn
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Khi bật "chỉ FIXED" mà chưa đạt FIXED → khoá nút lưu
                            val blockedByFix = requireFixed && gnss.fixQuality != 4
                            Button(
                                onClick  = onSave,
                                enabled  = gnss.hasFix && !isSaving && !blockedByFix,
                                modifier = Modifier.weight(1f),
                                colors   = ButtonDefaults.buttonColors(
                                    containerColor = when (gnss.fixQuality) {
                                        4 -> Color(0xFF2E7D32)
                                        5 -> Color(0xFF558B2F)
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            ) {
                                if (isSaving) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    when {
                                        !gnss.hasFix  -> "Chưa có GPS"
                                        blockedByFix  -> "Chờ FIXED (${gnss.fixLabel})"
                                        else          -> "Lưu (${gnss.fixLabel})"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                            OutlinedButton(
                                onClick  = { showAvgMode = true },
                                enabled  = gnss.hasFix,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Trung bình", fontSize = 12.sp)
                            }
                        }
                    } else {
                        // Chế độ trung bình hoá
                        AveragingPanel(
                            session          = averagingSession,
                            isAveraging      = isAveraging,
                            targetEpochs     = targetEpochs,
                            isSaving         = isSaving,
                            onStart          = onStartAvg,
                            onStop           = onStopAvg,
                            onSave           = { onSaveAvg(); showAvgMode = false },
                            onCancel         = { onCancelAvg(); showAvgMode = false },
                            onSetTarget      = onSetTargetEpochs
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// AveragingPanel — UI trung bình hoá epoch
// ════════════════════════════════════════════════════════════

@Composable
private fun AveragingPanel(
    session      : AveragingSession?,
    isAveraging  : Boolean,
    targetEpochs : Int,
    isSaving     : Boolean,
    onStart      : () -> Unit,
    onStop       : () -> Unit,
    onSave       : () -> Unit,
    onCancel     : () -> Unit,
    onSetTarget  : (Int) -> Unit
) {
    val count   = session?.count ?: 0
    val rmse    = session?.rmse2DFormatted ?: "-"
    val fixLabel = session?.let {
        when (it.dominantFixQuality) { 4 -> "Fixed"; 5 -> "Float"; else -> "Single" }
    } ?: "-"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                "Trung bình hoá epoch",
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.primary
            )
            // Chọn số epoch mục tiêu
            if (!isAveraging && session == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onSetTarget(targetEpochs - 10) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("−", fontWeight = FontWeight.Bold) }
                    Text("$targetEpochs ep", style = MaterialTheme.typography.bodySmall)
                    IconButton(
                        onClick = { onSetTarget(targetEpochs + 10) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("+", fontWeight = FontWeight.Bold) }
                }
            }
        }

        // Progress bar
        if (session != null || isAveraging) {
            LinearProgressIndicator(
                progress = { if (targetEpochs > 0) session?.progress ?: 0f else 0f },
                modifier = Modifier.fillMaxWidth(),
                color    = when (session?.dominantFixQuality) {
                    4 -> Color(0xFF2E7D32)
                    5 -> Color(0xFF558B2F)
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            // Thống kê live
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("$count/${targetEpochs} epoch", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("RMSE: $rmse", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(fixLabel, style = MaterialTheme.typography.bodySmall, color = when (session?.dominantFixQuality) {
                    4 -> Color(0xFF2E7D32); 5 -> Color(0xFF558B2F); else -> Color(0xFFF57F17)
                })
            }
            // Giá trị trung bình live
            session?.let { s ->
                if (s.meanNorthing != 0.0) {
                    Text(
                        "X: ${"%.3f".format(s.meanNorthing)}  Y: ${"%.3f".format(s.meanEasting)}  h: ${"%.3f".format(s.meanAltitude)}m",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Nút điều khiển
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!isAveraging && session == null) {
                // Chưa bắt đầu
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Bắt đầu đo", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(0.5f)) {
                    Text("Huỷ", fontSize = 12.sp)
                }
            } else if (isAveraging) {
                // Đang thu mẫu
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                ) {
                    Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Dừng ($count ep)", fontSize = 12.sp)
                }
            } else if (session != null && !isAveraging) {
                // Đã xong — có thể lưu hoặc đo lại
                Button(
                    onClick  = onSave,
                    enabled  = !isSaving && session.count >= 2,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Lưu TB", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Đo lại", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(0.5f)) {
                    Text("Huỷ", fontSize = 12.sp)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// Tab 0 — Đo điểm (cũ — giữ lại cho tham chiếu)
// ════════════════════════════════════════════════════════════

@Composable
private fun SurveyTab(
    modifier     : Modifier,
    gnss         : GnssStatus,
    pointCode    : String,
    note         : String,
    isSaving     : Boolean,
    onCodeChange : (String) -> Unit,
    onNoteChange : (String) -> Unit,
    onSave       : () -> Unit
) {
    Column(
        modifier            = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Fix Quality Banner ─────────────────────────────
        FixQualityBanner(gnss)

        // ── Toạ độ live ───────────────────────────────────
        LiveCoordinateCard(gnss)

        // ── Form lưu điểm ─────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier            = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Thông tin điểm đo",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value         = pointCode,
                    onValueChange = onCodeChange,
                    label         = { Text("Mã điểm") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    supportingText = { Text("Tự động tăng sau mỗi lần lưu") }
                )

                OutlinedTextField(
                    value         = note,
                    onValueChange = onNoteChange,
                    label         = { Text("Ghi chú (tuỳ chọn)") },
                    maxLines      = 3,
                    modifier      = Modifier.fillMaxWidth()
                )

                Button(
                    onClick  = onSave,
                    enabled  = gnss.hasFix && !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (gnss.isRtk)
                            Color(0xFF1B5E20) // RTK → xanh đậm
                        else
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color    = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Đang lưu...")
                    } else {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (gnss.hasFix) "Lưu điểm"
                            else "Chưa có tín hiệu",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ════════════════════════════════════════════════════════════
// Fix Quality Banner
// ════════════════════════════════════════════════════════════

@Composable
private fun FixQualityBanner(gnss: GnssStatus) {
    val bgColor = try {
        Color(AndroidColor.parseColor(gnss.fixColorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.error
    }

    Surface(
        modifier  = Modifier.fillMaxWidth(),
        color     = bgColor,
        shape     = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                gnss.fixLabel,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize   = 18.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusBadge("${gnss.satelliteCount} vệ tinh")
                StatusBadge("HDOP ${"%.1f".format(gnss.hdop)}")
                StatusBadge(gnss.localTime)   // UTC+7 (Hà Nội)
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Text(
        text     = text,
        color    = Color.White.copy(alpha = 0.9f),
        fontSize = 12.sp,
        style    = MaterialTheme.typography.labelMedium
    )
}

// ════════════════════════════════════════════════════════════
// Live Coordinate Card — toggle WGS-84 ↔ VN-2000
// ════════════════════════════════════════════════════════════

/**
 * Card tọa độ live có thể toggle giữa WGS-84 và VN-2000.
 * Nhấn vào header/card để chuyển hệ tọa độ → bớt chiếm diện tích màn hình.
 */
@Composable
private fun LiveCoordinateCard(gnss: GnssStatus) {
    var showWgs by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showWgs = !showWgs }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header có mũi tên đổi chiều ──────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text       = if (showWgs) "WGS-84" else "VN-2000 — ${gnss.vn2000?.zoneName ?: ""}",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                // Gợi ý toggle
                Text(
                    text  = if (showWgs) "▼ VN-2000" else "▼ WGS-84",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(8.dp))

            if (showWgs) {
                // ── WGS-84 ────────────────────────────────────
                CoordRow("φ (Vĩ độ)",   "%.8f°".format(gnss.latitude))
                CoordRow("λ (Kinh độ)", "%.8f°".format(gnss.longitude))
                CoordRow("h (Độ cao)",  "%.3f m".format(gnss.altitude) +
                        if (gnss.geoidSeparation != 0.0)
                            "  [N = %.2f]".format(gnss.geoidSeparation) else "")
            } else {
                // ── VN-2000 ───────────────────────────────────
                gnss.vn2000?.let { vn ->
                    CoordRow("X (Northing)", vn.northingFormatted)
                    CoordRow("Y (Easting)",  vn.eastingFormatted)
                    CoordRow("h (Độ cao)",   "%.3f m".format(gnss.altitude))
                } ?: Text(
                    "VN-2000: chờ tín hiệu GPS...",
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
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style      = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

// ════════════════════════════════════════════════════════════
// Tab 1 — Danh sách điểm đo
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PointListTab(
    modifier    : Modifier,
    points      : List<SurveyPoint>,
    onLongPress : (SurveyPoint) -> Unit
) {
    // ── Filter state ──────────────────────────────────────────
    // null = hiện tất cả; Int = chỉ hiện fixQuality == filter
    var fixFilter by remember { mutableStateOf<Int?>(null) }

    val displayed = remember(points, fixFilter) {
        if (fixFilter == null) points else points.filter { it.fixQuality == fixFilter }
    }

    // ── Thống kê ─────────────────────────────────────────────
    val totalFixed  = remember(points) { points.count { it.fixQuality == 4 } }
    val totalFloat  = remember(points) { points.count { it.fixQuality == 5 } }
    val totalSingle = remember(points) { points.count { it.fixQuality <= 2 } }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Summary bar ───────────────────────────────────────
        if (points.isNotEmpty()) {
            Surface(
                color  = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatChip(
                        label   = "Fixed",
                        count   = totalFixed,
                        color   = Color(0xFF2E7D32),
                        selected = fixFilter == 4,
                        onClick  = { fixFilter = if (fixFilter == 4) null else 4 }
                    )
                    StatChip(
                        label   = "Float",
                        count   = totalFloat,
                        color   = Color(0xFF558B2F),
                        selected = fixFilter == 5,
                        onClick  = { fixFilter = if (fixFilter == 5) null else 5 }
                    )
                    StatChip(
                        label   = "Single",
                        count   = totalSingle,
                        color   = Color(0xFFF57F17),
                        selected = fixFilter == 1,
                        onClick  = { fixFilter = if (fixFilter == 1) null else 1 }
                    )
                    StatChip(
                        label   = "Tất cả",
                        count   = points.size,
                        color   = MaterialTheme.colorScheme.primary,
                        selected = fixFilter == null,
                        onClick  = { fixFilter = null }
                    )
                }
            }
        }

        if (points.isEmpty()) {
            Box(
                modifier         = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Chưa có điểm nào được lưu",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Chuyển sang tab \"Đo điểm\" để bắt đầu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
            return
        }

        LazyColumn(
            modifier            = Modifier.fillMaxSize(),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    if (fixFilter == null) "${points.size} điểm đo"
                    else "${displayed.size}/${points.size} điểm (đã lọc)",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(displayed, key = { it.id }) { point ->
                SurveyPointCard(
                    point       = point,
                    onLongClick = { onLongPress(point) }
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun StatChip(
    label    : String,
    count    : Int,
    color    : Color,
    selected : Boolean,
    onClick  : () -> Unit
) {
    Surface(
        onClick      = onClick,
        shape        = RoundedCornerShape(8.dp),
        color        = if (selected) color.copy(alpha = 0.15f)
                       else MaterialTheme.colorScheme.surface,
        border       = BorderStroke(
            width = if (selected) 1.5.dp else 0.5.dp,
            color = if (selected) color else color.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier              = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment   = Alignment.CenterHorizontally
        ) {
            Text(
                count.toString(),
                style      = MaterialTheme.typography.titleSmall,
                color      = color,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SurveyPointCard(
    point       : SurveyPoint,
    onLongClick : () -> Unit
) {
    val fixBgColor = try {
        Color(AndroidColor.parseColor(point.fixColorHex))
    } catch (e: Exception) {
        Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick     = { /* tương lai: xem chi tiết */ },
                onLongClick = onLongClick
            ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mã điểm
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    point.pointCode,
                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Toạ độ VN-2000
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "X: ${"%.3f".format(point.northing)}",
                    fontSize   = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Y: ${"%.3f".format(point.easting)}",
                    fontSize   = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    point.timestampFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Fix badge + delete hint
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    color = fixBgColor,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        point.fixLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 9.sp,
                        color    = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "HDOP ${"%.1f".format(point.hdop)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
