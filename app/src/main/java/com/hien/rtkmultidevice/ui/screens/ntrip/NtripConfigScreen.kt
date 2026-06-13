package com.hien.rtkmultidevice.ui.screens.ntrip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.core.gnss.ntrip.NtripMountpointEntry

/**
 * NtripConfigScreen — Màn hình nhập cấu hình NTRIP Caster.
 *
 * Thay thế hoàn toàn NtripConfig object hardcode.
 * User nhập: Host, Port, Mountpoint, Username, Password
 * → Lưu vào DataStore → Dùng khi kết nối NTRIP
 *
 * Tính năng mới: Nút "Chọn từ danh sách" mở MountpointPickerDialog,
 * hiển thị tất cả mountpoint từ sourcetable, ưu tiên VRS+Datum (1021-1027).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NtripConfigScreen(
    onSaved   : () -> Unit,
    viewModel : NtripConfigViewModel = hiltViewModel()
) {
    val host                  by viewModel.host.collectAsStateWithLifecycle()
    val port                  by viewModel.port.collectAsStateWithLifecycle()
    val mountPoint            by viewModel.mountPoint.collectAsStateWithLifecycle()
    val username              by viewModel.username.collectAsStateWithLifecycle()
    val password              by viewModel.password.collectAsStateWithLifecycle()
    val isSaving              by viewModel.isSaving.collectAsStateWithLifecycle()
    val sourcetableEntries    by viewModel.sourcetableEntries.collectAsStateWithLifecycle()
    val isFetchingSourcetable by viewModel.isFetchingSourcetable.collectAsStateWithLifecycle()
    val sourcetableError      by viewModel.sourcetableError.collectAsStateWithLifecycle()

    var showPassword        by remember { mutableStateOf(false) }
    var showPickerDialog    by remember { mutableStateOf(false) }

    // Mở dialog khi entries đã load xong (và chưa có lỗi)
    LaunchedEffect(sourcetableEntries, isFetchingSourcetable) {
        if (!isFetchingSourcetable && sourcetableEntries.isNotEmpty()) {
            showPickerDialog = true
        }
    }

    // Hiện MountpointPickerDialog
    if (showPickerDialog) {
        MountpointPickerDialog(
            entries   = sourcetableEntries,
            onSelect  = { entry ->
                viewModel.selectMountpoint(entry)
                showPickerDialog = false
                viewModel.clearSourcetable()
            },
            onDismiss = {
                showPickerDialog = false
                viewModel.clearSourcetable()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cấu hình NTRIP") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onSaved) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Hướng dẫn ────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = "Nhập thông tin từ đơn vị cung cấp dịch vụ NTRIP RTK.\n" +
                           "Dữ liệu được lưu an toàn trên thiết bị.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // ── Host ─────────────────────────────────────────
            OutlinedTextField(
                value         = host,
                onValueChange = viewModel::onHostChanged,
                label         = { Text("Địa chỉ Caster (Host / IP)") },
                placeholder   = { Text("VD: 14.238.1.125 hoặc ntrip.vn") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true
            )

            // ── Port ─────────────────────────────────────────
            OutlinedTextField(
                value         = port,
                onValueChange = viewModel::onPortChanged,
                label         = { Text("Cổng (Port)") },
                placeholder   = { Text("Mặc định: 2101") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // ── Mountpoint + nút Browse ───────────────────────
            OutlinedTextField(
                value         = mountPoint,
                onValueChange = viewModel::onMountPointChanged,
                label         = { Text("Điểm Mount (Mountpoint)") },
                placeholder   = { Text("VD: VRS_VN2000 hoặc HANOI_RTK") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                trailingIcon  = {
                    // Nút mở sourcetable browser
                    IconButton(
                        onClick  = { viewModel.fetchSourcetable() },
                        enabled  = !isFetchingSourcetable
                    ) {
                        if (isFetchingSourcetable) {
                            CircularProgressIndicator(
                                modifier  = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = "Chọn từ danh sách",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )

            // Thông báo lỗi sourcetable (nếu có)
            sourcetableError?.let { err ->
                Text(
                    text  = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Gợi ý sử dụng Browse
            Text(
                text  = "Nhấn  ☰  để tải danh sách mountpoint từ Caster.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            HorizontalDivider()

            Text(
                "Xác thực",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            // ── Username ─────────────────────────────────────
            OutlinedTextField(
                value         = username,
                onValueChange = viewModel::onUsernameChanged,
                label         = { Text("Tên đăng nhập") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true
            )

            // ── Password ─────────────────────────────────────
            OutlinedTextField(
                value         = password,
                onValueChange = viewModel::onPasswordChanged,
                label         = { Text("Mật khẩu") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                visualTransformation = if (showPassword)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                trailingIcon  = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (showPassword) "Ẩn mật khẩu" else "Hiện mật khẩu"
                        )
                    }
                }
            )

            Spacer(Modifier.height(8.dp))

            // ── Nút Lưu ─────────────────────────────────────
            Button(
                onClick  = { viewModel.saveConfig(onSaved) },
                enabled  = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color    = Color.White
                    )
                } else {
                    Text("Lưu cấu hình", fontSize = 16.sp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// MountpointPickerDialog — Danh sách mountpoint từ sourcetable
// ═══════════════════════════════════════════════════════════

/**
 * Dialog hiển thị danh sách mountpoint để user chọn.
 *
 * Thứ tự ưu tiên (đã sắp xếp từ ViewModel / Fetcher):
 *   1. VRS + Datum (1021-1027) → badge xanh lá  ← QUAN TRỌNG cho VRS VN-2000
 *   2. VRS (không có 1021-1027)
 *   3. Single-base + MSM
 *   4. Còn lại
 */
@Composable
fun MountpointPickerDialog(
    entries  : List<NtripMountpointEntry>,
    onSelect : (NtripMountpointEntry) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier      = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape         = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header ────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Column {
                        Text(
                            "Chọn Mountpoint",
                            color      = Color.White,
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${entries.size} mountpoint — ưu tiên VRS + Datum 1021-1027",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // ── Chú thích màu sắc ─────────────────────
                Row(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    LegendChip(color = Color(0xFF2E7D32), label = "VRS + Datum")
                    LegendChip(color = Color(0xFF1565C0), label = "VRS")
                    LegendChip(color = MaterialTheme.colorScheme.outline, label = "Single-base")
                }

                HorizontalDivider()

                // ── Danh sách mountpoint ──────────────────
                LazyColumn(
                    modifier            = Modifier.weight(1f),
                    contentPadding      = PaddingValues(vertical = 4.dp)
                ) {
                    items(entries, key = { it.mountpoint }) { entry ->
                        MountpointItem(
                            entry    = entry,
                            onClick  = { onSelect(entry) }
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }

                // ── Nút Đóng ─────────────────────────────
                HorizontalDivider()
                TextButton(
                    onClick  = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text("Đóng", fontSize = 15.sp)
                }
            }
        }
    }
}

// ── MountpointItem ────────────────────────────────────────

@Composable
private fun MountpointItem(
    entry  : NtripMountpointEntry,
    onClick: () -> Unit
) {
    // Màu nền theo loại mountpoint
    val bgColor = when {
        entry.hasDatumTransformation && entry.isVrs ->
            Color(0xFF2E7D32).copy(alpha = 0.08f)   // xanh lá nhạt — tốt nhất
        entry.isVrs ->
            Color(0xFF1565C0).copy(alpha = 0.06f)   // xanh dương nhạt
        else ->
            Color.Transparent
    }

    val accentColor = when {
        entry.hasDatumTransformation && entry.isVrs -> Color(0xFF2E7D32)
        entry.isVrs                                 -> Color(0xFF1565C0)
        else                                        -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        // ── Tên mountpoint + badges ────────────────────
        Column(modifier = Modifier.weight(1f)) {
            // Tên chính
            Text(
                text       = entry.displayLabel,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color      = accentColor
            )

            // Subtitle: format + datum + MSM + navSystem
            Text(
                text  = entry.displaySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            // Badge row
            if (entry.hasDatumTransformation || entry.isVrs || entry.hasMsmCorrections) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.hasDatumTransformation) {
                        SmallBadge("✓ Datum 1021-1027", Color(0xFF2E7D32))
                    }
                    if (entry.isVrs) {
                        SmallBadge("VRS", Color(0xFF1565C0))
                    }
                    if (entry.hasMsmCorrections) {
                        SmallBadge("MSM", Color(0xFF6A1B9A))
                    }
                }
            }
        }

        // ── Tọa độ (lat/lon) ──────────────────────────
        if (entry.latitude != 0.0 || entry.longitude != 0.0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text  = "%.1f°N\n%.1f°E".format(entry.latitude, entry.longitude),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

// ── Helper composables ────────────────────────────────────

@Composable
private fun SmallBadge(text: String, color: Color) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = Color.White,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
