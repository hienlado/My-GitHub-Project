package com.hien.rtkmultidevice.ui.screens.traverse

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.hien.rtkmultidevice.domain.model.Traverse
import com.hien.rtkmultidevice.domain.model.TraversePoint
import com.hien.rtkmultidevice.export.ExportManager
import com.hien.rtkmultidevice.ui.components.FloatingGnssCard
import com.hien.rtkmultidevice.ui.screens.survey.PointFormatDialog
import com.hien.rtkmultidevice.ui.screens.map.GnssMapView
import com.hien.rtkmultidevice.ui.screens.map.MapLabelConfig
import org.osmdroid.util.GeoPoint

/**
 * TraverseScreen — Màn hình đo tuyến (Traverse).
 *
 * Layout:
 *   - Nếu chưa có tuyến nào mở: danh sách tuyến + nút tạo mới
 *   - Nếu đang đo tuyến: bản đồ nền + polyline live + panel thêm điểm
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraverseScreen(
    projectId       : Int,
    onNavigateBack  : () -> Unit,
    viewModel       : TraverseViewModel = hiltViewModel()
) {
    val traverses     by viewModel.traverses.collectAsStateWithLifecycle()
    val activeTraverse by viewModel.activeTraverse.collectAsStateWithLifecycle()
    val gnss          by viewModel.gnssStatus.collectAsStateWithLifecycle()
    val pointCode     by viewModel.pointCode.collectAsStateWithLifecycle()
    val isSaving      by viewModel.isSaving.collectAsStateWithLifecycle()
    val feedback      by viewModel.feedback.collectAsStateWithLifecycle()
    val error         by viewModel.error.collectAsStateWithLifecycle()
    val exportResult  by viewModel.exportResult.collectAsStateWithLifecycle()

    var showNewDialog  by remember { mutableStateOf(false) }
    var newName        by remember { mutableStateOf("") }
    var isClosed       by remember { mutableStateOf(false) }
    var deleteTarget   by remember { mutableStateOf<Traverse?>(null) }
    var exportTarget   by remember { mutableStateOf<Traverse?>(null) }
    var followGps      by remember { mutableStateOf(true) }
    val snackbarHost   = remember { SnackbarHostState() }
    val context        = LocalContext.current

    LaunchedEffect(feedback) {
        feedback?.let { snackbarHost.showSnackbar(it); viewModel.clearFeedback() }
    }
    LaunchedEffect(error) {
        error?.let { snackbarHost.showSnackbar("⚠ $it"); viewModel.clearError() }
    }
    // Xử lý kết quả export — giống SurveyScreen
    LaunchedEffect(exportResult) {
        when (val result = exportResult) {
            is ExportManager.ExportResult.Success -> {
                snackbarHost.showSnackbar("✓ Đã lưu: ${result.fileName} (${result.rowCount} điểm)")
                val shareIntent = viewModel.createShareIntent(result)
                context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ file tuyến đo"))
                viewModel.clearExportResult()
            }
            is ExportManager.ExportResult.Error -> {
                snackbarHost.showSnackbar("⚠ ${result.message}")
                viewModel.clearExportResult()
            }
            null -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            activeTraverse?.name ?: "Đo tuyến",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (activeTraverse != null)
                                "${activeTraverse!!.pointCount} điểm  •  ${activeTraverse!!.totalLengthFormatted}"
                            else "${traverses.size} tuyến",
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (activeTraverse != null) viewModel.closeTraverse()
                        else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White)
                    }
                },
                actions = {
                    if (activeTraverse != null) {
                        // Toggle tuyến đóng/mở
                        IconButton(onClick = viewModel::toggleClosed) {
                            Icon(
                                if (activeTraverse!!.isClosed) Icons.Default.LockOpen
                                else Icons.Default.Lock,
                                "Đóng/Mở tuyến",
                                tint = if (activeTraverse!!.isClosed) Color(0xFFFF8F00) else Color.White
                            )
                        }
                        // GPS Follow
                        IconButton(onClick = { followGps = !followGps }) {
                            Icon(
                                if (followGps) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                                "Follow GPS",
                                tint = if (followGps) Color(0xFF80FF80) else Color.White
                            )
                        }
                        // Xoá điểm cuối
                        if (activeTraverse!!.points.isNotEmpty()) {
                            IconButton(onClick = viewModel::removeLastPoint) {
                                Icon(Icons.Default.Undo, "Xoá điểm cuối", tint = Color.White)
                            }
                            // Xuất tuyến ra .csv/.txt
                            IconButton(onClick = { exportTarget = activeTraverse }) {
                                Icon(Icons.Default.FileUpload, "Xuất tuyến", tint = Color.White)
                            }
                        }
                    } else {
                        IconButton(onClick = { showNewDialog = true }) {
                            Icon(Icons.Default.Add, "Tạo tuyến mới", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = Color(0xFF1565C0),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (activeTraverse != null) {
            // ── Chế độ đo tuyến: bản đồ + panel ──────────────
            TraverseMeasureMode(
                modifier       = Modifier.padding(padding),
                traverse       = activeTraverse!!,
                gnss           = gnss,
                pointCode      = pointCode,
                isSaving       = isSaving,
                followGps      = followGps,
                onCodeChange   = viewModel::onPointCodeChange,
                onAddPoint     = viewModel::addPoint,
                onScrolled     = { followGps = false }
            )
        } else {
            // ── Danh sách tuyến ────────────────────────────────
            TraverseListMode(
                modifier        = Modifier.padding(padding),
                traverses       = traverses,
                onOpen          = viewModel::openTraverse,
                onDelete        = { deleteTarget = it },
                onExport        = { exportTarget = it }
            )
        }
    }

    // ── Dialog tạo tuyến mới ─────────────────────────────
    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("Tạo tuyến đo mới") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        label         = { Text("Tên tuyến") },
                        placeholder   = { Text("VD: Tuyến AB, Ranh T001...") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isClosed, onCheckedChange = { isClosed = it })
                        Text("Tuyến đóng (tự đóng về điểm đầu)")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createTraverse(newName, isClosed)
                    newName = ""; isClosed = false
                    showNewDialog = false
                }) { Text("Tạo") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false; newName = ""; isClosed = false }) { Text("Huỷ") }
            }
        )
    }

    // ── Dialog xác nhận xoá ──────────────────────────────
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Xoá tuyến?") },
            text  = { Text("Tuyến \"${t.name}\" và tất cả ${t.pointCount} điểm sẽ bị xoá vĩnh viễn.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteTraverse(t); deleteTarget = null }) {
                    Text("Xoá", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Huỷ") } }
        )
    }

    // ── Dialog chọn định dạng xuất .csv/.txt ─────────────
    exportTarget?.let { t ->
        PointFormatDialog(
            title         = "Xuất tuyến \"${t.name}\"",
            showExtension = true,
            showHeader    = true,
            onConfirm     = { fmt ->
                viewModel.exportTraverse(t, fmt)
                exportTarget = null
            },
            onDismiss     = { exportTarget = null }
        )
    }
}

// ════════════════════════════════════════════════════════════
// TraverseListMode — Danh sách tuyến
// ════════════════════════════════════════════════════════════

@Composable
private fun TraverseListMode(
    modifier  : Modifier,
    traverses : List<Traverse>,
    onOpen    : (Traverse) -> Unit,
    onDelete  : (Traverse) -> Unit,
    onExport  : (Traverse) -> Unit
) {
    if (traverses.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Timeline, null, Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(0.3f))
                Spacer(Modifier.height(12.dp))
                Text("Chưa có tuyến đo nào", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                Text("Nhấn + để tạo tuyến mới", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f))
            }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(traverses, key = { it.id }) { t ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(t) }) {
                Row(Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t.name, fontWeight = FontWeight.SemiBold)
                            if (t.isClosed) Surface(
                                color = Color(0xFFE65100).copy(0.15f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) { Text("Đóng", fontSize = 10.sp,
                                    color = Color(0xFFE65100),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) }
                        }
                        Text(
                            "${t.pointCount} điểm  •  ${t.totalLengthFormatted}" +
                            if (t.isClosed && t.areaM2 > 0) "  •  ${t.areaFormatted}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(onClick = { onOpen(t) }) {
                            Icon(Icons.Default.Edit, "Mở tuyến", tint = Color(0xFF1565C0))
                        }
                        if (t.pointCount > 0) {
                            IconButton(onClick = { onExport(t) }) {
                                Icon(Icons.Default.FileUpload, "Xuất tuyến", tint = Color(0xFF2E7D32))
                            }
                        }
                        IconButton(onClick = { onDelete(t) }) {
                            Icon(Icons.Default.Delete, "Xoá", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// TraverseMeasureMode — Đo tuyến với bản đồ nền
// ════════════════════════════════════════════════════════════

@Composable
private fun TraverseMeasureMode(
    modifier     : Modifier,
    traverse     : Traverse,
    gnss         : com.hien.rtkmultidevice.domain.model.GnssStatus,
    pointCode    : String,
    isSaving     : Boolean,
    followGps    : Boolean,
    onCodeChange : (String) -> Unit,
    onAddPoint   : () -> Unit,
    onScrolled   : () -> Unit
) {
    val fixColor = when (gnss.fixQuality) {
        4 -> Color(0xFF2E7D32); 5 -> Color(0xFF558B2F)
        2 -> Color(0xFF1565C0); else -> Color(0xFFF57F17)
    }

    Box(modifier.fillMaxSize()) {
        // ── Bản đồ nền ───────────────────────────────────
        GnssMapView(
            modifier                  = Modifier.fillMaxSize(),
            gnss                      = gnss,
            followGps                 = followGps,
            labelConfig               = MapLabelConfig(showPointCode = true),
            showZoomControls          = true,
            zoomControlsBottomPadding = 160.dp,   // panel Traverse cao ~150dp
            onScrolled                = onScrolled,
        )

        // ── Fix status chip ────────────────────────────
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            color    = fixColor.copy(0.9f),
            shape    = MaterialTheme.shapes.small
        ) {
            Text(
                "${gnss.fixLabel}  •  ${gnss.satelliteCount}sv  •  HDOP${"%.1f".format(gnss.hdop)}",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
        }

        // ── FloatingGnssCard ──────────────────────────
        FloatingGnssCard(
            gnss     = gnss,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
        )

        // ── Panel thêm điểm — phía dưới ───────────────
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color    = MaterialTheme.colorScheme.surface.copy(0.93f),
            tonalElevation = 0.dp,
            shape    = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // VN-2000 live
                if (gnss.vn2000 != null) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("X: ${"%.3f".format(gnss.vn2000!!.northing)}m",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text("Y: ${"%.3f".format(gnss.vn2000!!.easting)}m",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text("h: ${"%.3f".format(gnss.altitude)}m",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value         = pointCode,
                        onValueChange = onCodeChange,
                        label         = { Text("Mã điểm") },
                        singleLine    = true,
                        modifier      = Modifier.weight(1f)
                    )
                    Button(
                        onClick  = onAddPoint,
                        enabled  = gnss.hasFix && !isSaving,
                        colors   = ButtonDefaults.buttonColors(containerColor = fixColor),
                        modifier = Modifier.height(56.dp)
                    ) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), Color.White, 2.dp)
                        else Icon(Icons.Default.AddLocation, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Thêm\nđiểm", fontSize = 11.sp, lineHeight = 13.sp)
                    }
                }

                // Stats
                if (traverse.points.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("${traverse.pointCount} điểm",
                            style = MaterialTheme.typography.labelSmall)
                        Text("Dài: ${traverse.totalLengthFormatted}",
                            style = MaterialTheme.typography.labelSmall)
                        if (traverse.isClosed && traverse.areaM2 > 0)
                            Text("S: ${traverse.areaFormatted}",
                                style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
