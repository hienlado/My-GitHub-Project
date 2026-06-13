package com.hien.rtkmultidevice.ui.screens.survey

import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import com.hien.rtkmultidevice.export.FieldDelimiter
import com.hien.rtkmultidevice.export.PointFileFormat

/**
 * EnhancedPointListTab — Tab "Danh sách điểm" nâng cao.
 *
 * Chức năng:
 *   • Tìm kiếm theo mã điểm / ghi chú
 *   • Lọc theo chất lượng fix (Fixed/Float/Single/Import)
 *   • Chọn bằng checklist từng điểm + Chọn tất cả / Bỏ chọn
 *   • Add: thêm điểm thủ công bằng toạ độ VN-2000
 *   • Import: đọc file .csv/.txt với thứ tự trường tuỳ chọn
 *   • Export: xuất tất cả hoặc các điểm đã chọn, định dạng tuỳ chọn
 *   • Đảo XY↔YX cho các điểm đã chọn
 *   • Properties: xem + sửa thông tin điểm (chạm vào điểm)
 *   • Xoá các điểm đã chọn
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EnhancedPointListTab(
    modifier     : Modifier,
    points       : List<SurveyPoint>,
    isExporting  : Boolean,
    onAdd        : (code: String, n: Double, e: Double, h: Double, note: String) -> Unit,
    onUpdate     : (SurveyPoint, code: String, n: Double, e: Double, h: Double, note: String) -> Unit,
    onDeleteMany : (List<SurveyPoint>) -> Unit,
    onSwapXY     : (List<SurveyPoint>) -> Unit,
    onImportFile : (Uri, PointFileFormat) -> Unit,
    onExport     : (PointFileFormat, List<SurveyPoint>?) -> Unit
) {
    // ── State ────────────────────────────────────────────────
    var searchQuery   by remember { mutableStateOf("") }
    var fixFilter     by remember { mutableStateOf<Int?>(null) }   // null = tất cả
    var selectedIds   by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var propsTarget   by remember { mutableStateOf<SurveyPoint?>(null) }
    var showImportFmt by remember { mutableStateOf(false) }
    var showExportFmt by remember { mutableStateOf(false) }
    var showDeleteCfm by remember { mutableStateOf(false) }
    var pendingImportFmt by remember { mutableStateOf<PointFileFormat?>(null) }

    // File picker cho Import — format đã chọn trước đó (pendingImportFmt)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val fmt = pendingImportFmt
        if (uri != null && fmt != null) onImportFile(uri, fmt)
        pendingImportFmt = null
    }

    // ── Lọc dữ liệu hiển thị ─────────────────────────────────
    val displayed = remember(points, searchQuery, fixFilter) {
        points.filter { p ->
            val matchSearch = searchQuery.isBlank() ||
                p.pointCode.contains(searchQuery, ignoreCase = true) ||
                p.note.contains(searchQuery, ignoreCase = true)
            val matchFix = when (fixFilter) {
                null -> true
                0    -> p.fixQuality == 0                       // điểm import / nhập tay
                1    -> p.fixQuality in 1..2                    // Single/DGPS
                else -> p.fixQuality == fixFilter               // 4=Fixed, 5=Float
            }
            matchSearch && matchFix
        }
    }
    val selectedPoints = remember(points, selectedIds) { points.filter { it.id in selectedIds } }
    val allDisplayedSelected = displayed.isNotEmpty() && displayed.all { it.id in selectedIds }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Thanh tìm kiếm ────────────────────────────────────
        OutlinedTextField(
            value         = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder   = { Text("Tìm mã điểm / ghi chú…", fontSize = 13.sp) },
            leadingIcon   = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            trailingIcon  = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, "Xoá tìm kiếm", Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            modifier   = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        // ── Bộ lọc fix quality ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChipItem("Tất cả (${points.size})", fixFilter == null, MaterialTheme.colorScheme.primary) { fixFilter = null }
            FilterChipItem("Fixed (${points.count { it.fixQuality == 4 }})", fixFilter == 4, Color(0xFF2E7D32)) { fixFilter = if (fixFilter == 4) null else 4 }
            FilterChipItem("Float (${points.count { it.fixQuality == 5 }})", fixFilter == 5, Color(0xFF558B2F)) { fixFilter = if (fixFilter == 5) null else 5 }
            FilterChipItem("Single (${points.count { it.fixQuality in 1..2 }})", fixFilter == 1, Color(0xFFF57F17)) { fixFilter = if (fixFilter == 1) null else 1 }
            FilterChipItem("Import (${points.count { it.fixQuality == 0 }})", fixFilter == 0, Color(0xFF6D4C41)) { fixFilter = if (fixFilter == 0) null else 0 }
        }

        // ── Thanh công cụ ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chọn tất cả (theo danh sách đang hiển thị)
            Checkbox(
                checked = allDisplayedSelected,
                onCheckedChange = { checked ->
                    selectedIds = if (checked) selectedIds + displayed.map { it.id }
                                  else selectedIds - displayed.map { it.id }.toSet()
                }
            )
            Text(
                if (selectedIds.isEmpty()) "Chọn tất cả" else "${selectedIds.size} đã chọn",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))

            ToolButton("Thêm", Icons.Default.Add) { showAddDialog = true }
            ToolButton("Import", Icons.Default.FileOpen) { showImportFmt = true }
            ToolButton("Export", Icons.Default.FileDownload, enabled = !isExporting && points.isNotEmpty()) { showExportFmt = true }
            ToolButton("Đảo XY", Icons.Default.SwapHoriz, enabled = selectedPoints.isNotEmpty()) { onSwapXY(selectedPoints) }
            ToolButton(
                "Thuộc tính", Icons.Default.Info,
                enabled = selectedPoints.size == 1
            ) { propsTarget = selectedPoints.firstOrNull() }
            ToolButton(
                "Xoá", Icons.Default.Delete,
                enabled = selectedPoints.isNotEmpty(),
                tint = MaterialTheme.colorScheme.error
            ) { showDeleteCfm = true }
        }

        HorizontalDivider()

        // ── Danh sách ─────────────────────────────────────────
        if (points.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Save, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Chưa có điểm nào",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        "Đo điểm RTK, Import file, hoặc bấm \"Thêm\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxSize(),
                contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Text(
                        "${displayed.size}/${points.size} điểm",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(displayed, key = { it.id }) { point ->
                    PointRow(
                        point      = point,
                        checked    = point.id in selectedIds,
                        onCheck    = { checked ->
                            selectedIds = if (checked) selectedIds + point.id
                                          else selectedIds - point.id
                        },
                        onClick    = { propsTarget = point }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // Dialogs
    // ══════════════════════════════════════════════════════════

    // Thêm điểm thủ công
    if (showAddDialog) {
        PointEditDialog(
            title     = "Thêm điểm thủ công",
            initial   = null,
            onConfirm = { code, n, e, h, note ->
                onAdd(code, n, e, h, note)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // Properties / chỉnh sửa
    propsTarget?.let { point ->
        PointEditDialog(
            title     = "Thuộc tính điểm",
            initial   = point,
            onConfirm = { code, n, e, h, note ->
                onUpdate(point, code, n, e, h, note)
                propsTarget = null
            },
            onDismiss = { propsTarget = null }
        )
    }

    // Chọn định dạng Import → mở file picker
    if (showImportFmt) {
        PointFormatDialog(
            title         = "Định dạng file Import",
            showExtension = false,
            showHeader    = false,
            onConfirm     = { fmt ->
                showImportFmt    = false
                pendingImportFmt = fmt
                importLauncher.launch(arrayOf("text/*", "application/octet-stream", "text/comma-separated-values"))
            },
            onDismiss = { showImportFmt = false }
        )
    }

    // Chọn định dạng Export
    if (showExportFmt) {
        PointFormatDialog(
            title          = "Định dạng file Export",
            showExtension  = true,
            showHeader     = true,
            showScopeOption = selectedPoints.isNotEmpty(),
            selectedCount   = selectedPoints.size,
            onConfirmScoped = { fmt, onlySelected ->
                showExportFmt = false
                onExport(fmt, if (onlySelected) selectedPoints else null)
            },
            onDismiss = { showExportFmt = false }
        )
    }

    // Xác nhận xoá nhiều điểm
    if (showDeleteCfm) {
        AlertDialog(
            onDismissRequest = { showDeleteCfm = false },
            title = { Text("Xoá ${selectedPoints.size} điểm?") },
            text  = { Text("Các điểm đã chọn sẽ bị xoá vĩnh viễn:\n" +
                           selectedPoints.take(8).joinToString(", ") { it.pointCode } +
                           if (selectedPoints.size > 8) "…" else "") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMany(selectedPoints)
                    selectedIds = emptySet()
                    showDeleteCfm = false
                }) { Text("Xoá", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCfm = false }) { Text("Huỷ") }
            }
        )
    }
}

// ════════════════════════════════════════════════════════════
// Components
// ════════════════════════════════════════════════════════════

@Composable
private fun FilterChipItem(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape   = RoundedCornerShape(8.dp),
        color   = if (selected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
        border  = BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) color else color.copy(alpha = 0.4f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 11.sp,
            color    = color,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ToolButton(
    label   : String,
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    enabled : Boolean = true,
    tint    : Color = MaterialTheme.colorScheme.primary,
    onClick : () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.sp, color = if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PointRow(
    point   : SurveyPoint,
    checked : Boolean,
    onCheck : (Boolean) -> Unit,
    onClick : () -> Unit
) {
    val fixColor = try { Color(AndroidColor.parseColor(point.fixColorHex)) } catch (e: Exception) { Color.Gray }
    val isImported = point.fixQuality == 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick     = onClick,
                onLongClick = { onCheck(!checked) }
            ),
        border = BorderStroke(
            if (checked) 1.5.dp else 0.5.dp,
            if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheck, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(4.dp))

            // Mã điểm
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    point.pointCode,
                    modifier   = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 12.sp,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(8.dp))

            // Toạ độ
            Column(Modifier.weight(1f)) {
                Text("X ${"%.3f".format(point.northing)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Y ${"%.3f".format(point.easting)}",  fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "h ${"%.3f".format(point.altitude)}" + if (point.note.isNotEmpty()) "  •  ${point.note.take(24)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Badge fix / import
            Surface(
                color = if (isImported) Color(0xFF6D4C41) else fixColor,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    if (isImported) "IMPORT" else point.fixLabel,
                    modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize   = 9.sp,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// PointEditDialog — Thêm mới / Properties
// ════════════════════════════════════════════════════════════

@Composable
private fun PointEditDialog(
    title     : String,
    initial   : SurveyPoint?,
    onConfirm : (code: String, n: Double, e: Double, h: Double, note: String) -> Unit,
    onDismiss : () -> Unit
) {
    var code by remember { mutableStateOf(initial?.pointCode ?: "") }
    var nTxt by remember { mutableStateOf(initial?.let { "%.3f".format(java.util.Locale.US, it.northing) } ?: "") }
    var eTxt by remember { mutableStateOf(initial?.let { "%.3f".format(java.util.Locale.US, it.easting)  } ?: "") }
    var hTxt by remember { mutableStateOf(initial?.let { "%.3f".format(java.util.Locale.US, it.altitude) } ?: "0") }
    var note by remember { mutableStateOf(initial?.note ?: "") }

    fun String.coord(): Double? = replace(" ", "").replace(",", ".").toDoubleOrNull()
    val nVal = nTxt.coord()
    val eVal = eTxt.coord()
    val hVal = hTxt.coord() ?: 0.0
    val valid = code.isNotBlank() && nVal != null && eVal != null && nVal > 100.0 && eVal > 100.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase().take(20) },
                    label = { Text("Mã điểm") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nTxt, onValueChange = { nTxt = it },
                    label = { Text("X — Northing (m)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = eTxt, onValueChange = { eTxt = it },
                    label = { Text("Y — Easting (m)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hTxt, onValueChange = { hTxt = it },
                    label = { Text("h — Độ cao (m)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Ghi chú / Code") }, maxLines = 2, modifier = Modifier.fillMaxWidth()
                )

                // Thông tin chỉ đọc khi xem Properties
                if (initial != null) {
                    HorizontalDivider()
                    Text("Thông tin đo (chỉ đọc)", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReadOnlyRow("Fix", initial.fixLabel)
                    ReadOnlyRow("Vĩ độ φ",  "%.8f°".format(initial.latitude))
                    ReadOnlyRow("Kinh độ λ","%.8f°".format(initial.longitude))
                    ReadOnlyRow("HDOP",     "%.1f".format(initial.hdop))
                    ReadOnlyRow("Vệ tinh",  "${initial.satelliteCount}")
                    ReadOnlyRow("Thời gian", initial.timestampFormatted)
                }
                if (!valid && (nTxt.isNotEmpty() || eTxt.isNotEmpty())) {
                    Text(
                        "Cần mã điểm + toạ độ VN-2000 hợp lệ (mét, > 100)",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(code, nVal ?: 0.0, eVal ?: 0.0, hVal, note) },
                enabled = valid
            ) { Text(if (initial == null) "Thêm" else "Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

// ════════════════════════════════════════════════════════════
// PointFormatDialog — Chọn định dạng Import/Export
// ════════════════════════════════════════════════════════════

@Composable
internal fun PointFormatDialog(
    title           : String,
    showExtension   : Boolean,
    showHeader      : Boolean,
    showScopeOption : Boolean = false,
    selectedCount   : Int = 0,
    onConfirm       : ((PointFileFormat) -> Unit)? = null,
    onConfirmScoped : ((PointFileFormat, onlySelected: Boolean) -> Unit)? = null,
    onDismiss       : () -> Unit
) {
    var presetIdx     by remember { mutableIntStateOf(0) }
    var delimiter     by remember { mutableStateOf(FieldDelimiter.COMMA) }
    var extension     by remember { mutableStateOf("csv") }
    var includeHeader by remember { mutableStateOf(true) }
    var onlySelected  by remember { mutableStateOf(showScopeOption) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── Thứ tự trường ──────────────────────────
                Text("Thứ tự trường", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                PointFileFormat.FIELD_ORDER_PRESETS.forEachIndexed { idx, preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { presetIdx = idx },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = presetIdx == idx, onClick = { presetIdx = idx })
                        Text(
                            preset.joinToString(", ") { it.shortLabel },
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }

                HorizontalDivider()

                // ── Dấu phân cách ──────────────────────────
                Text("Dấu phân cách", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FieldDelimiter.entries.forEach { d ->
                        FilterChip(
                            selected = delimiter == d,
                            onClick  = { delimiter = d },
                            label    = { Text(d.label, fontSize = 11.sp) }
                        )
                    }
                }

                // ── Đuôi file (Export) ─────────────────────
                if (showExtension) {
                    Text("Phần mở rộng", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("csv", "txt").forEach { ext ->
                            FilterChip(
                                selected = extension == ext,
                                onClick  = { extension = ext },
                                label    = { Text(".$ext", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // ── Header (Export) ────────────────────────
                if (showHeader) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeHeader = !includeHeader },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = includeHeader, onCheckedChange = { includeHeader = it })
                        Text("Ghi dòng tiêu đề (header)", fontSize = 12.sp)
                    }
                }

                // ── Phạm vi xuất ───────────────────────────
                if (showScopeOption) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onlySelected = !onlySelected },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = onlySelected, onCheckedChange = { onlySelected = it })
                        Text("Chỉ xuất $selectedCount điểm đã chọn", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val fmt = PointFileFormat(
                    fields        = PointFileFormat.FIELD_ORDER_PRESETS[presetIdx],
                    delimiter     = delimiter,
                    extension     = extension,
                    includeHeader = includeHeader
                )
                onConfirmScoped?.invoke(fmt, onlySelected) ?: onConfirm?.invoke(fmt)
            }) { Text("Tiếp tục") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}
