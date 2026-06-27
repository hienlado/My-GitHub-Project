package com.hien.rtkmultidevice.ui.screens.coordsettings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone
import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone.ZoneInfo

/**
 * CoordSettingsScreen — Cài đặt múi chiếu VN-2000.
 *
 * Cho phép user:
 *   1. Chọn múi 3° (khuyến nghị RTK) hoặc múi 6°
 *   2. Tự động chọn kinh tuyến trục theo GPS, hoặc ghi đè
 *   3. Chọn kinh tuyến trục từ danh sách 16 múi chính thức VN-2000
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordSettingsScreen(
    onNavigateBack : () -> Unit,
    viewModel      : CoordSettingsViewModel = hiltViewModel()
) {
    val zoneWidth       by viewModel.zoneWidth.collectAsStateWithLifecycle()
    val overrideEnabled by viewModel.overrideEnabled.collectAsStateWithLifecycle()
    val selectedZone    by viewModel.selectedZone.collectAsStateWithLifecycle()
    val calibN          by viewModel.calibN.collectAsStateWithLifecycle()
    val calibE          by viewModel.calibE.collectAsStateWithLifecycle()
    val calibEnabled    by viewModel.calibEnabled.collectAsStateWithLifecycle()
    val calibFeedback   by viewModel.calibFeedback.collectAsStateWithLifecycle()

    val zones = if (zoneWidth == 3) viewModel.zones3Deg else viewModel.zones6Deg

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt toạ độ VN-2000") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor             = MaterialTheme.colorScheme.primary,
                    titleContentColor          = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ── 1. Chọn múi 3° / 6° ───────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Hệ múi chiếu",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Múi 3° — dùng cho đo đạc địa chính, RTK (mặc định)\nMúi 6° — dùng cho bản đồ tổng hợp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(3, 6).forEach { w ->
                            FilterChip(
                                selected = zoneWidth == w,
                                onClick  = { viewModel.onZoneWidthChange(w) },
                                label    = {
                                    Text(
                                        if (w == 3) "Múi 3° (RTK)" else "Múi 6°",
                                        fontWeight = if (zoneWidth == w) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // ── 2. Toggle tự động / ghi đè ───────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Ghi đè kinh tuyến trục",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (overrideEnabled)
                                    "Sử dụng kinh tuyến trục cố định bên dưới"
                                else
                                    "Tự động chọn theo kinh độ GPS hiện tại",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked         = overrideEnabled,
                            onCheckedChange = viewModel::onOverrideEnabledChange
                        )
                    }
                }
            }

            // ── 3. Danh sách kinh tuyến trục ─────────────────
            if (overrideEnabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Chọn kinh tuyến trục",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${zones.size} kinh tuyến trục chính thức VN-2000",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))

                        zones.forEach { zone ->
                            ZoneRow(
                                zone       = zone,
                                isSelected = selectedZone.centralMeridian == zone.centralMeridian,
                                onClick    = { viewModel.onZoneSelected(zone) }
                            )
                        }
                    }
                }
            }

            // ── 4. Xem trước ─────────────────────────────────
            PreviewCard(
                zoneWidth       = zoneWidth,
                overrideEnabled = overrideEnabled,
                selectedZone    = selectedZone
            )

            // ── 5. Hiệu chỉnh về mốc chuẩn ───────────────────
            CalibrationCard(
                calibN       = calibN,
                calibE       = calibE,
                calibEnabled = calibEnabled,
                feedback     = calibFeedback,
                onCompute    = { n, e -> viewModel.computeCalibrationFromKnownMark(n, e) },
                onToggle     = { viewModel.setCalibEnabled(it) },
                onClear      = { viewModel.clearCalibration() },
                onClearFeedback = { viewModel.clearCalibFeedback() }
            )

            // ── Nút Lưu ──────────────────────────────────────
            Button(
                onClick  = { viewModel.saveSettings(onNavigateBack) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Lưu cài đặt", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ════════════════════════════════════════════════════════════
// Sub-composables
// ════════════════════════════════════════════════════════════

@Composable
private fun ZoneRow(
    zone       : ZoneInfo,
    isSelected : Boolean,
    onClick    : () -> Unit
) {
    val bgColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surface

    Surface(
        onClick = onClick,
        color   = bgColor,
        shape   = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick  = onClick
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    zone.label,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize   = 14.sp,
                    color      = if (isSelected) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    zone.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    zoneWidth       : Int,
    overrideEnabled : Boolean,
    selectedZone    : ZoneInfo
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Hệ toạ độ sẽ áp dụng:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (overrideEnabled)
                    Vn2000Zone.zoneName(selectedZone.centralMeridian, zoneWidth)
                else
                    "VN-2000 / Múi ${zoneWidth}° / Tự động theo GPS",
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (overrideEnabled) {
                Spacer(Modifier.height(2.dp))
                Text(
                    selectedZone.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// CalibrationCard — Hiệu chỉnh tịnh tiến về mốc chuẩn (localization)
// ════════════════════════════════════════════════════════════

@Composable
private fun CalibrationCard(
    calibN          : Double,
    calibE          : Double,
    calibEnabled    : Boolean,
    feedback        : String?,
    onCompute       : (Double, Double) -> Unit,
    onToggle        : (Boolean) -> Unit,
    onClear         : () -> Unit,
    onClearFeedback : () -> Unit
) {
    var trueN by remember { mutableStateOf("") }
    var trueE by remember { mutableStateOf("") }
    fun parse(v: String): Double? = v.replace(" ", "").replace(",", "").toDoubleOrNull()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Hiệu chỉnh về mốc chuẩn", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "Đặt máy tại mốc đã biết toạ độ (trạng thái Fixed), nhập toạ độ chuẩn " +
                "rồi bấm \"Tính\". App sẽ tịnh tiến mọi điểm đo về hệ địa phương.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Đang áp dụng", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (calibN == 0.0 && calibE == 0.0) "Chưa hiệu chỉnh"
                        else "ΔN=%.3f m   ΔE=%.3f m".format(calibN, calibE),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Switch(checked = calibEnabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = trueN, onValueChange = { trueN = it },
                label = { Text("X chuẩn — Northing (m)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = trueE, onValueChange = { trueE = it },
                label = { Text("Y chuẩn — Easting (m)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val n = parse(trueN); val e = parse(trueE)
                        if (n != null && e != null) onCompute(n, e)
                    },
                    enabled = parse(trueN) != null && parse(trueE) != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Tính & áp dụng") }
                OutlinedButton(onClick = onClear) { Text("Xoá") }
            }

            feedback?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(msg, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                LaunchedEffect(msg) { kotlinx.coroutines.delay(4000); onClearFeedback() }
            }
        }
    }
}
