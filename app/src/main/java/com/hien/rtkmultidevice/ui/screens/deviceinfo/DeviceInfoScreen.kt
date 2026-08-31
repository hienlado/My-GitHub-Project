package com.hien.rtkmultidevice.ui.screens.deviceinfo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Warning
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
    val canhBao by viewModel.canhBao.collectAsStateWithLifecycle()

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
            // Cảnh báo đang treo — để TRÊN CÙNG, không nhét xuống cuối trang.
            canhBao?.let { c ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor   = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(c.tieuDe, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(c.chiTiet, fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("→ " + c.phaiLam, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }

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

            item {
                Spacer(Modifier.height(10.dp))
                TieuDe("Ghi nhớ — máy đứng ở SINGLE, không lên FIXED")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Máy thu bắt đủ vệ tinh mà vẫn SINGLE thì gần như luôn là " +
                            "KHÔNG NHẬN ĐƯỢC CẢI CHÍNH, không phải lỗi máy thu. " +
                            "Kiểm tra theo đúng thứ tự này:",
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Buoc("1", "Mật khẩu NTRIP",
                             "Nguyên nhân số một, đã gặp 31/08/2026: nhà cung cấp đổi " +
                             "mật khẩu, caster trả 401, máy thu thử lại 5 giây một lần " +
                             "vô tận. Vào Thiết bị ▸ NTRIP nhập lại mật khẩu.")
                        Buoc("2", "IP điện thoại đổi",
                             "Máy thu lấy cải chính từ điện thoại nên phải trỏ đúng IP. " +
                             "Xem IP ở Thiết bị ▸ Kết nối, rồi sửa mục RTK Client trên " +
                             "trang web máy thu cho khớp.")
                        Buoc("3", "Mountpoint",
                             "Sai tên mountpoint thì caster trả bảng sourcetable thay vì " +
                             "dòng cải chính. Đối chiếu lại tên trong Thiết bị ▸ NTRIP.")
                        Buoc("4", "Dữ liệu di động",
                             "WiFi của máy thu không có Internet. App tự chuyển sang 4G, " +
                             "nhưng SIM hết dung lượng hoặc vùng không sóng thì cải chính " +
                             "không về được.")
                    }
                }
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

@Composable
private fun Buoc(so: String, ten: String, moTa: String) {
    Row(Modifier.padding(bottom = 10.dp)) {
        Text(
            so,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp)
        )
        Column {
            Text(ten, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(
                moTa,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
