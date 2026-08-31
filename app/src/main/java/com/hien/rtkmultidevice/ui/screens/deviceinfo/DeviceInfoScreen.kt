package com.hien.rtkmultidevice.ui.screens.deviceinfo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.core.network.WifiInfoHelper
import com.hien.rtkmultidevice.domain.model.DeviceInfo

/**
 * DeviceInfoScreen — Thiết bị ▸ Thông tin.
 *
 * Trả lời đúng một câu hỏi ngoài thực địa: **máy này lần trước nối bằng gì?**
 * Gom ba nguồn đã có sẵn trong app, không bịa thêm dữ liệu:
 *
 *   1. `IDeviceRepository` (Room, bảng `devices`) — mọi máy đã kết nối thành
 *      công: tên, địa chỉ (MAC hoặc IP:cổng), loại kết nối, lần dùng gần nhất.
 *   2. `WifiInfoHelper.PROFILES` — hồ sơ hãng máy đã kiểm chứng: mẫu tên WiFi,
 *      địa chỉ IP và dải cổng dữ liệu.
 *   3. `AppSettings.ntripConfigFlow` — tài khoản NTRIP đang lưu.
 *
 * ⚠ KHÔNG hiển thị mật khẩu NTRIP. Màn này là chỗ người ta hay chụp màn hình
 *   gửi nhau khi nhờ chỉnh máy.
 *
 * ⚠ Dùng `DeviceInfoViewModel` — ViewModel CHỈ ĐỌC của riêng màn này. Bản đầu
 * mượn `ConnectionViewModel` cho gọn và đã gây lỗi máy thu đứng ở SINGLE; lý do
 * đầy đủ ghi trong DeviceInfoViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoScreen(
    onNavigateBack : () -> Unit,
    // ⚠ KHÔNG dùng ConnectionViewModel ở đây — xem ghi chú đỏ trong
    //   DeviceInfoViewModel: nó có init tác dụng phụ, ghé màn này là hỏng
    //   cảnh báo đổi IP điện thoại và máy thu đứng ở SINGLE.
    viewModel      : DeviceInfoViewModel = hiltViewModel()
) {
    val recent by viewModel.recentDevices.collectAsStateWithLifecycle()
    val ntrip  by viewModel.ntripTomTat.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thông tin thiết bị") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { TieuDe("Máy đã từng kết nối (${recent.size})") }
            if (recent.isEmpty()) {
                item {
                    Text(
                        "Chưa có máy nào. Kết nối thành công một lần là máy được ghi vào đây.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(recent) { d -> TheMayThu(d) }

            item { Spacer(Modifier.height(6.dp)); TieuDe("Hồ sơ hãng máy đã kiểm chứng") }
            items(WifiInfoHelper.PROFILES) { pr -> TheHoSo(pr) }

            item {
                Spacer(Modifier.height(6.dp))
                TieuDe("NTRIP đang lưu")
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Router, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (ntrip.isBlank()) "Chưa cấu hình" else ntrip,
                            fontSize = 13.sp
                        )
                    }
                }
                Text(
                    "Mật khẩu NTRIP không hiển thị ở đây — sửa trong Thiết bị ▸ NTRIP.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun TieuDe(s: String) {
    Text(s, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun TheMayThu(d: DeviceInfo) {
    val laBt = d.type == DeviceInfo.ConnectionType.BLUETOOTH
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (laBt) Icons.Default.Bluetooth else Icons.Default.Wifi,
                null, Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name.ifBlank { "(không tên)" }, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "${d.typeLabel}  ·  ${d.address}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Đoán hãng từ tên máy: hồ sơ khớp theo mẫu tên WiFi, mà tên
                // máy Bluetooth thường trùng mẫu ấy (T30-…, GNSS-…).
                WifiInfoHelper.profileFor(d.name)?.let {
                    Text(
                        "Nhận dạng: ${it.label}  ·  cổng ${it.ports.joinToString(", ")}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun TheHoSo(pr: WifiInfoHelper.Profile) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(pr.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                "Tên WiFi khớp: ${pr.ssidRegex.pattern}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                // hosts rỗng "" nghĩa là dùng gateway của mạng WiFi hiện tại
                "IP: " + pr.hosts.joinToString(", ") { if (it.isBlank()) "gateway WiFi" else it } +
                        "   ·   Cổng: " + pr.ports.joinToString(", "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
