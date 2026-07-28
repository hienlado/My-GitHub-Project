package com.hien.rtkmultidevice.ui.screens.baseconfig

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hien.rtkmultidevice.core.network.WifiInfoHelper
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: BaseConfigViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val gnss by viewModel.gnss.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(feedback) {
        feedback?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.clearFeedback() }
    }

    fun pd(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cấu hình Máy trạm (Base)") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ── Thiết bị làm Base (cấu hình đi theo thiết bị) ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Thiết bị làm Base", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        BaseDevice.entries.forEach { dev ->
                            val lbl = when (dev) {
                                BaseDevice.COMNAV_T30 -> "ComNav T30"
                                BaseDevice.STEC -> "STEC"
                                BaseDevice.GENERIC -> "Khác"
                            }
                            FilterChip(
                                selected = config.deviceType == dev.key,
                                onClick = { viewModel.update(config.copy(deviceType = dev.key)) },
                                label = { Text(lbl, fontSize = 12.sp) }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }

            // ── Tên trạm ──
            OutlinedTextField(
                value = config.name, onValueChange = { viewModel.update(config.copy(name = it)) },
                label = { Text("Tên trạm base") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            // ── Chế độ vị trí ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Chế độ vị trí base", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        listOf("Điểm đã biết", "Vị trí hiện tại", "Bình sai TB").forEachIndexed { i, name ->
                            FilterChip(selected = config.mode == i, onClick = { viewModel.update(config.copy(mode = i)) },
                                label = { Text(name, fontSize = 12.sp) })
                            if (i < 2) Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }

            // ── Nhập toạ độ theo chế độ ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    when (config.mode) {
                        0 -> {   // Điểm đã biết — nhập VN-2000, chuyển sang WGS-84
                            Text("Nhập toạ độ VN-2000 của mốc base", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            var nTxt by remember { mutableStateOf("") }
                            var eTxt by remember { mutableStateOf("") }
                            var hTxt by remember { mutableStateOf(if (config.ellHeight != 0.0) "%.3f".format(config.ellHeight) else "") }
                            Row {
                                OutlinedTextField(nTxt, { nTxt = it }, label = { Text("N (X)") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(eTxt, { eTxt = it }, label = { Text("E (Y)") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(hTxt, { hTxt = it }, label = { Text("Độ cao ellipsoid H (m)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                val n = pd(nTxt); val e = pd(eTxt); val h = pd(hTxt) ?: 0.0
                                if (n != null && e != null) viewModel.setFromVn2000(n, e, h)
                                else Toast.makeText(context, "Nhập N và E", Toast.LENGTH_SHORT).show()
                            }) { Text("Đặt từ VN-2000") }
                        }
                        1 -> {   // Vị trí hiện tại
                            Text("Dùng vị trí RTK hiện tại làm base (nên đo tại mốc, có fix).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("Hiện tại: ${gnss.fixLabel} • ${gnss.satelliteCount} vệ tinh", fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { viewModel.captureCurrent() }) { Text("Lấy vị trí hiện tại") }
                        }
                        else -> {   // Bình sai TB — máy tự bình sai
                            Text("Máy thu tự bình sai vị trí base trong khoảng thời gian đặt trước " +
                                "(cấu hình trên web máy). Toạ độ tuyệt đối kém chính xác hơn — dùng cho đo TƯƠNG ĐỐI.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            var avg by remember { mutableStateOf(config.avgSeconds.toString()) }
                            OutlinedTextField(avg, { avg = it; it.toIntOrNull()?.let { s -> viewModel.update(config.copy(avgSeconds = s)) } },
                                label = { Text("Thời gian bình sai (giây)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = if (config.antennaHeight != 0.0) "%.3f".format(config.antennaHeight) else "",
                        onValueChange = { viewModel.update(config.copy(antennaHeight = pd(it) ?: 0.0)) },
                        label = { Text("Chiều cao anten base (m)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Toạ độ base hiện lưu (để nhập vào web máy) ──
            if (config.lat != 0.0 || config.lon != 0.0) {
                val vn = viewModel.toVn2000()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Toạ độ base đang lưu", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text("WGS-84: %.9f°, %.9f°\nH ellipsoid: %.3f m".format(config.lat, config.lon, config.ellHeight),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (vn != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("VN-2000: N=%.3f  E=%.3f".format(vn.first, vn.second),
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ── Datalink: cách base phát cải chính ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Kênh phát cải chính (Datalink)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        listOf("NTRIP Server", "Radio UHF", "Ngoài").forEachIndexed { i, name ->
                            FilterChip(selected = config.datalinkType == i, onClick = { viewModel.update(config.copy(datalinkType = i)) },
                                label = { Text(name, fontSize = 12.sp) })
                            if (i < 2) Spacer(Modifier.width(6.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    when (config.datalinkType) {
                        0 -> {   // NTRIP Server — base đẩy RTCM lên caster
                            Text("Base đẩy RTCM lên caster; rover lấy CÙNG host/port/mountpoint.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Row {
                                OutlinedTextField(config.ntripHost, { viewModel.update(config.copy(ntripHost = it)) },
                                    label = { Text("Caster host") }, singleLine = true, modifier = Modifier.weight(2f))
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(config.ntripPort.toString(),
                                    { viewModel.update(config.copy(ntripPort = it.toIntOrNull() ?: config.ntripPort)) },
                                    label = { Text("Port") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(config.ntripMount, { viewModel.update(config.copy(ntripMount = it)) },
                                label = { Text("Mountpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(config.ntripPassword, { viewModel.update(config.copy(ntripPassword = it)) },
                                label = { Text("Password (đẩy lên caster)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        1 -> {   // Radio UHF — base & rover phải trùng
                            Text("Base & rover PHẢI trùng: tần số + protocol + air baud.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Row {
                                OutlinedTextField(config.radioFreq, { viewModel.update(config.copy(radioFreq = it)) },
                                    label = { Text("Tần số/kênh (MHz)") }, singleLine = true, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(config.radioBaud.toString(),
                                    { viewModel.update(config.copy(radioBaud = it.toIntOrNull() ?: config.radioBaud)) },
                                    label = { Text("Air baud") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(config.radioProtocol, { viewModel.update(config.copy(radioProtocol = it)) },
                                label = { Text("Protocol (TransparentEOT/TrimTalk450S/SATEL...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        else -> {
                            Text("Cấu hình datalink trực tiếp trên máy/điện đài.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Cổng phát RTCM (dùng trong lệnh LOG của ComNav T30)
                    if (config.deviceType == BaseDevice.COMNAV_T30.key && config.datalinkType != 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(config.outPort, { viewModel.update(config.copy(outPort = it)) },
                            label = { Text("Cổng phát RTCM (vd COM2)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // ── Hướng dẫn đặt Base THEO THIẾT BỊ ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Hướng dẫn đặt Base — ${BaseDevice.from(config.deviceType).displayName}",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        BaseDevice.from(config.deviceType).guidance(config),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                    )
                }
            }

            Button(onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth()) {
                Text("Lưu cấu hình Base", fontWeight = FontWeight.SemiBold)
            }

            // ── Điều khiển máy thu qua WiFi (dùng chính lệnh của trang web máy) ──
            val gw = remember { WifiInfoHelper.gatewayIp(context) }
            if (gw != null) {
                var showWebPowerOff by remember { mutableStateOf(false) }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Điều khiển máy thu (qua WiFi)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { viewModel.rebootViaWeb() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Khởi động lại máy thu") }

                        // T30 không có lệnh tắt nguồn (web chỉ có Reboot/Factory)
                        val isT30 = BaseDevice.from(config.deviceType) == BaseDevice.COMNAV_T30
                        if (!isT30) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { showWebPowerOff = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Tắt nguồn máy thu") }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "ComNav T30 chưa hỗ trợ tắt nguồn từ xa (web máy chỉ có Reboot). " +
                                "Dùng nút nguồn trên máy để tắt.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://$gw")
                                    )
                                )
                            }.onFailure {
                                Toast.makeText(context, "Không mở được trang $gw", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Mở trang cấu hình đầy đủ (I/O Settings...)") }
                    }
                }

                if (showWebPowerOff) {
                    AlertDialog(
                        onDismissRequest = { showWebPowerOff = false },
                        title = { Text("Tắt nguồn máy thu?") },
                        text = {
                            Text("Máy sẽ TẮT HẲN. Phải bấm nút nguồn trên máy để bật lại. " +
                                "Hãy chắc đã đo xong và lưu đủ dữ liệu.")
                        },
                        confirmButton = {
                            TextButton(onClick = { showWebPowerOff = false; viewModel.powerOffViaWeb() }) {
                                Text("Tắt nguồn", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { showWebPowerOff = false }) { Text("Huỷ") } }
                    )
                }
            }

            // ── Gửi lệnh xuống máy (chỉ máy dùng lệnh, vd ComNav T30) ──
            if (BaseDevice.from(config.deviceType).commandBased) {
                var showSend by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showSend = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Gửi lệnh cấu hình xuống máy (Bluetooth/TCP)")
                }
                if (showSend) {
                    val cmds = viewModel.previewCommands()
                    AlertDialog(
                        onDismissRequest = { showSend = false },
                        title = { Text("Gửi lệnh xuống ${BaseDevice.from(config.deviceType).displayName}?") },
                        text = {
                            Column {
                                Text("Các lệnh sẽ gửi qua kết nối đang mở (mỗi dòng CR/LF). " +
                                    "Lệnh SAVECONFIG sẽ GHI vào máy. Hãy chắc đã kết nối đúng máy Base.",
                                    style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                cmds.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSend = false; viewModel.sendCommandsToDevice() }) { Text("Gửi") }
                        },
                        dismissButton = { TextButton(onClick = { showSend = false }) { Text("Huỷ") } }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Lưu ý: kiểm chứng cú pháp lệnh với tài liệu máy trước khi gửi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Điều khiển máy thu ──
            if (BaseDevice.from(config.deviceType).canRestart) {
                var showRestart by remember { mutableStateOf(false) }
                Spacer(Modifier.height(4.dp))

                // Đặt lại tính toán RTK — nhẹ, dùng khi kẹt FLOAT
                OutlinedButton(
                    onClick = { viewModel.resetRtkFilter() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Đặt lại tính toán RTK (khi kẹt FLOAT)") }

                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { showRestart = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Khởi động lại máy thu") }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Bộ lệnh OEM không có lệnh TẮT NGUỒN. Muốn tắt máy: mở trang cấu hình " +
                    "→ Receiver Configuration → Receiver Reset → Turn Off Receiver.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (showRestart) {
                    AlertDialog(
                        onDismissRequest = { showRestart = false },
                        title = { Text("Khởi động lại máy thu?") },
                        text = {
                            Text("Máy sẽ boot lại từ đầu và MẤT KẾT NỐI khoảng 30 giây, " +
                                "sau đó tự hoạt động lại (phải kết nối lại trong app). " +
                                "Dùng khi máy bị kẹt. Hãy lưu dữ liệu trước.")
                        },
                        confirmButton = {
                            TextButton(onClick = { showRestart = false; viewModel.restartDevice() }) {
                                Text("Khởi động lại", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { showRestart = false }) { Text("Huỷ") } }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
