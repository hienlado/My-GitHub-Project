package com.hien.rtkmultidevice.ui.screens.connection

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hien.rtkmultidevice.core.network.DeviceProbe
import com.hien.rtkmultidevice.core.network.DeviceProfileStore
import com.hien.rtkmultidevice.core.network.WifiInfoHelper
import kotlinx.coroutines.launch

/**
 * DoMayMoiDialog — DÒ máy thu chưa có trong danh sách, NGHE thử, rồi mới kết nối.
 *
 * Bài toán: mỗi hãng một địa chỉ, một cổng, một kiểu dữ liệu. Trước đây gặp máy
 * lạ là phải mở tài liệu hãng rồi sửa mã nguồn mới nối được. Màn này làm ngược
 * lại — **nghe máy nói gì rồi mới kết luận**, và lưu lại thành hồ sơ dùng lần sau.
 *
 * Ba bước, đúng thứ tự người đo hay làm ngoài thực địa:
 *   1. DÒ    — quét gateway + các IP hay gặp + quét LAN, liệt kê mọi ứng viên.
 *   2. NGHE  — mở cổng, hứng vài giây dữ liệu thô, phân loại NMEA / RTCM3.
 *              Đây là bước quyết định: cổng mở chưa chắc là cổng dữ liệu.
 *   3. NỐI   — chỉ khi đã nghe thấy NMEA mới nên bấm kết nối.
 *
 * ⚠ CHỈ ĐỌC. Không gửi lệnh, không đổi cấu hình máy. Dò nhầm một máy đang đo
 *   ngoài thực địa thì đắt hơn nhiều so với dò chậm một chút.
 */
@Composable
fun DoMayMoiDialog(
    viewModel : ConnectionViewModel,
    onDong    : () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var dangDo   by remember { mutableStateOf(false) }
    var buoc     by remember { mutableStateOf("") }
    var ungVien  by remember { mutableStateOf<List<DeviceProbe.UngVien>>(emptyList()) }
    var daDo     by remember { mutableStateOf(false) }
    // Kết quả nghe lại theo từng host:cổng, đè lên kết quả nghe lúc quét
    var ngheThem by remember { mutableStateOf<Map<String, DeviceProbe.KetQua>>(emptyMap()) }
    var luuXong  by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!dangDo) onDong() },
        title = { Text("Dò máy thu mới") },
        confirmButton = {
            TextButton(onClick = onDong, enabled = !dangDo) { Text("Đóng") }
        },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Nối điện thoại vào WiFi của máy thu trước, rồi bấm Dò. " +
                    "App chỉ ĐỌC — không gửi lệnh nào tới máy.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        scope.launch {
                            dangDo = true; daDo = false; ungVien = emptyList()
                            ngheThem = emptyMap(); luuXong = null
                            buoc = "Đang quét gateway, IP cố định và LAN…"
                            val gw = WifiInfoHelper.gatewayIp(ctx)
                            ungVien = runCatching { DeviceProbe.quet(ctx, gw) }
                                .getOrDefault(emptyList())
                            buoc = ""; dangDo = false; daDo = true
                        }
                    },
                    enabled = !dangDo,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (dangDo) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (dangDo) "Đang dò…" else "Bắt đầu dò")
                }
                if (buoc.isNotBlank()) {
                    Text(buoc, fontSize = 11.sp,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (daDo && ungVien.isEmpty()) {
                    Text(
                        "Không thấy máy nào. Kiểm tra: điện thoại đã nối đúng WiFi của " +
                        "máy thu chưa, và máy thu có bật phát dữ liệu qua WiFi không.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                ungVien.forEach { uv ->
                    TheUngVien(
                        uv = uv,
                        ngheThem = ngheThem,
                        onNghe = { port ->
                            scope.launch {
                                dangDo = true
                                buoc = "Đang nghe ${uv.host}:$port trong 5 giây…"
                                val k = DeviceProbe.nghe(ctx, uv.host, port, 5)
                                ngheThem = ngheThem + ("${uv.host}:$port" to k)
                                buoc = ""; dangDo = false
                            }
                        },
                        onNoi = { port ->
                            viewModel.onTcpHostChanged(uv.host)
                            viewModel.onTcpPortChanged(port.toString())
                            viewModel.connectTcp()
                            onDong()
                        },
                        onLuu = { ten, port ->
                            val hs = DeviceProfileStore.tuKetQua(
                                ten, uv, WifiInfoHelper.deviceNameFromWifi(ctx)
                            ).copy(ports = listOf(port))
                            DeviceProfileStore.luu(ctx, hs)
                            luuXong = "Đã lưu hồ sơ \"$ten\" — lần sau nối thẳng."
                        }
                    )
                }

                luuXong?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

@Composable
private fun TheUngVien(
    uv       : DeviceProbe.UngVien,
    ngheThem : Map<String, DeviceProbe.KetQua>,
    onNghe   : (Int) -> Unit,
    onNoi    : (Int) -> Unit,
    onLuu    : (String, Int) -> Unit
) {
    var tenMay by remember(uv.host) { mutableStateOf(uv.web?.tieuDe?.take(24).orEmpty()) }
    // Cổng đang xét: mặc định cổng dữ liệu đầu tiên mở được.
    var cong by remember(uv.host) { mutableStateOf(uv.cong.firstOrNull() ?: 0) }
    val k = ngheThem["${uv.host}:$cong"] ?: uv.nghe?.takeIf { it.port == cong }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uv.host, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text(uv.nguon, fontSize = 10.sp) })
            }

            uv.web?.let { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Web ${w.ma}" + (if (w.tieuDe.isNotBlank()) " · ${w.tieuDe}" else "") +
                        (if (w.server.isNotBlank()) " · ${w.server}" else ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uv.cong.isEmpty()) {
                Text("Không cổng dữ liệu nào mở — chỉ thấy trang web.",
                     fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            } else {
                Text("Cổng mở: chạm để chọn", fontSize = 11.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    uv.cong.take(6).forEach { p ->
                        FilterChip(
                            selected = p == cong,
                            onClick = { cong = p },
                            label = { Text("$p", fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Kết quả NGHE — phần quyết định
            k?.let {
                Surface(
                    color = when (it.loai) {
                        DeviceProbe.Loai.NMEA, DeviceProbe.Loai.HON_HOP ->
                            MaterialTheme.colorScheme.primaryContainer
                        DeviceProbe.Loai.TRONG ->
                            MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(it.moTa, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("${it.soByte} byte trong ${"%.1f".format(it.giay)} s",
                             fontSize = 10.sp)
                        if (it.mauChu.isNotBlank()) {
                            Text(it.mauChu.take(120), fontSize = 9.sp,
                                 fontFamily = FontFamily.Monospace)
                        } else if (it.mauHex.isNotBlank()) {
                            Text(it.mauHex, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            if (cong > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onNghe(cong) }) {
                        Icon(Icons.Default.Hearing, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Nghe 5s", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onNoi(cong) },
                        // Chỉ mở nút Kết nối khi ĐÃ NGHE THẤY dữ liệu định vị.
                        // Cổng mở không có nghĩa là cổng dữ liệu.
                        enabled = k != null && k.loai != DeviceProbe.Loai.TRONG
                    ) { Text("Kết nối", fontSize = 12.sp) }
                }

                OutlinedTextField(
                    value = tenMay,
                    onValueChange = { tenMay = it },
                    label = { Text("Đặt tên máy để lưu hồ sơ", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { onLuu(tenMay.trim(), cong) },
                    enabled = tenMay.isNotBlank()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Lưu hồ sơ máy này", fontSize = 12.sp)
                }
            }
        }
    }
}
