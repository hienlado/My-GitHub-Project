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
    onLoad: (communeSlug: String, sheet: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var communeExpanded by remember { mutableStateOf(false) }
    var commune by remember { mutableStateOf(CadastralCloudSource.COMMUNES.first()) }
    var sheet by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tải bản đồ địa chính (Cloud)") },
        text = {
            Column {
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
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sheet,
                    onValueChange = { sheet = it },
                    label = { Text("Tờ / Thửa (vd: 122/90)") },
                    placeholder = { Text("122/90  •  122.90  •  122-90  •  122") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && sheet.isNotBlank(),
                onClick = { onLoad(commune.first, sheet) }
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Tải")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}
