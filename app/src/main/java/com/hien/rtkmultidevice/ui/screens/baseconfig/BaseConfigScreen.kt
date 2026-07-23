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

            // ── Hướng dẫn cấu hình máy STEC ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Đặt máy thu sang chế độ Base", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "1. Nối WiFi vào hotspot của máy (tên = số series) → mở trình duyệt vào http://192.168.10.1 (admin/password).\n" +
                        "2. Working Mode = Base.\n" +
                        "3. Nhập toạ độ base = giá trị WGS-84 ở trên (hoặc chọn Average để máy tự bình sai).\n" +
                        "4. Datalink (đầu ra cải chính): chọn Radio / NTRIP Server / TCP tuỳ cách phát cho rover; định dạng RTCM3.\n" +
                        "5. Chiều cao anten = giá trị đã nhập ở đây.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth()) {
                Text("Lưu cấu hình Base", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
