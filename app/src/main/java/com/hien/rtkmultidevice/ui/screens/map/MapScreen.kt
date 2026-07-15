package com.hien.rtkmultidevice.ui.screens.map

import android.content.Context
import android.net.ConnectivityManager
import android.provider.OpenableColumns
import android.net.NetworkCapabilities
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * MapScreen — Bản đồ OSM hiển thị vị trí GNSS live + điểm đo của dự án.
 *
 * Tính năng:
 *   - Bản đồ nền OSM / Google / Esri toàn màn hình
 *   - FAB "Đo điểm" (khi có project)
 *   - Follow GPS
 *   - Import DXF/SHP/CSV → hiển thị vector overlay
 *   - Tap vào đối tượng vector → VectorFeatureSheet (toạ độ VN-2000 + nút Cắm mốc)
 *   - Căn chỉnh toạ độ: đổi kinh tuyến trục + dịch chuyển ΔN/ΔE
 *   - Điều hướng sang StakeoutScreen với điểm chọn từ bản đồ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    projectId           : Int = -1,
    onNavigateBack      : () -> Unit,
    onNavigateStakeout  : ((n: Double, e: Double, name: String) -> Unit)? = null,
    viewModel           : MapViewModel = hiltViewModel()
) {
    val gnss          by viewModel.gnssStatus.collectAsStateWithLifecycle()
    val project       by viewModel.project.collectAsStateWithLifecycle()
    val savedPoints   by viewModel.savedPoints.collectAsStateWithLifecycle()
    val selectedPoint by viewModel.selectedPoint.collectAsStateWithLifecycle()
    val followGps     by viewModel.followGps.collectAsStateWithLifecycle()
    val isSaving      by viewModel.isSaving.collectAsStateWithLifecycle()
    val pointCode     by viewModel.pointCode.collectAsStateWithLifecycle()
    val note          by viewModel.note.collectAsStateWithLifecycle()
    val savedFeedback by viewModel.savedFeedback.collectAsStateWithLifecycle()
    val measureError  by viewModel.measureError.collectAsStateWithLifecycle()

    var showMeasureSheet    by remember { mutableStateOf(false) }
    var showTileMenu        by remember { mutableStateOf(false) }
    var selectedTile        by remember { mutableStateOf(MapTileSource.GOOGLE_NORMAL) }   // mặc định Google Maps
    // Layer CAD dùng chung (VectorLayerHolder) — không mất khi chuyển màn hình
    val importedLayer       by viewModel.vectorLayer.collectAsStateWithLifecycle()
    var isImporting         by remember { mutableStateOf(false) }

    // ── Trạng thái tap vector feature ──────────────────────────
    var selectedVecFeature  by remember { mutableStateOf<VectorLayerImporter.VectorFeature?>(null) }
    var selectedVecVertexIdx by remember { mutableIntStateOf(0) }
    // Bật/tắt hiển thị lớp thửa (không xoá layer — chỉ ẩn/hiện)
    var layerVisible by remember { mutableStateOf(true) }
    // Unload tờ (gỡ layer) -> đóng menu thửa đang mở
    LaunchedEffect(importedLayer) { if (importedLayer == null) selectedVecFeature = null }

    // Overlay khung tờ tổng thể (bật/tắt)
    val allFrames by viewModel.sheetFrames.collectAsStateWithLifecycle()
    var showFrames by remember { mutableStateOf(false) }

    // ── Trạng thái dialog căn chỉnh toạ độ ─────────────────────
    var showAlignDialog     by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val importScope = rememberCoroutineScope()

    // ── Điều hướng tới thửa mong muốn (nhập "tờ/thửa" ở nút Cloud) ──
    val targetThua by viewModel.targetThua.collectAsStateWithLifecycle()
    var focusPoint by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(importedLayer, targetThua) {
        val layer = importedLayer
        val t = targetThua?.filter { it.isDigit() }
        if (layer != null && !t.isNullOrBlank()) {
            val feat = layer.features.firstOrNull { f -> f.label.filter { c -> c.isDigit() } == t }
            if (feat != null) {
                selectedVecFeature = feat
                focusPoint = feat.centroid
            } else {
                snackbarHostState.showSnackbar("Không thấy thửa $t trong tờ này")
            }
            viewModel.clearTargetThua()
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // File picker
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Lấy tên file thật qua ContentResolver — URI từ SAF thường là
        // "content://...document/msf:1234", lastPathSegment không có extension
        val fileName: String = ctx.contentResolver
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
            when (val result = VectorLayerImporter.importFromUri(ctx, uri, fileName, hintCm)) {
                is VectorLayerImporter.ImportResult.Success -> {
                    viewModel.setVectorLayer(result.layer)
                    val sys = result.layer.coordSystem
                    val cm  = result.layer.detectedCm
                    val sysLabel = when (sys) {
                        VectorLayerImporter.CoordSystem.VN2000_3DEG -> "VN-2000 múi 3° (KTT ${cm}°)"
                        VectorLayerImporter.CoordSystem.VN2000_6DEG -> "VN-2000 múi 6°"
                        VectorLayerImporter.CoordSystem.WGS84        -> "WGS-84"
                        else -> "Không rõ"
                    }
                    snackbarHostState.showSnackbar(
                        "✓ ${result.layer.featureCount} đối tượng — $sysLabel"
                    )
                }
                is VectorLayerImporter.ImportResult.Error ->
                    snackbarHostState.showSnackbar("Lỗi: ${result.message}")
            }
            isImporting = false
        }
    }

    LaunchedEffect(projectId) { viewModel.loadProject(projectId) }

    LaunchedEffect(savedFeedback) {
        savedFeedback?.let { snackbarHostState.showSnackbar("✓ Đã lưu điểm $it"); viewModel.clearSavedFeedback() }
    }
    LaunchedEffect(measureError) {
        measureError?.let { snackbarHostState.showSnackbar(it); viewModel.clearMeasureError() }
    }

    // ── Cảnh báo WiFi không có internet (T30 hotspot scenario) ──────
    // Khi phone kết nối WiFi hotspot của T30 (không có internet),
    // osmdroid mặc định dùng WiFi → tile download thất bại.
    // Hiện snackbar gợi ý bật "Dùng dữ liệu di động" trong cài đặt WiFi.
    LaunchedEffect(Unit) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        // Kiểm tra TẤT CẢ networks — nếu có 4G/5G với internet thì không cần cảnh báo
        val hasAnyInternet = cm.allNetworks.any { network ->
            val c = cm.getNetworkCapabilities(network)
            c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        if (onWifi && !hasAnyInternet) {
            snackbarHostState.showSnackbar(
                "⚠ WiFi không có internet — bản đồ nền có thể không tải được.\n"
              + "Bật \"Dùng dữ liệu di động\" trong cài đặt WiFi để tải tile.",
                duration = SnackbarDuration.Long
            )
        }
    }

    // Đảm bảo có Internet: nếu WiFi RTK không có mạng → tự chuyển app sang 4G/5G
    // Sau đó chẩn đoán tile server: tải thử 1 tile, báo lỗi cụ thể nếu thất bại.
    LaunchedEffect(selectedTile) {
        com.hien.rtkmultidevice.core.network.CellularNetworkHelper.ensureInternet(ctx)?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
        }
        MapTileDiagnostics.probe(selectedTile)?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Top bar thu gọn (42dp) — tối đa không gian bản đồ
            com.hien.rtkmultidevice.ui.components.CompactTopBar(
                title    = project?.name ?: "Bản đồ",
                subtitle = "${savedPoints.size} điểm đo",
                onBack   = onNavigateBack,
                actions  = {
                    // ── Căn chỉnh toạ độ (hiện khi có layer import) ─
                    if (importedLayer != null) {
                        com.hien.rtkmultidevice.ui.components.CompactActionIcon(
                            Icons.Default.Tune, "Căn chỉnh toạ độ", tint = Color(0xFFFFE082)
                        ) { showAlignDialog = true }
                    }
                    // ── Tile source selector ────────────────────────
                    Box {
                        com.hien.rtkmultidevice.ui.components.CompactActionIcon(
                            Icons.Default.Layers, "Lớp bản đồ"
                        ) { showTileMenu = true }
                        DropdownMenu(expanded = showTileMenu, onDismissRequest = { showTileMenu = false }) {
                            MapTileSource.entries.forEach { source ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(source.label, fontSize = 13.sp, fontWeight = if (source == selectedTile) FontWeight.Bold else FontWeight.Normal)
                                            Text(source.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { selectedTile = source; showTileMenu = false },
                                    leadingIcon = {
                                        if (source == selectedTile) Icon(Icons.Default.GpsFixed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    }
                                )
                            }
                        }
                    }
                    // ── Import vector ───────────────────────────────
                    IconButton(
                        onClick = { importFileLauncher.launch(arrayOf("*/*")) },
                        enabled = !isImporting,
                        modifier = Modifier.size(38.dp)
                    ) {
                        if (isImporting) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Default.FileOpen, "Import DXF/SHP", Modifier.size(19.dp), tint = Color.White)
                    }
                    // ── Tải bản đồ địa chính từ Cloud ───────────────
                    CadastralCloudButton(viewModel, modifier = Modifier.size(38.dp))
                    // ── Tôi đang ở thửa nào ─────────────────────────
                    WhereAmIButton(viewModel, gnss.latitude, gnss.longitude, modifier = Modifier.size(38.dp))
                    // ── Tra thửa theo toạ độ nhập tay ───────────────
                    CoordLookupButton(viewModel, modifier = Modifier.size(38.dp))
                    // ── Bật/tắt khung tờ tổng thể ───────────────────
                    IconButton(
                        onClick = {
                            showFrames = !showFrames
                            if (showFrames) viewModel.loadSheetFramesIfNeeded()
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            if (showFrames) Icons.Default.GridOff else Icons.Default.GridOn,
                            contentDescription = "Khung tờ tổng thể",
                            tint = if (showFrames) Color(0xFF80FF80) else Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    // ── Follow GPS ──────────────────────────────────
                    com.hien.rtkmultidevice.ui.components.CompactActionIcon(
                        icon = if (followGps) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                        contentDescription = "Follow GPS",
                        tint = if (followGps) Color(0xFF80FF80) else Color.White,
                        onClick = viewModel::toggleFollowGps
                    )
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Map View ──────────────────────────────────────────
            OsmMapView(
                modifier            = Modifier.fillMaxSize(),
                gnss                = gnss,
                points              = savedPoints,
                followGps           = followGps,
                tileSource          = selectedTile,
                vectorLayer         = if (layerVisible) importedLayer else null,
                // Highlight đối tượng đang chọn (sheet đang mở) — cyan nét đậm
                highlightFeatureId  = selectedVecFeature?.id,
                focusPoint          = focusPoint,
                sheetFrames         = if (showFrames) allFrames else emptyList(),
                onSheetTap          = { commune, to -> viewModel.loadCadastralSheet(commune, to) },
                onScrolled          = viewModel::onMapScrolled,
                onMarkerTap         = { viewModel.selectPoint(it) },
                onVectorFeatureTap  = { feature, vidx ->
                    selectedVecFeature   = feature
                    selectedVecVertexIdx = vidx
                }
            )

            // ── Badge lớp thửa: chạm nhãn để BẬT/TẮT hiển thị, ✕ để gỡ hẳn ──
            importedLayer?.let { layer ->
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                    color = Color(0xFF7B1FA2).copy(alpha = if (layerVisible) 0.9f else 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clickable { layerVisible = !layerVisible }
                                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (layerVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Bật/tắt lớp thửa",
                                tint = Color.White, modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(layer.name.take(20), color = Color.White, fontSize = 11.sp)
                        }
                        Icon(
                            Icons.Default.Close, contentDescription = "Gỡ lớp thửa",
                            tint = Color.White,
                            modifier = Modifier
                                .clickable { viewModel.clearVectorLayer(); layerVisible = true }
                                .padding(6.dp).size(15.dp)
                        )
                    }
                }
            }

            // ── Fix chip + coordinate toggle ──────────────────────
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    color = Color(android.graphics.Color.parseColor(gnss.fixColorHex)),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "${gnss.fixLabel}  •  ${gnss.satelliteCount} vệ tinh",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                CoordinateToggleOverlay(gnss)
            }

            // ── FAB "Đo điểm" ─────────────────────────────────────
            if (projectId > 0) {
                ExtendedFloatingActionButton(
                    modifier       = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                    onClick        = { showMeasureSheet = true },
                    icon           = { Icon(Icons.Default.AddLocation, null) },
                    text           = { Text("Đo điểm", fontWeight = FontWeight.SemiBold) },
                    containerColor = if (gnss.isRtk) Color(0xFF1B5E20) else MaterialTheme.colorScheme.primary,
                    contentColor   = Color.White
                )
            }
        }
    }

    // ── Bottom Sheets & Dialogs ─────────────────────────────────

    selectedPoint?.let { point ->
        PointDetailSheet(point = point, onDismiss = { viewModel.selectPoint(null) })
    }

    if (showMeasureSheet) {
        MeasurePointSheet(
            gnss = gnss, pointCode = pointCode, note = note, isSaving = isSaving,
            onCodeChange = viewModel::onPointCodeChange,
            onNoteChange = viewModel::onNoteChange,
            onSave    = { viewModel.savePoint(); showMeasureSheet = false },
            onDismiss = { showMeasureSheet = false }
        )
    }

    // ── VectorFeatureSheet — tap đối tượng DXF/SHP ──────────────
    selectedVecFeature?.let { feature ->
        VectorFeatureSheet(
            feature    = feature,
            vertexIdx  = selectedVecVertexIdx,
            layer      = importedLayer,
            onDismiss  = { selectedVecFeature = null },
            onStakeout = { n, e, name ->
                selectedVecFeature = null
                // Fix crash: đặt target vào holder (bộ nhớ) thay vì truyền qua route string.
                // Tên điểm DXF có thể chứa ký tự đặc biệt làm route không khớp → crash.
                // featureId: để Survey/Stakeout vẽ nhãn số hiệu đỉnh của đối tượng CAD.
                viewModel.prepareStakeout(n, e, name, feature.id)
                onNavigateStakeout?.invoke(n, e, name)
            },
            onStakeoutLine = { f ->
                selectedVecFeature = null
                // Định vị tuyến: đưa toàn bộ đỉnh tuyến vào holder → Stakeout
                // hiển thị khoảng cách vuông góc đến tuyến.
                viewModel.prepareStakeoutLine(f)
                onNavigateStakeout?.invoke(0.0, 0.0, "")
            }
        )
    }

    // ── Dialog căn chỉnh toạ độ ──────────────────────────────────
    if (showAlignDialog) {
        importedLayer?.let { layer ->
            CoordAlignDialog(
                layer     = layer,
                onDismiss = { showAlignDialog = false },
                onApply   = { newLayer ->
                    viewModel.setVectorLayer(newLayer)
                    showAlignDialog  = false
                    importScope.launch {
                        snackbarHostState.showSnackbar("✓ Đã căn chỉnh toạ độ")
                    }
                }
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
// OsmMapView — AndroidView wrapper cho osmdroid
// ════════════════════════════════════════════════════════════

@Composable
private fun OsmMapView(
    modifier           : Modifier = Modifier,
    gnss               : GnssStatus,
    points             : List<SurveyPoint>,
    followGps          : Boolean,
    tileSource         : MapTileSource = MapTileSource.GOOGLE_NORMAL,
    vectorLayer        : VectorLayerImporter.VectorLayer? = null,
    /** Id feature cần highlight (đang được chọn) — vẽ cyan nét đậm */
    highlightFeatureId : Int? = null,
    /** Điểm cần căn giữa bản đồ (đi tới thửa) */
    focusPoint         : GeoPoint? = null,
    onScrolled         : () -> Unit,
    onMarkerTap        : (SurveyPoint) -> Unit,
    onVectorFeatureTap : (VectorLayerImporter.VectorFeature, Int) -> Unit = { _, _ -> },
    /** Khung tờ tổng thể (overlay) + chạm khung để tải tờ */
    sheetFrames        : List<CadastralCloudSource.SheetBox> = emptyList(),
    onSheetTap         : (commune: String, to: String) -> Unit = { _, _ -> }
) {
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    // Căn giữa + phóng to tới thửa được yêu cầu (đi tới thửa 90...)
    LaunchedEffect(focusPoint) {
        focusPoint?.let {
            mapViewRef.value?.controller?.animateTo(it)
            mapViewRef.value?.controller?.setZoom(19.0)
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { context ->
                Configuration.getInstance().apply {
                    load(context, context.getSharedPreferences("osmdroid", 0))
                    // UA theo nguồn tile: OSM/OpenTopo/Carto chặn UA giả trình duyệt,
                    // Google cần UA trình duyệt → chọn đúng UA cho từng nguồn
                    userAgentValue = MapTileSource.userAgentFor(tileSource)
                    // Dùng filesDir thay cacheDir: Android có thể dọn cacheDir giữa chừng
                    // → SQLite tile cache hỏng → tile không vẽ được
                    val baseDir = java.io.File(context.filesDir, "osmdroid")
                    val tileDir = java.io.File(baseDir, "tiles")
                    tileDir.mkdirs()
                    osmdroidBasePath             = baseDir
                    osmdroidTileCache            = tileDir
                    tileDownloadMaxQueueSize     = 40
                    tileFileSystemCacheMaxBytes  = 200L * 1024 * 1024   // 200 MB max
                    tileFileSystemCacheTrimBytes = 150L * 1024 * 1024   // trim về 150 MB
                }
                MapView(context).apply {
                    setTileSource(tileSource.toOsmdroidSource())
                    setMultiTouchControls(true)
                    // Center mặc định = giữa Việt Nam — tránh mở map ở (0,0) giữa biển
                    controller.setZoom(6.0)
                    controller.setCenter(GeoPoint(16.0, 107.5))
                    mapViewRef.value = this
                }
            },
            update = { mapView ->
                if (mapView.tileProvider.tileSource.name() != tileSource.toOsmdroidSource().name()) {
                    // Đổi UA TRƯỚC khi đổi tile source
                    Configuration.getInstance().userAgentValue = MapTileSource.userAgentFor(tileSource)
                    mapView.setTileSource(tileSource.toOsmdroidSource())
                    mapView.invalidate()
                }
                updateMapOverlays(mapView, gnss, points, followGps, onScrolled, onMarkerTap)
                updateSheetFrames(mapView, sheetFrames, onSheetTap)
                updateVectorOverlay(mapView, vectorLayer, highlightFeatureId, onVectorFeatureTap)
            }
        )

        // ── Zoom controls ──────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (vectorLayer != null) {
                SmallFloatingActionButton(
                    onClick        = { fitToVectorLayerMap(mapViewRef.value, vectorLayer) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor   = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape          = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.CenterFocusStrong, "Fit layer", Modifier.size(18.dp))
                }
            }
            SmallFloatingActionButton(
                onClick        = { mapViewRef.value?.controller?.zoomIn() },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor   = MaterialTheme.colorScheme.onSurface,
                shape          = RoundedCornerShape(8.dp)
            ) { Icon(Icons.Default.Add, "Zoom in", Modifier.size(18.dp)) }
            SmallFloatingActionButton(
                onClick        = { mapViewRef.value?.controller?.zoomOut() },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor   = MaterialTheme.colorScheme.onSurface,
                shape          = RoundedCornerShape(8.dp)
            ) { Icon(Icons.Default.Remove, "Zoom out", Modifier.size(18.dp)) }
        }
    }

    // Lifecycle-aware: osmdroid cần onResume() để bắt đầu tải tile,
    // onPause() để dừng khi màn hình không hiển thị.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE  -> mapViewRef.value?.onPause()
                else                                          -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef.value?.onDetach()
        }
    }
}

// ════════════════════════════════════════════════════════════
// Overlay helpers
// ════════════════════════════════════════════════════════════

private fun fitToVectorLayerMap(mapView: MapView?, layer: VectorLayerImporter.VectorLayer) {
    if (mapView == null) return
    val allPoints = (layer.polylines + layer.polygons).flatten() + layer.points
    if (allPoints.isEmpty()) return
    val minLat = allPoints.minOf { it.latitude  }; val maxLat = allPoints.maxOf { it.latitude  }
    val minLon = allPoints.minOf { it.longitude }; val maxLon = allPoints.maxOf { it.longitude }
    if (minLat == maxLat && minLon == maxLon) {
        mapView.controller.animateTo(GeoPoint(minLat, minLon)); mapView.controller.setZoom(18.0); return
    }
    mapView.post { mapView.zoomToBoundingBox(BoundingBox(maxLat, maxLon, minLat, minLon), true, 60) }
}

/**
 * Cập nhật overlay GPS + điểm đo.
 * Chỉ xoá GPS/survey overlays, GIỮ NGUYÊN vector overlays.
 */
private fun updateMapOverlays(
    mapView     : MapView,
    gnss        : GnssStatus,
    points      : List<SurveyPoint>,
    followGps   : Boolean,
    onScrolled  : () -> Unit,
    onMarkerTap : (SurveyPoint) -> Unit
) {
    // Chỉ xoá GPS/survey overlays — không xoá vector overlays (tag bắt đầu bằng "vec_"
    // hoặc title="vector") và MapEventsOverlay (bắt tap vào giữa thửa)
    mapView.overlays.removeAll { overlay ->
        when (overlay) {
            is Polyline                                    -> overlay.title != "vector"
            is Marker                                      -> overlay.title?.startsWith("vec_") != true
            is org.osmdroid.views.overlay.MapEventsOverlay -> false
            else                                           -> true
        }
    }

    // Polyline nối điểm đo — CHỈ nối điểm đo RTK thật (fixQuality > 0),
    // không nối điểm import (tâm thửa, nhãn TEXT từ DXF) → tránh rối mắt
    val measuredPts = points.filter {
        it.fixQuality > 0 && it.latitude != 0.0 && it.longitude != 0.0
    }
    if (measuredPts.size >= 2) {
        mapView.overlays.add(Polyline(mapView).apply {
            outlinePaint.color       = AndroidColor.parseColor("#881565C0")
            outlinePaint.strokeWidth = 4f
            setPoints(measuredPts.map { GeoPoint(it.latitude, it.longitude) })
        })
    }

    // Marker điểm đo
    points.forEach { point ->
        mapView.overlays.add(Marker(mapView).apply {
            position  = GeoPoint(point.latitude, point.longitude)
            title     = point.pointCode
            snippet   = "X: ${"%.3f".format(point.northing)}  Y: ${"%.3f".format(point.easting)}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createCircleDrawable(mapView.context, AndroidColor.parseColor(point.fixColorHex))
            setOnMarkerClickListener { _, _ -> onMarkerTap(point); true }
        })
    }

    // Marker GPS hiện tại
    if (gnss.hasFix) {
        val pos = GeoPoint(gnss.latitude, gnss.longitude)
        mapView.overlays.add(Marker(mapView).apply {
            position = pos
            title    = "Vị trí hiện tại"
            snippet  = "Fix: ${gnss.fixLabel}  HDOP: ${"%.1f".format(gnss.hdop)}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createGpsDrawable(mapView.context)
        })
        if (followGps) mapView.controller.animateTo(pos)
    }
    mapView.invalidate()
}

/**
 * Vẽ vector layer (DXF/SHP) lên MapView.
 *
 * Caching theo object identity: chỉ tái tạo marker khi layer thực sự thay đổi.
 * → tránh tạo lại hàng trăm marker mỗi lần GPS cập nhật.
 *
 * Markers tappable:
 *   POINT    → 1 marker cho mỗi điểm (tối đa 1 000 điểm)
 *   POLYLINE → marker ở 2 đầu; nếu ≤ 10 đỉnh thì tất cả đỉnh
 *   POLYGON  → tất cả đỉnh (tối đa 50 đỉnh/feature)
 */
/** Vẽ overlay KHUNG TỜ (bbox WGS84) + nhãn số tờ; chạm khung → tải tờ đó. */
private fun updateSheetFrames(
    mapView: MapView,
    frames: List<CadastralCloudSource.SheetBox>,
    onSheetTap: (String, String) -> Unit
) {
    // Dùng Polyline (VIỀN) thay vì Polygon để KHÔNG chặn thao tác chạm thửa bên trong.
    val already = mapView.overlays.count { it is Polyline && it.title == "sheetframe" }
    if (frames.isEmpty()) {
        if (already > 0) {
            mapView.overlays.removeAll { it is Polyline && it.title == "sheetframe" }
            mapView.overlays.removeAll { it is Marker && it.title?.startsWith("sf_") == true }
            mapView.invalidate()
        }
        return
    }
    if (already == frames.size) return   // đã vẽ rồi

    mapView.overlays.removeAll { it is Polyline && it.title == "sheetframe" }
    mapView.overlays.removeAll { it is Marker && it.title?.startsWith("sf_") == true }

    frames.forEach { box ->
        mapView.overlays.add(Polyline(mapView).apply {
            title = "sheetframe"
            infoWindow = null
            setPoints(listOf(
                GeoPoint(box.latMin, box.lonMin),
                GeoPoint(box.latMin, box.lonMax),
                GeoPoint(box.latMax, box.lonMax),
                GeoPoint(box.latMax, box.lonMin),
                GeoPoint(box.latMin, box.lonMin)   // khép viền
            ))
            outlinePaint.color       = AndroidColor.argb(190, 21, 101, 192)
            outlinePaint.strokeWidth = 2f
            setOnClickListener { _, _, _ -> onSheetTap(box.commune, box.to); true }
        })
        mapView.overlays.add(Marker(mapView).apply {
            title = "sf_${box.commune}_${box.to}"
            position = GeoPoint(box.centerLat, box.centerLon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = makeSheetLabelIcon(mapView.context, box.to)
            infoWindow = null
            setOnMarkerClickListener { _, _ -> onSheetTap(box.commune, box.to); true }
        })
    }
    mapView.invalidate()
}

/** Icon nhãn "Tờ N" (chip nhỏ) cho overlay khung tờ. */
private fun makeSheetLabelIcon(context: Context, to: String): android.graphics.drawable.Drawable {
    val d = context.resources.displayMetrics.density
    val ts = 12f * d; val padX = 6f * d; val padY = 3f * d
    val txt = "Tờ $to"
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ts; typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER; color = AndroidColor.WHITE
    }
    val w = (tp.measureText(txt) + padX * 2).toInt().coerceAtLeast(1)
    val h = (ts + padY * 2).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 6f * d, 6f * d,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(205, 21, 101, 192) })
    c.drawText(txt, w / 2f, padY + ts * 0.85f, tp)
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

private fun updateVectorOverlay(
    mapView           : MapView,
    layer             : VectorLayerImporter.VectorLayer?,
    highlightFeatureId: Int?,
    onFeatureTap      : (VectorLayerImporter.VectorFeature, Int) -> Unit
) {
    // Cache check — chỉ vẽ lại khi layer HOẶC highlight thay đổi
    // (identityHashCode: so sánh rẻ, không deep-compare hàng nghìn toạ độ)
    val cacheKey = "vec_${System.identityHashCode(layer)}_$highlightFeatureId"
    if (mapView.tag == cacheKey) return
    mapView.tag = cacheKey

    // Xoá overlay vector cũ + MapEventsOverlay cũ
    mapView.overlays.removeAll { it is Polyline && it.title == "vector" }
    mapView.overlays.removeAll { it is Marker  && it.title?.startsWith("vec_") == true }
    mapView.overlays.removeAll { it is org.osmdroid.views.overlay.MapEventsOverlay }

    if (layer == null) { mapView.invalidate(); return }

    // ── Tap vào GIỮA thửa → chọn polygon khép kín nhỏ nhất chứa điểm chạm ──
    // Tap vùng trống ngoài mọi đối tượng khép kín → không làm gì.
    run {
        val receiver = object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val f = VectorLayerImporter.findEnclosingFeature(layer, p.latitude, p.longitude)
                    ?: return false
                onFeatureTap(f, 0)
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        mapView.overlays.add(0, org.osmdroid.views.overlay.MapEventsOverlay(receiver))
    }

    val strokeColor    = AndroidColor.argb(200, 255, 100, 0)   // cam đậm
    val highlightColor = AndroidColor.argb(255, 0, 229, 255)   // cyan nổi bật

    // Vẽ polylines / polygons — THEO TỪNG FEATURE để chạm trực tiếp vào đường.
    // infoWindow = null: tắt popup mặc định "vector" của osmdroid (gây khó hiểu).
    // Chạm vào đường → mở VectorFeatureSheet với tuỳ chọn Định vị điểm/tuyến.
    // Feature đang chọn được HIGHLIGHT cyan + nét đậm để user biết rõ.
    layer.features.forEach { feature ->
        if (feature.type == VectorLayerImporter.FeatureType.POINT) return@forEach
        if (feature.geoPoints.size < 2) return@forEach
        val isHighlighted = feature.id == highlightFeatureId
        mapView.overlays.add(Polyline(mapView).apply {
            title      = "vector"
            infoWindow = null
            setPoints(feature.geoPoints)
            outlinePaint.color       = if (isHighlighted) highlightColor else strokeColor
            outlinePaint.strokeWidth = if (isHighlighted) 7f else 2.5f
            setOnClickListener { _, _, _ -> onFeatureTap(feature, 0); true }
        })
    }

    // ── Nhãn hỗn số tại tâm thửa: loại đất / (số thửa ÷ diện tích) ──
    val labelCtx = mapView.context
    var labelBudget = 500
    layer.features.forEach { feature ->
        if (labelBudget <= 0) return@forEach
        if (feature.type != VectorLayerImporter.FeatureType.POLYGON) return@forEach
        if (feature.soThua.isBlank()) return@forEach
        val c = feature.centroid ?: return@forEach
        labelBudget--
        mapView.overlays.add(Marker(mapView).apply {
            title = "vec_lbl_${feature.id}"
            position = c
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = makeParcelLabelIcon(labelCtx, feature.loaiDat, feature.soThua, feature.dienTich)
            infoWindow = null
            setOnMarkerClickListener { _, _ -> onFeatureTap(feature, 0); true }
        })
    }

    // Thêm markers tappable — giới hạn tổng 1 500
    var markerBudget = 1500

    layer.features.forEach { feature ->
        if (markerBudget <= 0) return@forEach

        val indicesToMark: List<Int> = when (feature.type) {
            VectorLayerImporter.FeatureType.POINT    ->
                feature.geoPoints.indices.toList()

            VectorLayerImporter.FeatureType.POLYLINE ->
                if (feature.geoPoints.size <= 10)
                    feature.geoPoints.indices.toList()
                else
                    listOf(0, feature.geoPoints.lastIndex)   // chỉ 2 đầu cho đường dài

            VectorLayerImporter.FeatureType.POLYGON  ->
                feature.geoPoints.indices.toList().take(50)
        }

        indicesToMark.forEach { vidx ->
            if (markerBudget <= 0) return@forEach
            val gp = feature.geoPoints.getOrNull(vidx) ?: return@forEach
            mapView.overlays.add(Marker(mapView).apply {
                title      = "vec_${feature.id}_$vidx"
                position   = gp
                infoWindow = null    // tắt popup mặc định của osmdroid
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createVectorNodeDrawable(
                    mapView.context, feature.type,
                    highlighted = feature.id == highlightFeatureId
                )
                setOnMarkerClickListener { _, _ -> onFeatureTap(feature, vidx); true }
            })
            markerBudget--
        }
    }

    mapView.invalidate()
}

// ════════════════════════════════════════════════════════════
// VectorFeatureSheet — bottom sheet khi tap đối tượng vector
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VectorFeatureSheet(
    feature        : VectorLayerImporter.VectorFeature,
    vertexIdx      : Int,
    layer          : VectorLayerImporter.VectorLayer?,
    onDismiss      : () -> Unit,
    onStakeout     : (n: Double, e: Double, name: String) -> Unit,
    /** Định vị tuyến — chuyển sang Stakeout với chế độ khoảng cách vuông góc */
    onStakeoutLine : (VectorLayerImporter.VectorFeature) -> Unit = {}
) {
    // sheetState + scope để gọi hide() trước khi navigate
    // Không gọi trực tiếp selectedVecFeature=null + navigate trong onClick
    // vì ModalBottomSheet đang ở trạng thái Expanded → remove khỏi tree → crash
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { it != SheetValue.Hidden }   // chặn vuốt-ẩn: chỉ đóng bằng nút X hoặc unload tờ
    )
    val scope      = rememberCoroutineScope()
    var showVertexTable by remember { mutableStateOf(false) }

    val rawX = feature.rawPoints.getOrNull(vertexIdx)?.first  ?: feature.rawPoints.firstOrNull()?.first  ?: 0.0
    val rawY = feature.rawPoints.getOrNull(vertexIdx)?.second ?: feature.rawPoints.firstOrNull()?.second ?: 0.0
    val gp   = feature.geoPoints.getOrNull(vertexIdx) ?: feature.centroid

    val isVn2000 = feature.coordSystem == VectorLayerImporter.CoordSystem.VN2000_3DEG ||
                   feature.coordSystem == VectorLayerImporter.CoordSystem.VN2000_6DEG

    // rawX = easting, rawY = northing (với VN-2000)
    // rawX = lon,     rawY = lat      (với WGS-84)

    val typeLabel = when (feature.type) {
        VectorLayerImporter.FeatureType.POINT    -> "Điểm"
        VectorLayerImporter.FeatureType.POLYLINE -> if (feature.geoPoints.size == 2) "Đoạn thẳng" else "Đường"
        VectorLayerImporter.FeatureType.POLYGON  -> "Vùng"
    }
    val vertexLabel = when (feature.type) {
        VectorLayerImporter.FeatureType.POINT    -> ""
        VectorLayerImporter.FeatureType.POLYLINE ->
            when (vertexIdx) {
                0                           -> "  (điểm đầu)"
                feature.geoPoints.lastIndex -> "  (điểm cuối)"
                else                        -> "  (đỉnh $vertexIdx)"
            }
        VectorLayerImporter.FeatureType.POLYGON  -> "  (đỉnh $vertexIdx)"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        scrimColor       = Color.Transparent,   // map vẫn thấy & chạm được sau menu (chạm thửa khác/mở lại)
        containerColor   = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)  // trong suốt hơn
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Tiêu đề ──────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = Color(0xFFFF6D00).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "$typeLabel$vertexLabel",
                        modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color      = Color(0xFFFF6D00),
                        fontWeight = FontWeight.Bold,
                        fontSize   = 14.sp
                    )
                }
                if (feature.dxfLayer.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "Layer: ${feature.dxfLayer}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Đóng")
                }
            }

            if (feature.label.isNotEmpty()) {
                Text("\"${feature.label}\"", fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }

            HorizontalDivider()

            // ── Toạ độ VN-2000 ────────────────────────────────────
            if (isVn2000) {
                val cmLabel = if ((layer?.detectedCm ?: 0.0) > 0) " (KTT ${layer!!.detectedCm}°)" else ""
                Text(
                    "VN-2000$cmLabel",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFCC80)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("X (Northing)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.3f m".format(rawY), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Y (Easting)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.3f m".format(rawX), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
            }

            // ── Toạ độ WGS-84 ─────────────────────────────────────
            gp?.let {
                Text("WGS-84", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF80DEEA))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Vĩ độ φ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.7f°".format(it.latitude), fontFamily = FontFamily.Monospace)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Kinh độ λ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.7f°".format(it.longitude), fontFamily = FontFamily.Monospace)
                }
            }

            HorizontalDivider()

            // ── Nút Cắm mốc ──────────────────────────────────────
            if (isVn2000) {
                val stakeoutLabel = feature.label.ifEmpty { "VEC-${feature.id}" }
                Button(
                    onClick = {
                        // Phải hide sheet trước rồi mới navigate.
                        // Nếu remove composable ngay (selectedVecFeature=null) khi sheet đang
                        // Expanded → ModalBottomSheet state mâu thuẫn → crash.
                        val n = rawY; val e = rawX; val lbl = stakeoutLabel
                        scope.launch {
                            sheetState.hide()        // đợi animation kết thúc
                            onStakeout(n, e, lbl)    // rồi mới navigate + dismiss
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                ) {
                    Icon(Icons.Default.Navigation, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cắm mốc điểm này", fontWeight = FontWeight.SemiBold)
                }

                // ── Định vị tuyến (line/shape ≥ 2 đỉnh) ─────────
                if (feature.type != VectorLayerImporter.FeatureType.POINT && feature.rawPoints.size > 1) {
                    Button(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                onStakeoutLine(feature)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) {
                        Icon(Icons.Default.Timeline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Định vị TUYẾN (⊥ vuông góc)", fontWeight = FontWeight.SemiBold)
                    }
                }

                // ── Bảng toạ độ các đỉnh (line/shape ≥ 2 đỉnh) ──
                if (feature.rawPoints.size > 1) {
                    OutlinedButton(
                        onClick  = { showVertexTable = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.TableChart, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Bảng toạ độ các đỉnh (${feature.rawPoints.size})")
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "⚠ File dùng WGS-84 — cần tọa độ VN-2000 để cắm mốc.\n" +
                        "Dùng \"Căn chỉnh toạ độ\" nếu file thực chất là VN-2000.",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 12.sp,
                        color    = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    // ── Bảng toạ độ các đỉnh — chọn đỉnh bất kỳ để cắm mốc ──
    if (showVertexTable) {
        VertexTableDialog(
            feature      = feature,
            highlightIdx = vertexIdx,
            onPick       = { lbl, n, e ->
                showVertexTable = false
                scope.launch {
                    sheetState.hide()
                    onStakeout(n, e, lbl)
                }
            },
            onDismiss = { showVertexTable = false }
        )
    }
}

// ════════════════════════════════════════════════════════════
// CoordAlignDialog — đổi kinh tuyến trục + offset ΔN / ΔE
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoordAlignDialog(
    layer     : VectorLayerImporter.VectorLayer,
    onDismiss : () -> Unit,
    onApply   : (VectorLayerImporter.VectorLayer) -> Unit
) {
    // Kinh tuyến trục
    var selectedCm by remember {
        mutableStateOf(
            if (layer.detectedCm > 0) layer.detectedCm
            else VectorLayerImporter.DEFAULT_CM   // mặc định 107°45' = 107.75°
        )
    }

    // Offset thêm vào (không phải tổng offset)
    var dNText by remember { mutableStateOf("0") }
    var dEText by remember { mutableStateOf("0") }

    val isVn2000 = layer.coordSystem == VectorLayerImporter.CoordSystem.VN2000_3DEG ||
                   layer.coordSystem == VectorLayerImporter.CoordSystem.VN2000_6DEG

    val hasChangedCm = isVn2000 && selectedCm != layer.detectedCm
    val dN = dNText.replace(",",".").toDoubleOrNull() ?: 0.0
    val dE = dEText.replace(",",".").toDoubleOrNull() ?: 0.0
    val hasOffset = dN != 0.0 || dE != 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Căn chỉnh toạ độ", fontWeight = FontWeight.Bold) },
        text  = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Thông tin hệ toạ độ đang dùng ──────────────
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Hệ toạ độ phát hiện:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when (layer.coordSystem) {
                                VectorLayerImporter.CoordSystem.VN2000_3DEG -> "VN-2000 múi 3°"
                                VectorLayerImporter.CoordSystem.VN2000_6DEG -> "VN-2000 múi 6°"
                                VectorLayerImporter.CoordSystem.WGS84        -> "WGS-84 (lat/lon)"
                                else                                          -> "Hệ phẳng (không rõ)"
                            },
                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                        )
                        if (layer.offsetN != 0.0 || layer.offsetE != 0.0) {
                            Text(
                                "Offset hiện tại: ΔN=%.2fm  ΔE=%.2fm".format(layer.offsetN, layer.offsetE),
                                fontSize = 11.sp, color = Color(0xFFFF6D00)
                            )
                        }
                    }
                }

                // ── Đổi kinh tuyến trục (chỉ cho VN-2000) ──────
                if (isVn2000) {
                    Text(
                        "Kinh tuyến trục (KTT)",
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                    )
                    Text(
                        "Nếu file lệch xa bản đồ nền, hãy thử đổi KTT cho đúng múi chiếu.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Grid các chip kinh tuyến trục
                    val cmList = VectorLayerImporter.VN2000_CENTRAL_MERIDIANS
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cmList.chunked(5).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { cm ->
                                    val isSelected = cm == selectedCm
                                    Surface(
                                        modifier  = Modifier.clickable { selectedCm = cm },
                                        color     = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                        shape     = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            text     = if (cm % 1.0 == 0.0) "%.0f°".format(cm) else "%.2f°".format(cm),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            color    = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // ── Offset ΔN / ΔE ───────────────────────────────
                Text("Dịch chuyển toạ độ (mét)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "Nếu layer lệch nhẹ so với bản đồ nền, nhập ΔN/ΔE để dịch chuyển.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value         = dNText,
                        onValueChange = { dNText = it },
                        label         = { Text("ΔN (Bắc+)") },
                        modifier      = Modifier.weight(1f),
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("mét") }
                    )
                    OutlinedTextField(
                        value         = dEText,
                        onValueChange = { dEText = it },
                        label         = { Text("ΔE (Đông+)") },
                        modifier      = Modifier.weight(1f),
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("mét") }
                    )
                }

                if (!hasChangedCm && !hasOffset) {
                    Text(
                        "Chưa có thay đổi nào.",
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = {
                    var newLayer = layer
                    // 1. Rezone (đổi múi chiếu) nếu KTT thay đổi
                    if (hasChangedCm) {
                        newLayer = VectorLayerImporter.applyRezone(newLayer, selectedCm)
                    }
                    // 2. Áp dụng offset
                    if (hasOffset) {
                        newLayer = VectorLayerImporter.applyOffset(newLayer, dN, dE)
                    }
                    onApply(newLayer)
                },
                enabled = hasChangedCm || hasOffset
            ) {
                Text("Áp dụng")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}

// ════════════════════════════════════════════════════════════
// CoordinateToggleOverlay
// ════════════════════════════════════════════════════════════

@Composable
private fun CoordinateToggleOverlay(gnss: GnssStatus) {
    var showWgs by remember { mutableStateOf(true) }
    Surface(
        modifier = Modifier.clickable { showWgs = !showWgs },
        color    = Color.Black.copy(alpha = 0.62f),
        shape    = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (showWgs) {
                Text("WGS-84  ▼ VN-2000", color = Color(0xFF80DEEA), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("φ %.7f°".format(gnss.latitude),  color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("λ %.7f°".format(gnss.longitude), color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("h %.2f m  •  %s".format(gnss.altitude, gnss.localTime), color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp)
            } else {
                gnss.vn2000?.let { vn ->
                    Text("VN-2000 ${vn.zoneName}  ▼ WGS-84", color = Color(0xFFFFCC80), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("X  ${vn.northingFormatted}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Y  ${vn.eastingFormatted}",  color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("h %.2f m  •  %s".format(gnss.altitude, gnss.localTime), color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp)
                } ?: Text("VN-2000: chờ GPS...", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// MeasurePointSheet
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurePointSheet(
    gnss         : GnssStatus,
    pointCode    : String,
    note         : String,
    isSaving     : Boolean,
    onCodeChange : (String) -> Unit,
    onNoteChange : (String) -> Unit,
    onSave       : () -> Unit,
    onDismiss    : () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Đo điểm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(color = Color(android.graphics.Color.parseColor(gnss.fixColorHex)), shape = RoundedCornerShape(6.dp)) {
                    Text(gnss.fixLabel, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    gnss.vn2000?.let { vn ->
                        Text("X ${vn.northingFormatted}   Y ${vn.eastingFormatted}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Text("φ %.6f°   λ %.6f°   h %.2fm".format(gnss.latitude, gnss.longitude, gnss.altitude), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${gnss.satelliteCount} vệ tinh  •  HDOP %.1f  •  %s (UTC+7)".format(gnss.hdop, gnss.localTime), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(value = pointCode, onValueChange = onCodeChange, label = { Text("Mã điểm") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), supportingText = { Text("Tự động tăng sau mỗi lần lưu") })
            OutlinedTextField(value = note, onValueChange = onNoteChange, label = { Text("Ghi chú (tuỳ chọn)") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
            Button(
                onClick  = onSave,
                enabled  = gnss.hasFix && !isSaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = if (gnss.isRtk) Color(0xFF1B5E20) else MaterialTheme.colorScheme.primary)
            ) {
                if (isSaving) { CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Đang lưu...") }
                else { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text(if (gnss.hasFix) "Lưu điểm  ($pointCode)" else "Chưa có tín hiệu GPS", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// PointDetailSheet
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointDetailSheet(point: SurveyPoint, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(point.pointCode, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Text(point.fixLabel, fontWeight = FontWeight.SemiBold, color = Color(android.graphics.Color.parseColor(point.fixColorHex)))
            HorizontalDivider()
            DetailRow("X (Northing)", "%.3f m".format(point.northing))
            DetailRow("Y (Easting)",  "%.3f m".format(point.easting))
            DetailRow("Vĩ độ",        "%.8f°".format(point.latitude))
            DetailRow("Kinh độ",      "%.8f°".format(point.longitude))
            DetailRow("Độ cao",       "%.3f m".format(point.altitude))
            DetailRow("HDOP",         "%.1f".format(point.hdop))
            DetailRow("Vệ tinh",      "${point.satelliteCount}")
            DetailRow("Thời gian",    point.timestampFormatted)
            if (point.note.isNotEmpty()) DetailRow("Ghi chú", point.note)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

// ════════════════════════════════════════════════════════════
// Drawable helpers
// ════════════════════════════════════════════════════════════

private fun createCircleDrawable(context: Context, color: Int): android.graphics.drawable.Drawable {
    val size = 28
    val bmp  = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c    = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.WHITE;  c.drawCircle(size/2f, size/2f, size/2f, p)
    p.color = color;               c.drawCircle(size/2f, size/2f, size/2f - 3f, p)
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

private fun createGpsDrawable(context: Context): android.graphics.drawable.Drawable {
    val size = 32
    val bmp  = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c    = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.WHITE;              c.drawCircle(size/2f, size/2f, size/2f, p)
    p.color = AndroidColor.parseColor("#FF1B5E20"); c.drawCircle(size/2f, size/2f, size/2f-3f, p)
    p.color = AndroidColor.WHITE; p.strokeWidth = 2f; p.style = Paint.Style.STROKE
    val cx=size/2f; val cy=size/2f; val r=size/2f-8f
    c.drawLine(cx, cy-r, cx, cy+r, p); c.drawLine(cx-r, cy, cx+r, cy, p)
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

/**
 * Nhãn hỗn số tại tâm thửa:
 *     LoaiDat            (vd ONT+CLN, xanh lá)
 *     SoThua             (đỏ)
 *     ─────────          (vạch phân số)
 *     DienTich           (xanh dương)
 */
private fun makeParcelLabelIcon(
    context: Context, loaiDat: String, soThua: String, dienTich: String
): android.graphics.drawable.Drawable {
    val d     = context.resources.displayMetrics.density
    val ts    = 11f * d
    val pad   = 4f * d
    val lineH = ts * 1.30f

    fun mkPaint(color: Int, stroke: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ts; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        if (stroke) { style = Paint.Style.STROKE; strokeWidth = 3f; this.color = AndroidColor.WHITE }
        else this.color = color
    }

    val rows = ArrayList<Pair<String, Int>>()
    if (loaiDat.isNotBlank()) rows.add(loaiDat to AndroidColor.parseColor("#1B5E20"))
    rows.add(soThua   to AndroidColor.parseColor("#B71C1C"))
    rows.add(dienTich to AndroidColor.parseColor("#0D47A1"))

    val measure = mkPaint(AndroidColor.BLACK, false)
    val w = (rows.maxOf { measure.measureText(it.first) } + pad * 2).toInt().coerceAtLeast(1)
    val h = (lineH * rows.size + pad * 2).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    var y = pad + ts
    val soThuaRow = if (loaiDat.isNotBlank()) 1 else 0

    rows.forEachIndexed { i, (txt, col) ->
        c.drawText(txt, cx, y, mkPaint(col, true))
        c.drawText(txt, cx, y, mkPaint(col, false))
        if (i == soThuaRow) {   // vạch phân số ngay dưới số thửa
            val ly = y + ts * 0.30f
            c.drawLine(pad, ly, w - pad, ly, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; strokeWidth = 3.5f })
            c.drawLine(pad, ly, w - pad, ly, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.BLACK; strokeWidth = 1.6f })
        }
        y += lineH
    }
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

/**
 * Tạo icon nhỏ màu cam cho node vector tappable.
 * POINT → hình tròn lớn hơn / POLYLINE,POLYGON → hình tròn nhỏ
 */
private fun createVectorNodeDrawable(
    context     : Context,
    featureType : VectorLayerImporter.FeatureType,
    highlighted : Boolean = false
): android.graphics.drawable.Drawable {
    val base = when (featureType) {
        VectorLayerImporter.FeatureType.POINT    -> 26
        VectorLayerImporter.FeatureType.POLYLINE -> 18
        VectorLayerImporter.FeatureType.POLYGON  -> 18
    }
    val size = if (highlighted) base + 8 else base   // node highlight to hơn
    val fillColor = if (highlighted) AndroidColor.parseColor("#FF00E5FF")  // cyan
                    else AndroidColor.parseColor("#FFFF6D00")              // cam
    val bmp  = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c    = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.WHITE; c.drawCircle(size/2f, size/2f, size/2f, p)
    p.color = fillColor;          c.drawCircle(size/2f, size/2f, size/2f-3f, p)
    // Dấu "+" ở giữa để phân biệt với survey marker
    p.color = AndroidColor.WHITE; p.strokeWidth = 1.5f; p.style = Paint.Style.STROKE
    val cx=size/2f; val cy=size/2f; val r=size/2f-7f
    if (r > 1f) { c.drawLine(cx, cy-r, cx, cy+r, p); c.drawLine(cx-r, cy, cx+r, cy, p) }
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}