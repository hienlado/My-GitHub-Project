package com.hien.rtkmultidevice.ui.screens.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hộp thoại tải bản đồ địa chính theo TỜ từ Cloud (GCS).
 * Gọi từ MapScreen bằng 1 nút; kết quả nạp vào overlay vector (đo/cắm mốc).
 */
/**
 * Nút "Tải bản đồ địa chính" TỰ CHỨA (nút + dialog + trạng thái + thông báo).
 * Chỉ cần đặt MỘT dòng trong MapScreen:  CadastralCloudButton(viewModel)
 */
@Composable
fun CadastralCloudButton(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier,
) {
    val loading by viewModel.cloudLoading.collectAsStateWithLifecycle()
    val message by viewModel.cloudMessage.collectAsStateWithLifecycle()
    val offline by viewModel.offlineMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearCloudMessage()
        }
    }

    IconButton(onClick = { show = true }, modifier = modifier) {
        Icon(Icons.Default.CloudDownload, contentDescription = "Tải bản đồ địa chính")
    }
    if (show) CadastralCloudDialog(
        loading = loading,
        offline = offline,
        hasOffline = viewModel.hasOfflineData(),
        onOfflineChange = { viewModel.setOfflineMode(it) },
        onLoad = { c, s -> viewModel.loadCadastralSheet(c, s); show = false },
        onDismiss = { show = false }
    )
}

/**
 * Nút "Tôi đang ở thửa nào?" — lấy vị trí RTK hiện tại (lat/lon) -> tra ngược xã/tờ/thửa.
 * Đặt 1 dòng trong MapScreen: WhereAmIButton(viewModel, gnss.latitude, gnss.longitude)
 */
@Composable
fun WhereAmIButton(
    viewModel: MapViewModel,
    lat: Double,
    lon: Double,
    modifier: Modifier = Modifier,
) {
    val loading by viewModel.cloudLoading.collectAsStateWithLifecycle()
    val result by viewModel.whereResult.collectAsStateWithLifecycle()

    IconButton(onClick = { viewModel.whereAmINow(lat, lon) }, modifier = modifier, enabled = !loading) {
        Icon(Icons.Default.MyLocation, contentDescription = "Tôi đang ở thửa nào")
    }

    result?.let { r ->
        AlertDialog(
            onDismissRequest = { viewModel.clearWhereResult() },
            title = { Text(if (r.found) "Vị trí hiện tại" else "Không xác định") },
            text = {
                if (r.found) Text(
                    "${r.xaName}\n" +
                    "Tờ ${r.to} — Thửa ${r.thua}\n" +
                    "Diện tích: ${r.dienTich} m²\n" +
                    "Chủ: ${r.tenChu}"
                ) else Text(r.message)
            },
            confirmButton = { TextButton(onClick = { viewModel.clearWhereResult() }) { Text("Đóng") } }
        )
    }
}

/**
 * Nút "Tra thửa theo TOẠ ĐỘ nhập tay" (VN-2000 hoặc WGS-84).
 * Kết quả hiện qua hộp thoại của WhereAmIButton (chung whereResult).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordLookupButton(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    val loading by viewModel.cloudLoading.collectAsStateWithLifecycle()

    IconButton(onClick = { show = true }, modifier = modifier) {
        Icon(Icons.Default.EditLocationAlt, contentDescription = "Nhập toạ độ tra thửa")
    }

    if (show) {
        var isVn by remember { mutableStateOf(true) }   // true=VN-2000, false=WGS-84
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Tra thửa theo toạ độ") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = isVn, onClick = { isVn = true }, label = { Text("VN-2000") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = !isVn, onClick = { isVn = false }, label = { Text("WGS-84") })
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = a, onValueChange = { a = it },
                        label = { Text(if (isVn) "X (Easting)" else "Vĩ độ (lat)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = b, onValueChange = { b = it },
                        label = { Text(if (isVn) "Y (Northing)" else "Kinh độ (lon)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !loading && a.isNotBlank() && b.isNotBlank(),
                    onClick = {
                        val av = a.trim().replace(',', '.').toDoubleOrNull()
                        val bv = b.trim().replace(',', '.').toDoubleOrNull()
                        if (av != null && bv != null) {
                            if (isVn) viewModel.whereAmIVn2000(av, bv)
                            else viewModel.whereAmINow(av, bv)
                            show = false
                        }
                    }
                ) { Text("Tra") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Đóng") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadastralCloudDialog(
    loading: Boolean,
    offline: Boolean = false,
    hasOffline: Boolean = false,
    onOfflineChange: (Boolean) -> Unit = {},
    onLoad: (communeSlug: String, sheet: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // false = tìm theo xã MỚI (sau sáp nhập); true = tìm theo xã CŨ (trước sáp nhập)
    var oldMode by remember { mutableStateOf(false) }

    var communeExpanded by remember { mutableStateOf(false) }
    var commune by remember { mutableStateOf(CadastralCloudSource.COMMUNES.first()) }

    val oldCommunes = remember { CadastralConvert.oldCommunes(context) }
    var oldExpanded by remember { mutableStateOf(false) }
    var oldCommune by remember { mutableStateOf(oldCommunes.firstOrNull()) }

    var sheet by remember { mutableStateOf("") }

    // Xem trước ánh xạ tờ cũ -> tờ mới (chế độ xã cũ)
    val preview: String? = if (oldMode && oldCommune != null) {
        val sp = CadastralCloudSource.parse(sheet)
        if (sp == null) null
        else CadastralConvert.resolve(context, oldCommune!!.slug, oldCommune!!.newSlug, sp.to)?.let { r ->
            "→ ${r.newName}, Tờ ${r.newTo}" + (sp.thua?.let { " — Thửa $it" } ?: "")
        } ?: "→ Không có tờ ${sp.to} ở ${oldCommune!!.name}"
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tải bản đồ địa chính (Cloud)") },
        text = {
            Column {
                // Nguồn dữ liệu: Cloud (cần mạng) hoặc Offline (đọc từ bộ nhớ máy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !offline, onClick = { onOfflineChange(false) }, label = { Text("Cloud") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = offline, onClick = { onOfflineChange(true) }, label = { Text("Offline") })
                }
                if (offline && !hasOffline) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Chưa có dữ liệu offline trên máy — chép thư mục sheets/ vào bộ nhớ ứng dụng.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !oldMode, onClick = { oldMode = false }, label = { Text("Xã mới") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = oldMode, onClick = { oldMode = true }, label = { Text("Xã cũ") })
                }
                Spacer(Modifier.height(8.dp))

                if (!oldMode) {
                    ExposedDropdownMenuBox(
                        expanded = communeExpanded,
                        onExpandedChange = { communeExpanded = !communeExpanded }
                    ) {
                        OutlinedTextField(
                            value = commune.second,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Xã") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(communeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = communeExpanded,
                            onDismissRequest = { communeExpanded = false }
                        ) {
                            CadastralCloudSource.COMMUNES.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.second) },
                                    onClick = { commune = c; communeExpanded = false }
                                )
                            }
                        }
                    }
                } else {
                    ExposedDropdownMenuBox(
                        expanded = oldExpanded,
                        onExpandedChange = { oldExpanded = !oldExpanded }
                    ) {
                        OutlinedTextField(
                            value = oldCommune?.let { "${it.name}  (→ ${it.newName})" } ?: "—",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Xã cũ (trước sáp nhập)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(oldExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = oldExpanded,
                            onDismissRequest = { oldExpanded = false }
                        ) {
                            oldCommunes.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.name}  →  ${c.newName}") },
                                    onClick = { oldCommune = c; oldExpanded = false }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sheet,
                    onValueChange = { sheet = it },
                    label = { Text(if (oldMode) "Tờ cũ / Thửa (vd: 5/103)" else "Tờ / Thửa (vd: 122/90)") },
                    placeholder = { Text("122/90  •  122.90  •  122-90  •  122") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                preview?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && sheet.isNotBlank() && (!oldMode || oldCommune != null),
                onClick = {
                    if (!oldMode) {
                        onLoad(commune.first, sheet)
                    } else {
                        val sp = CadastralCloudSource.parse(sheet)
                        val oc = oldCommune
                        if (sp == null || oc == null) {
                            Toast.makeText(context, "Nhập chưa đúng — ví dụ 5/103", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val r = CadastralConvert.resolve(context, oc.slug, oc.newSlug, sp.to)
                        if (r == null) {
                            Toast.makeText(context, "Không có tờ ${sp.to} ở ${oc.name}", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val raw = if (sp.thua != null) "${r.newTo}/${sp.thua}" else r.newTo
                        onLoad(r.newSlug, raw)
                    }
                }
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Tải")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}
