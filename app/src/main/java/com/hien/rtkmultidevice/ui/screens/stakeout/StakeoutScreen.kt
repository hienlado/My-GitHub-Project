package com.hien.rtkmultidevice.ui.screens.stakeout

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.core.network.CellularNetworkHelper
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import com.hien.rtkmultidevice.ui.components.CompactActionIcon
import com.hien.rtkmultidevice.ui.components.CompactTopBar
import com.hien.rtkmultidevice.ui.components.FloatingGnssCard
import com.hien.rtkmultidevice.ui.components.rememberDeviceHeading
import com.hien.rtkmultidevice.ui.screens.map.CoordAlignDialog
import com.hien.rtkmultidevice.ui.screens.map.GnssMapView
import com.hien.rtkmultidevice.ui.screens.map.MapLabelConfig
import com.hien.rtkmultidevice.ui.screens.map.MapTileDiagnostics
import com.hien.rtkmultidevice.ui.screens.map.MapTileSource
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
import com.hien.rtkmultidevice.ui.screens.map.VertexTableDialog
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * StakeoutScreen — Màn hình cắm mốc (Stakeout / Stake-out).
 *
 * Luồng sử dụng:
 *   1. User nhập toạ độ điểm thiết kế (X Northing, Y Easting VN-2000)
 *      hoặc chọn từ danh sách điểm đã đo
 *   2. App tính liên tục: khoảng cách + azimuth hướng đến điểm thiết kế
 *   3. Mũi tên trên màn hình quay về hướng cần đi
 *   4. Bảng offset ΔX/ΔY giúp dẫn hướng chính xác
 *   5. Khi khoảng cách ≤ acceptRadius → hiệu ứng "Đã đến nơi"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StakeoutScreen(
    projectId      : Int = -1,
    onNavigateBack : () -> Unit,
    viewModel      : StakeoutViewModel = hiltViewModel()
) {
    val result        by viewModel.stakeoutResult.collectAsStateWithLifecycle()
    val gnssStatus    by viewModel.gnssStatus.collectAsStateWithLifecycle()
    val targetN       by viewModel.targetNorthing.collectAsStateWithLifecycle()
    val targetE       by viewModel.targetEasting.collectAsStateWithLifecycle()
    val targetName    by viewModel.targetName.collectAsStateWithLifecycle()
    val targetLine    by viewModel.targetLine.collectAsStateWithLifecycle()
    val acceptR       by viewModel.acceptRadius.collectAsStateWithLifecycle()
    val savedPoints   by viewModel.savedPoints.collectAsStateWithLifecycle()
    val importedLayer by viewModel.importedLayer.collectAsStateWithLifecycle()
    val error         by viewModel.error.collectAsStateWithLifecycle()
    val activeStakeFeatureId by viewModel.activeStakeFeatureId.collectAsStateWithLifecycle()

    var showPointPicker  by remember { mutableStateOf(false) }
    var pickerLineMode   by remember { mutableStateOf(false) }
    // Vào từ thẻ "Định vị tuyến" (Khảo sát/Đo tuyến) → tự mở picker chế độ tuyến
    LaunchedEffect(Unit) {
        if (StakeoutEntryFlags.consumeOpenLinePicker()) { pickerLineMode = true; showPointPicker = true }
    }
    var showLabelMenu    by remember { mutableStateOf(false) }
    var showTileMenu     by remember { mutableStateOf(false) }
    var showAlignDialog  by remember { mutableStateOf(false) }
    // Dialog hành động khi chạm đối tượng vector (Định vị điểm / tuyến / bảng đỉnh)
    var actionFeature    by remember { mutableStateOf<VectorLayerImporter.VectorFeature?>(null) }
    var actionIdx        by remember { mutableIntStateOf(0) }
    // Bảng toạ độ đỉnh — mở từ dialog hành động
    var vertexFeature    by remember { mutableStateOf<VectorLayerImporter.VectorFeature?>(null) }
    var vertexIdx        by remember { mutableIntStateOf(0) }
    var selectedTile     by remember { mutableStateOf(MapTileSource.GOOGLE_NORMAL) }   // mặc định Google Maps
    var labelConfig      by remember { mutableStateOf(MapLabelConfig(showPointCode = true)) }
    var followGps        by remember { mutableStateOf(true) }
    var targetCollapsed  by remember { mutableStateOf(false) }   // thu gọn form nhập điểm
    var acceptRText      by remember { mutableStateOf("%.2f".format(acceptR)) }
    // Vị trí la bàn — kéo thả tự do trên màn hình đồ hoạ
    var compassOffset    by remember { mutableStateOf(Offset.Zero) }
    val context          = LocalContext.current
    val deviceHeading    = rememberDeviceHeading(context)
    val beeper           = rememberStakeoutBeeper(context)
    val beeperScope      = rememberCoroutineScope()

    // Âm báo dẫn hướng: chỉ 2 trường hợp — (1) đến gần mục tiêu,
    // (2) đã vào trong phạm vi dung sai (bán kính chấp nhận).
    LaunchedEffect(result) {
        val r = result
        if (r is StakeoutResult.Active) {
            beeper.updateDistance(r.distanceM, r.acceptRadius, beeperScope)
        } else {
            beeper.stop()
        }
    }
    val snackbarHost     = remember { SnackbarHostState() }

    // ── Target GeoPoint để vẽ "sợi tóc" + marker mục tiêu trên bản đồ ──
    // FIX BUG sợi tóc chỉ sai hướng ~300km (sang Cambodia): trước đây dùng
    // công thức xấp xỉ với KTT cứng 105° — sai 2.75° kinh độ khi đo ở múi
    // 107°45'. Nay dùng nghịch đảo Gauss-Krüger CHÍNH XÁC với đúng KTT.
    // Chế độ tuyến: target = CHÂN VUÔNG GÓC trên tuyến (cập nhật theo vị trí).
    val targetGeoPoint = remember(result, targetN, targetE, gnssStatus.vn2000?.centralMeridian) {
        val cm = gnssStatus.vn2000?.centralMeridian?.takeIf { it > 0.0 }
            ?: com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.DEFAULT_CM
        val r = result
        if (r is StakeoutResult.Active) {
            // Active: target N/E chính xác (điểm thiết kế hoặc chân vuông góc tuyến)
            com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
                .inverseVn2000(r.targetNorthing, r.targetEasting, cm)
        } else {
            val n = targetN.replace(" ", "").replace(",", "").toDoubleOrNull()
            val e = targetE.replace(" ", "").replace(",", "").toDoubleOrNull()
            if (n != null && e != null && n > 1000 && e > 1000)
                com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.inverseVn2000(n, e, cm)
            else null
        }
    }

    // File picker để import CSV thiết kế
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importPointsFromCsv(it) } }

    // Tải điểm đã lưu nếu có projectId
    LaunchedEffect(projectId) {
        if (projectId > 0) viewModel.loadSavedPointsForProject(projectId)
    }

    LaunchedEffect(error) {
        error?.let { snackbarHostState ->
            snackbarHost.showSnackbar(snackbarHostState)
            viewModel.clearError()
        }
    }

    // Đảm bảo có Internet: nếu WiFi RTK không có mạng → tự chuyển app sang 4G/5G
    // Sau đó chẩn đoán tile server: tải thử 1 tile, báo lỗi cụ thể nếu thất bại.
    LaunchedEffect(selectedTile) {
        CellularNetworkHelper.ensureInternet(context)?.let {
            snackbarHost.showSnackbar(it, duration = SnackbarDuration.Long)
        }
        MapTileDiagnostics.probe(selectedTile)?.let {
            snackbarHost.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            // Top bar thu gọn (42dp) — tối đa không gian bản đồ
            CompactTopBar(
                title    = "Cắm mốc (Stakeout)",
                subtitle = when {
                    targetLine != null        -> "⊥ Tuyến: ${targetLine!!.name} (${targetLine!!.vertices.size} đỉnh)"
                    targetName.isNotEmpty()   -> "→ $targetName"
                    else                      -> "Chưa chọn điểm thiết kế"
                },
                onBack   = onNavigateBack,
                actions  = {}
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

        // ── Bản đồ nền toàn màn hình ─────────────────────
        GnssMapView(
            modifier    = Modifier.fillMaxSize(),
            gnss        = gnssStatus,
            points      = savedPoints,
            followGps   = followGps,
            tileSource  = selectedTile,
            labelConfig = labelConfig,
            targetPoint = targetGeoPoint,
            targetLabel = targetName,
            vectorLayer = importedLayer,
            // Highlight: tuyến đang định vị, hoặc đối tượng đang chọn trong dialog
            highlightFeatureId = targetLine?.featureId ?: actionFeature?.id ?: activeStakeFeatureId,
            zoomControlsBottomPadding = if (targetCollapsed) 96.dp else 340.dp,
            // Chạm node hoặc đường vector (DXF/SHP) → dialog Định vị điểm/tuyến
            onVectorNodeTap = { feature, vidx ->
                actionFeature = feature
                actionIdx     = vidx
            },
            onScrolled  = { followGps = false }
        )

        // ── Cột icon thao tác dọc bên trái (bán trong suốt) ──
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                    // GPS Follow toggle
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
                    // Label toggle
                    Box {
                        CompactActionIcon(Icons.Default.Label, "Nhãn bản đồ") { showLabelMenu = true }
                        DropdownMenu(expanded = showLabelMenu, onDismissRequest = { showLabelMenu = false }) {
                            listOf(
                                "Mã điểm"    to { labelConfig = labelConfig.copy(showPointCode  = !labelConfig.showPointCode) },
                                "Độ cao (h)" to { labelConfig = labelConfig.copy(showElevation  = !labelConfig.showElevation) },
                                "VN-2000 X/Y" to { labelConfig = labelConfig.copy(showVn2000 = !labelConfig.showVn2000) }
                            ).forEach { (label, toggle) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 13.sp) },
                                    onClick = { toggle(); showLabelMenu = false }
                                )
                            }
                        }
                    }
                    // Căn chỉnh toạ độ layer (khi đã import DXF/SHP)
                    if (importedLayer != null) {
                        CompactActionIcon(Icons.Default.Tune, "Căn chỉnh toạ độ", tint = Color(0xFFFFE082)) {
                            showAlignDialog = true
                        }
                    }
                    // Import CSV/DXF/SHP
                    CompactActionIcon(Icons.Default.FileOpen, "Import file điểm") {
                        importLauncher.launch(arrayOf("*/*"))
                    }
                    // Công cụ COGO — chọn điểm từ dữ liệu (khảo sát + import); kết quả gửi sang cắm mốc
                    com.hien.rtkmultidevice.ui.screens.map.CogoButton(
                        points = savedPoints
                            .filter { it.northing != 0.0 || it.easting != 0.0 }
                            .map { com.hien.rtkmultidevice.ui.screens.map.CogoPoint(it.pointCode, it.northing, it.easting) },
                        onStakeout = { name, n, e -> viewModel.setManualTarget(name, n, e) }
                    )
                    if (savedPoints.isNotEmpty()) {
                        CompactActionIcon(Icons.Default.List, "Chọn điểm") { showPointPicker = true }
                        // Điều hướng loạt điểm cắm mốc: gần nhất / trước / kế
                        CompactActionIcon(Icons.Default.NearMe, "Điểm gần nhất", onClick = viewModel::stakeNearest)
                        CompactActionIcon(Icons.Default.ChevronLeft, "Điểm trước", onClick = viewModel::stakePrev)
                        CompactActionIcon(Icons.Default.ChevronRight, "Điểm kế", onClick = viewModel::stakeNext)
                    }
                    if (targetN.isNotEmpty() || targetE.isNotEmpty() || targetLine != null) {
                        CompactActionIcon(Icons.Default.Clear, "Xoá target", onClick = viewModel::clearTarget)
                    }
        }

        // ── Badge layer vector (chạm để xoá layer) ────────
        importedLayer?.let { layer ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 56.dp)
                    .clickable { viewModel.clearImportedLayer() },
                color = Color(0xFF7B1FA2).copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "📄 ${layer.name.take(16)}  ×  ${layer.featureCount} obj",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 11.sp
                )
            }
        }

        // ═══════════════════════════════════════════════════
        // OVERLAY ZONE — tất cả đều bán trong suốt trên map
        // ═══════════════════════════════════════════════════

        // ── La bàn điều hướng — giữa màn hình ─────────────
        when (val r = result) {
            is StakeoutResult.NoTarget     -> { /* nothing — hint ở bottom */ }
            is StakeoutResult.NoGnss       -> { /* nothing */ }
            is StakeoutResult.InvalidCoord -> { /* nothing */ }
            is StakeoutResult.Active -> {
                if (r.arrived) {
                    // Đã đến nơi — hiển thị ở giữa
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        ArrivedBanner(r)
                    }
                } else {
                    // La bàn + khoảng cách — KÉO THẢ tự do đến vị trí bất kỳ
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    compassOffset.x.toInt(), compassOffset.y.toInt()
                                )
                            }
                            .padding(end = 8.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    compassOffset += dragAmount
                                }
                            }
                    ) {
                        CompassCardCompact(r, deviceHeading)
                    }
                }
            }
        }

        // ── Form điểm thiết kế + Hint — phía dưới ─────────
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color          = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            tonalElevation = 0.dp,
            shape          = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {

                // Handle thu gọn / mở rộng
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .clickable { targetCollapsed = !targetCollapsed },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        if (targetName.isNotEmpty()) "→ $targetName"
                        else "Điểm thiết kế",
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        if (targetCollapsed) Icons.Default.KeyboardArrowUp
                        else                 Icons.Default.KeyboardArrowDown,
                        contentDescription = "Thu gọn/mở rộng",
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (!targetCollapsed) {
                    Spacer(Modifier.height(4.dp))

                    // ── Banner định vị tuyến ────────────────────
                    val line = targetLine
                    if (line != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color    = MaterialTheme.colorScheme.secondaryContainer,
                            shape    = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Định vị TUYẾN — khoảng cách vuông góc",
                                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        "${line.name} • ${line.vertices.size} đỉnh",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                TextButton(onClick = viewModel::clearTarget) { Text("Bỏ tuyến") }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        TargetInputCard(
                            northing         = targetN,
                            easting          = targetE,
                            name             = targetName,
                            acceptRadius     = acceptRText,
                            onNorthingChange = viewModel::onTargetNorthingChange,
                            onEastingChange  = viewModel::onTargetEastingChange,
                            onNameChange     = viewModel::onTargetNameChange,
                            onRadiusChange   = { s -> acceptRText = s; viewModel.onAcceptRadiusChange(s) }
                        )
                    }

                    // Hint khi chưa có target/gnss
                    val currentResult = result
                    when (currentResult) {
                        is StakeoutResult.NoTarget     -> NoTargetHint()
                        is StakeoutResult.NoGnss       -> NoGnssHint()
                        is StakeoutResult.InvalidCoord -> InvalidCoordHint()
                        is StakeoutResult.Active       -> {
                            if (!currentResult.arrived) OffsetCard(currentResult)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // ── Floating GNSS card — overlay kéo được ──────────
        FloatingGnssCard(
            gnss     = gnssStatus,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
        )
        } // end Box
    }

    // ── Dialog chọn điểm đã lưu ──────────────────────────────
    if (showPointPicker) {
        PointPickerDialog(
            points    = savedPoints,
            startInLineMode = pickerLineMode,
            onSelect  = { point ->
                viewModel.selectSavedPoint(point)
                showPointPicker = false
            },
            onSelectLine = { name, p1, p2 ->
                // Định vị TUYẾN từ 2 điểm import/đã lưu (A→B)
                viewModel.setLineTarget(
                    name,
                    listOf(
                        Pair(p1.northing, p1.easting),
                        Pair(p2.northing, p2.easting)
                    )
                )
                showPointPicker = false
                pickerLineMode = false
                beeperScope.launch {
                    snackbarHost.showSnackbar("✓ Định vị tuyến $name — khoảng cách trực giao tới đường thẳng")
                }
            },
            onDismiss = { showPointPicker = false; pickerLineMode = false }
        )
    }

    // ── Dialog căn chỉnh toạ độ layer vector ─────────────────
    val layerForAlign = importedLayer
    if (showAlignDialog && layerForAlign != null) {
        CoordAlignDialog(
            layer     = layerForAlign,
            onDismiss = { showAlignDialog = false },
            onApply   = { newLayer ->
                viewModel.applyAlignedLayer(newLayer)
                showAlignDialog = false
            }
        )
    }

    // ── Dialog hành động: Định vị điểm / Định vị tuyến / Bảng đỉnh ──
    actionFeature?.let { af ->
        FeatureStakeoutDialog(
            feature   = af,
            vertexIdx = actionIdx,
            onPickPoint = { label, n, e ->
                actionFeature = null
                viewModel.setManualTarget(label, n, e, af.id)
                // KHÔNG bật followGps — giữ nguyên khung nhìn/zoom hiện tại
                beeperScope.launch {
                    snackbarHost.showSnackbar(
                        "✓ Định vị điểm: $label  X=%.3f  Y=%.3f".format(java.util.Locale.US, n, e)
                    )
                }
            },
            onPickLine = {
                actionFeature = null
                val name = af.label.ifEmpty { "VEC-${af.id}" }
                // rawPoints (E, N) → vertices (N, E); featureId để highlight tuyến
                viewModel.setLineTarget(name, af.rawPoints.map { Pair(it.second, it.first) }, af.id)
                // KHÔNG bật followGps — giữ nguyên khung nhìn/zoom hiện tại
                beeperScope.launch {
                    snackbarHost.showSnackbar("✓ Định vị tuyến: $name — hiển thị khoảng cách vuông góc đến tuyến")
                }
            },
            onOpenTable = {
                vertexFeature = af
                vertexIdx     = actionIdx
                actionFeature = null
            },
            onDismiss = { actionFeature = null }
        )
    }

    // ── Bảng toạ độ đỉnh — chọn đỉnh của line/shape để cắm mốc ──
    vertexFeature?.let { vf ->
        val vn = gnssStatus.vn2000
        VertexTableDialog(
            feature         = vf,
            highlightIdx    = vertexIdx,
            currentNorthing = vn?.northing,
            currentEasting  = vn?.easting,
            onPick          = { label, n, e ->
                vertexFeature = null
                viewModel.setManualTarget(label, n, e, vf.id)
                // KHÔNG bật followGps — giữ nguyên khung nhìn/zoom hiện tại
                beeperScope.launch {
                    snackbarHost.showSnackbar(
                        "✓ Mục tiêu: $label  X=%.3f  Y=%.3f".format(java.util.Locale.US, n, e)
                    )
                }
            },
            onDismiss = { vertexFeature = null }
        )
    }
}

// ════════════════════════════════════════════════════════════
// FeatureStakeoutDialog — Chạm đối tượng vector → chọn cách định vị
// ════════════════════════════════════════════════════════════

@Composable
private fun FeatureStakeoutDialog(
    feature     : VectorLayerImporter.VectorFeature,
    vertexIdx   : Int,
    onPickPoint : (label: String, northing: Double, easting: Double) -> Unit,
    onPickLine  : () -> Unit,
    onOpenTable : () -> Unit,
    onDismiss   : () -> Unit
) {
    val baseLbl = feature.label.ifEmpty { "VEC-${feature.id}" }
    val typeLabel = when (feature.type) {
        VectorLayerImporter.FeatureType.POINT    -> "Điểm"
        VectorLayerImporter.FeatureType.POLYLINE -> "Đường"
        VectorLayerImporter.FeatureType.POLYGON  -> "Vùng"
    }
    val raw = feature.rawPoints.getOrNull(vertexIdx) ?: feature.rawPoints.firstOrNull()
    val n = raw?.second ?: 0.0   // Northing
    val e = raw?.first  ?: 0.0   // Easting
    val vLabel = when {
        feature.type == VectorLayerImporter.FeatureType.POINT -> baseLbl
        vertexIdx == 0                                        -> "$baseLbl-Đầu"
        vertexIdx == feature.rawPoints.lastIndex              -> "$baseLbl-Cuối"
        else                                                  -> "$baseLbl-Đ$vertexIdx"
    }
    val isLineable = feature.type != VectorLayerImporter.FeatureType.POINT &&
                     feature.rawPoints.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("$typeLabel \"$baseLbl\"", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Chọn cách định vị",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Toạ độ đỉnh gần điểm chạm
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Đỉnh gần điểm chạm: $vLabel", fontSize = 10.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "X = %.3f   Y = %.3f".format(java.util.Locale.US, n, e),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Định vị điểm
                Button(
                    onClick  = { onPickPoint(vLabel, n, e) },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                ) {
                    Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Định vị ĐIỂM (đỉnh này)", fontWeight = FontWeight.SemiBold)
                }

                // Định vị tuyến — khoảng cách vuông góc
                if (isLineable) {
                    Button(
                        onClick  = onPickLine,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) {
                        Icon(Icons.Default.Timeline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Định vị TUYẾN (⊥ vuông góc)", fontWeight = FontWeight.SemiBold)
                    }
                }

                // Bảng toạ độ đỉnh
                OutlinedButton(
                    onClick  = onOpenTable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.List, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Bảng toạ độ đỉnh (${feature.rawPoints.size})")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

// ════════════════════════════════════════════════════════════
// TargetInputCard — Nhập điểm thiết kế
// ════════════════════════════════════════════════════════════

@Composable
private fun TargetInputCard(
    northing         : String,
    easting          : String,
    name             : String,
    acceptRadius     : String,
    onNorthingChange : (String) -> Unit,
    onEastingChange  : (String) -> Unit,
    onNameChange     : (String) -> Unit,
    onRadiusChange   : (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        )
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Điểm thiết kế",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value         = name,
                onValueChange = onNameChange,
                label         = { Text("Tên điểm (tuỳ chọn)") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value         = northing,
                onValueChange = onNorthingChange,
                label         = { Text("X — Northing (mét)") },
                placeholder   = { Text("VD: 2 325 478.123") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier      = Modifier.fillMaxWidth(),
                leadingIcon   = { Text("X", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)) }
            )

            OutlinedTextField(
                value         = easting,
                onValueChange = onEastingChange,
                label         = { Text("Y — Easting (mét)") },
                placeholder   = { Text("VD: 575 123.456") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier      = Modifier.fillMaxWidth(),
                leadingIcon   = { Text("Y", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)) }
            )

            OutlinedTextField(
                value         = acceptRadius,
                onValueChange = onRadiusChange,
                label         = { Text("Bán kính chấp nhận (mét)") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier      = Modifier.fillMaxWidth(),
                supportingText = { Text("Đến đây xem là đã cắm xong (VD: 0.05 = 5 cm)") }
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
// CompassCard — Mũi tên la bàn
// ════════════════════════════════════════════════════════════

/**
 * CompassCardCompact — La bàn nhỏ gọn với 2 lớp:
 *   • Vòng ngoài: hoa tiêu (N/E/S/W) xoay theo hướng thiết bị thực (sensor)
 *   • Kim đỏ: hướng đến điểm mục tiêu (azimuth tính toán)
 *   • Khoảng cách hiển thị ở giữa
 *
 * Cách dùng: xoay thiết bị đến khi kim đỏ trỏ thẳng lên → đi thẳng về phía trước.
 */
@Composable
private fun CompassCardCompact(
    result        : StakeoutResult.Active,
    deviceHeading : Float?   // góc thiết bị từ sensor (độ, 0=Bắc)
) {
    val cardAlpha = 0.65f   // tăng độ trong suốt thêm 10% (0.72 → 0.65)

    // Animate azimuth (hướng đến target từ Bắc)
    val animatedAzimuth by animateFloatAsState(
        targetValue   = result.azimuthDeg.toFloat(),
        animationSpec = tween(300, easing = LinearEasing),
        label         = "azimuth"
    )
    // Animate device heading
    val animatedHeading by animateFloatAsState(
        targetValue   = deviceHeading ?: 0f,
        animationSpec = tween(150, easing = LinearEasing),
        label         = "heading"
    )

    // Góc kim đỏ = azimuth − deviceHeading
    // Khi thiết bị quay về hướng target, kim đỏ = 0° (trỏ lên)
    val needleAngle = animatedAzimuth - animatedHeading

    Surface(
        modifier = Modifier.size(160.dp),
        shape    = androidx.compose.foundation.shape.CircleShape,
        color    = Color.Black.copy(alpha = cardAlpha * 0.6f),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r  = size.width / 2f - 8f

                // dùng drawIntoCanvas để truy cập android.graphics.Canvas
                drawIntoCanvas { composeCanvas ->
                    val nativeC = composeCanvas.nativeCanvas

                    // ── La bàn xoay theo thiết bị ───────────────
                    val textPaint = android.graphics.Paint().apply {
                        color     = android.graphics.Color.WHITE
                        textSize  = 28f
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface  = android.graphics.Typeface.DEFAULT_BOLD
                        isAntiAlias = true
                    }
                    nativeC.save()
                    nativeC.rotate(-animatedHeading, cx, cy)

                    listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (lbl, ang) ->
                        val rad = Math.toRadians(ang.toDouble())
                        val tx  = cx + (r - 18f) * kotlin.math.sin(rad).toFloat()
                        val ty  = cy - (r - 18f) * kotlin.math.cos(rad).toFloat()
                        textPaint.color = if (lbl == "N") android.graphics.Color.RED
                                          else android.graphics.Color.WHITE
                        nativeC.drawText(lbl, tx, ty + textPaint.textSize / 3f, textPaint)
                    }

                    val tickPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(150, 255, 255, 255)
                        strokeWidth = 1.5f; isAntiAlias = true
                    }
                    for (deg in 0 until 360 step 10) {
                        val rad   = Math.toRadians(deg.toDouble())
                        val sinV  = kotlin.math.sin(rad).toFloat()
                        val cosV  = kotlin.math.cos(rad).toFloat()
                        val inner = if (deg % 30 == 0) r - 14f else r - 8f
                        nativeC.drawLine(cx + inner*sinV, cy - inner*cosV, cx + r*sinV, cy - r*cosV, tickPaint)
                    }
                    nativeC.restore()

                    // ── Kim MŨI TÊN đỏ — dẫn hướng đến target ─────
                    // Mũi tên đặc (đầu nhọn + thân) trỏ về mục tiêu cần stakeout.
                    // Xoay canvas theo needleAngle rồi vẽ mũi tên thẳng đứng — đơn giản
                    // và chính xác hơn tự tính sin/cos cho từng đỉnh.
                    val needleLen = r - 24f
                    nativeC.save()
                    nativeC.rotate(needleAngle, cx, cy)

                    val tipY    = cy - needleLen   // đỉnh mũi tên
                    val headLen = 34f              // chiều dài phần đầu nhọn
                    val headW   = 17f              // nửa bề rộng đầu mũi tên
                    val shaftW  = 5.5f             // nửa bề rộng thân

                    val arrowPath = android.graphics.Path().apply {
                        moveTo(cx,          tipY)                  // đỉnh nhọn
                        lineTo(cx - headW,  tipY + headLen)        // cánh trái
                        lineTo(cx - shaftW, tipY + headLen)
                        lineTo(cx - shaftW, cy)                    // thân trái
                        lineTo(cx + shaftW, cy)                    // thân phải
                        lineTo(cx + shaftW, tipY + headLen)
                        lineTo(cx + headW,  tipY + headLen)        // cánh phải
                        close()
                    }
                    val needlePaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.FILL
                        color = android.graphics.Color.RED
                    }
                    nativeC.drawPath(arrowPath, needlePaint)
                    // Viền trắng mảnh giúp mũi tên nổi trên nền tối
                    needlePaint.apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 2f
                        color = android.graphics.Color.argb(220, 255, 255, 255)
                    }
                    nativeC.drawPath(arrowPath, needlePaint)
                    // Đuôi trắng phía sau tâm (đối diện mũi tên)
                    needlePaint.apply {
                        strokeWidth = 5f
                        strokeCap   = android.graphics.Paint.Cap.ROUND
                        color       = android.graphics.Color.argb(180, 255, 255, 255)
                    }
                    nativeC.drawLine(cx, cy, cx, cy + needleLen * 0.35f, needlePaint)
                    nativeC.restore()

                    // Chấm trắng tâm la bàn
                    needlePaint.apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.FILL }
                    nativeC.drawCircle(cx, cy, 6f, needlePaint)
                }
            }

            // Khoảng cách ở giữa
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.padding(top = 48.dp)
            ) {
                Text(
                    result.distanceFormatted,
                    color      = Color.White,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (deviceHeading != null) {
                    Text(
                        "${deviceHeading.toInt()}°",
                        color    = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CompassCard(result: StakeoutResult.Active) {
    // Animate góc quay mũi tên
    val animatedAzimuth by animateFloatAsState(
        targetValue   = result.azimuthDeg.toFloat(),
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label         = "azimuth"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        )
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text(
                "Hướng đi",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            // Vòng tròn la bàn với mũi tên
            Box(
                modifier          = Modifier.size(220.dp),
                contentAlignment  = Alignment.Center
            ) {
                // Vòng nền + ký hiệu N/S/E/W
                CompassRing(modifier = Modifier.fillMaxSize())

                // Mũi tên quay theo azimuth
                Canvas(modifier = Modifier.size(160.dp)) {
                    rotate(degrees = animatedAzimuth, pivot = center) {
                        drawArrow(
                            color  = Color(0xFF1B5E20),
                            center = center,
                            radius = size.minDimension / 2f * 0.8f
                        )
                    }
                }

                // Khoảng cách ở trung tâm
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        result.distanceFormatted,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 22.sp,
                        color      = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        result.compassLabel,
                        fontSize = 12.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Azimuth số
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoChip("Azimuth", result.azimuthFormatted)
                InfoChip("Khoảng cách", result.distanceFormatted)
                InfoChip("Hướng", result.compassLabel)
            }
        }
    }
}

@Composable
private fun CompassRing(modifier: Modifier) {
    val outline  = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r  = size.minDimension / 2f

        // Vòng tròn ngoài
        drawCircle(color = outline, radius = r - 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))

        // Vạch chia 45°
        for (i in 0 until 8) {
            val angle  = i * 45.0 * PI / 180.0
            val cosA   = kotlin.math.cos(angle).toFloat()
            val sinA   = kotlin.math.sin(angle).toFloat()
            val inner  = r * 0.85f
            val outer  = r - 4f
            drawLine(
                color     = outline,
                start     = Offset(cx + cosA * inner, cy + sinA * inner),
                end       = Offset(cx + cosA * outer,  cy + sinA * outer),
                strokeWidth = if (i % 2 == 0) 2f else 1f
            )
        }
    }
}

private val PI = kotlin.math.PI

/**
 * Vẽ mũi tên chỉ hướng. Mũi nhọn chỉ lên trên (hướng 0°=Bắc trước khi rotate).
 */
private fun DrawScope.drawArrow(color: Color, center: Offset, radius: Float) {
    val tip  = Offset(center.x, center.y - radius)          // đỉnh mũi tên (Bắc)
    val base = Offset(center.x, center.y + radius * 0.4f)   // đuôi
    val lw   = radius * 0.18f                                // nửa chiều rộng gốc mũi tên

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x - lw, base.y)
        lineTo(base.x, center.y + radius * 0.1f)   // lõm ở đuôi
        lineTo(base.x + lw, base.y)
        close()
    }
    drawPath(path, color)

    // Đuôi mũi tên (màu xám nhạt hơn, hướng Nam)
    val tailTip   = Offset(center.x, center.y + radius)
    val tailBase  = Offset(center.x, center.y - radius * 0.4f)
    val tailLw    = radius * 0.12f
    val tailPath  = Path().apply {
        moveTo(tailTip.x, tailTip.y)
        lineTo(tailBase.x - tailLw, tailBase.y)
        lineTo(tailBase.x + tailLw, tailBase.y)
        close()
    }
    drawPath(tailPath, Color.LightGray.copy(alpha = 0.6f))
}

// ════════════════════════════════════════════════════════════
// ArrivedBanner — Đã đến nơi
// ════════════════════════════════════════════════════════════

@Composable
private fun ArrivedBanner(result: StakeoutResult.Active) {
    Surface(
        modifier  = Modifier.fillMaxWidth(),
        color     = Color(0xFF1B5E20),
        shape     = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier            = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint     = Color.White,
                modifier = Modifier.size(56.dp)
            )
            Text(
                if (result.isLineMode) "ĐÃ VÀO TUYẾN" else "ĐÃ ĐẾN ĐIỂM THIẾT KẾ",
                color      = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize   = 20.sp
            )
            Text(
                "Khoảng cách còn lại: ${result.distanceFormatted}",
                color    = Color.White.copy(alpha = 0.9f),
                fontSize = 14.sp
            )
            Text(
                "≤ bán kính chấp nhận ${"%.2f".format(result.acceptRadius)} m",
                color    = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
// OffsetCard — Bảng ΔX, ΔY
// ════════════════════════════════════════════════════════════

@Composable
private fun OffsetCard(result: StakeoutResult.Active) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (result.isLineMode) "Độ lệch so với TUYẾN \"${result.lineName}\""
                else "Độ lệch so với điểm thiết kế",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OffsetItem(
                    label    = "ΔX (Bắc/Nam)",
                    value    = result.northingOffsetFormatted,
                    isPlus   = result.dNorthingM >= 0
                )
                VerticalDivider(modifier = Modifier.height(48.dp))
                OffsetItem(
                    label    = "ΔY (Đông/Tây)",
                    value    = result.eastingOffsetFormatted,
                    isPlus   = result.dEastingM >= 0
                )
            }

            // ── Thông tin tuyến (chế độ định vị tuyến) ─────────
            if (result.isLineMode) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoChip("⊥ Cách tuyến", result.distanceFormatted)
                    InfoChip("Lý trình", "%.2f m".format(result.stationM ?: 0.0))
                    InfoChip("Phía", result.sideLabel ?: "—")
                }
            }
        }
    }
}

@Composable
private fun OffsetItem(label: String, value: String, isPlus: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize   = 15.sp,
            fontFamily = FontFamily.Monospace,
            color      = if (isPlus) Color(0xFF1565C0) else Color(0xFFB71C1C)
        )
    }
}

// ════════════════════════════════════════════════════════════
// CoordDetailCard — Toạ độ chi tiết
// ════════════════════════════════════════════════════════════

@Composable
private fun CoordDetailCard(result: StakeoutResult.Active) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Toạ độ VN-2000",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            CoordCompareRow(
                label   = "X (Northing)",
                current = "%.3f".format(result.currentNorthing),
                target  = "%.3f".format(result.targetNorthing)
            )
            Spacer(Modifier.height(4.dp))
            CoordCompareRow(
                label   = "Y (Easting)",
                current = "%.3f".format(result.currentEasting),
                target  = "%.3f".format(result.targetEasting)
            )
        }
    }
}

@Composable
private fun CoordCompareRow(label: String, current: String, target: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            label,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "Hiện tại: $current",
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                color      = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Thiết kế: $target",
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
// Helper Composables
// ════════════════════════════════════════════════════════════

@Composable
private fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp
        )
    }
}

@Composable
private fun NoTargetHint() {
    Surface(
        modifier  = Modifier.fillMaxWidth(),
        color     = MaterialTheme.colorScheme.surfaceVariant,
        shape     = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier            = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.GpsFixed,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Nhập toạ độ điểm thiết kế",
                style     = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color     = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "hoặc chọn từ danh sách điểm đã đo",
                style     = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun InvalidCoordHint() {
    Surface(
        modifier  = Modifier.fillMaxWidth(),
        color     = Color(0xFFFF6F00).copy(alpha = 0.15f),
        shape     = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "⚠ Tọa độ không hợp lệ",
                color     = Color(0xFFE65100),
                style     = MaterialTheme.typography.titleSmall,
                fontWeight= FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "Vui lòng nhập tọa độ VN-2000 đúng định dạng:\n" +
                "• X (Northing): 1 000 000 – 2 500 000 m\n" +
                "• Y (Easting):  200 000 – 900 000 m\n\n" +
                "Không nhập độ thập phân WGS-84 (vd: 10.733...).",
                color     = Color(0xFFE65100),
                textAlign = TextAlign.Start,
                style     = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NoGnssHint() {
    Surface(
        modifier  = Modifier.fillMaxWidth(),
        color     = MaterialTheme.colorScheme.errorContainer,
        shape     = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "⚠ Chưa có vị trí GNSS",
                color     = MaterialTheme.colorScheme.onErrorContainer,
                style     = MaterialTheme.typography.titleSmall,
                fontWeight= FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "Điểm thiết kế đã chọn — la bàn và khoảng cách sẽ hiển thị khi có tín hiệu GPS (kết nối thiết bị RTK và bật NTRIP)",
                modifier  = Modifier.padding(top = 2.dp),
                color     = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                style     = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
// PointPickerDialog — Chọn điểm từ danh sách đã lưu
// ════════════════════════════════════════════════════════════

@Composable
private fun PointPickerDialog(
    points          : List<SurveyPoint>,
    onSelect        : (SurveyPoint) -> Unit,
    onSelectLine    : (name: String, p1: SurveyPoint, p2: SurveyPoint) -> Unit,
    startInLineMode : Boolean = false,
    onDismiss       : () -> Unit
) {
    // false = chọn 1 điểm (định vị điểm); true = chọn 2 điểm (định vị tuyến)
    var lineMode by remember { mutableStateOf(startInLineMode) }
    // Các điểm đã chọn trong chế độ tuyến (tối đa 2: A rồi B)
    var picked   by remember { mutableStateOf<List<SurveyPoint>>(emptyList()) }
    // Tên tuyến (chế độ tuyến) — đặt tên rồi mới chọn điểm đầu/cuối
    var lineName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = {
            Column {
                Text(if (lineMode) "Định vị tuyến từ 2 điểm" else "Chọn điểm thiết kế",
                    fontWeight = FontWeight.Bold)
                Text(
                    if (lineMode) {
                        when (picked.size) {
                            0    -> "Chọn điểm ĐẦU tuyến (A)"
                            1    -> "Đã chọn A=${picked[0].pointCode} • chọn điểm CUỐI (B)"
                            else -> "A=${picked[0].pointCode} → B=${picked[1].pointCode}"
                        }
                    } else "Chạm một điểm để định vị",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text   = {
            Column {
                // ── Chuyển chế độ Điểm ↔ Tuyến ──────────────
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !lineMode,
                        onClick  = { lineMode = false; picked = emptyList() },
                        label    = { Text("Định vị điểm", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.GpsFixed, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = lineMode,
                        onClick  = { lineMode = true; picked = emptyList() },
                        label    = { Text("Định vị tuyến (2 điểm)", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Timeline, null, Modifier.size(16.dp)) }
                    )
                }

                // Ô đặt tên tuyến (chỉ ở chế độ tuyến)
                if (lineMode) {
                    OutlinedTextField(
                        value         = lineName,
                        onValueChange = { lineName = it.take(40) },
                        label         = { Text("Tên tuyến") },
                        placeholder   = { Text("VD: Tuyến AB, Ranh T001...") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }

                if (points.isEmpty()) {
                    Text("Chưa có điểm nào. Hãy đo hoặc import điểm trước.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                }

                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    items(points.size) { idx ->
                        val point = points[idx]
                        val order = picked.indexOf(point)            // -1 nếu chưa chọn
                        val isPicked = order >= 0
                        Card(
                            onClick = {
                                if (!lineMode) {
                                    onSelect(point)
                                } else {
                                    picked = when {
                                        isPicked            -> picked - point          // bỏ chọn
                                        picked.size >= 2    -> listOf(picked[1], point) // thay A cũ
                                        else                -> picked + point
                                    }
                                }
                            },
                            colors = if (isPicked)
                                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else CardDefaults.cardColors(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier          = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (lineMode && isPicked) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        ) {
                                            Text(
                                                if (order == 0) "A" else "B",
                                                color = Color.White, fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(point.pointCode, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("X: ${"%.3f".format(point.northing)}",
                                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Text("Y: ${"%.3f".format(point.easting)}",
                                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (lineMode) {
                Button(
                    onClick = {
                        val nm = lineName.ifBlank { "${picked[0].pointCode}–${picked[1].pointCode}" }
                        onSelectLine(nm, picked[0], picked[1])
                    },
                    enabled = picked.size == 2
                ) { Text("Định vị tuyến") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}
