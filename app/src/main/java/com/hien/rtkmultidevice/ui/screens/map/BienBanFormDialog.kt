package com.hien.rtkmultidevice.ui.screens.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hien.rtkmultidevice.report.BienBan
import com.hien.rtkmultidevice.report.BienBanSketch
import java.util.Calendar

/**
 * BienBanFormDialog — Nhập nội dung BIÊN BẢN BÀN GIAO MỐC GIỚI ngay trong app.
 *
 * Thay cho việc phải cài trình soạn thảo và sửa XML bằng tay:
 *   • Ô nào app biết thì điền sẵn (số thửa, diện tích, loại đất, chủ sử dụng, toạ độ).
 *   • Ô nào không có trong dữ liệu địa chính thì người dùng gõ (số GCN, ngày cấp, địa chỉ…).
 *   • Thông tin ĐƠN VỊ ĐO ĐẠC được LƯU LẠI, lần sau tự điền.
 *
 * Kèm KHUNG CHỤP sơ hoạ: khung xem trước đúng tỉ lệ với khung sơ hoạ trên báo cáo,
 * kéo để dời tâm, chụm hai ngón để phóng to/thu nhỏ — thấy sao in ra vậy.
 */
@Composable
fun BienBanFormDialog(
    feature      : VectorLayerImporter.VectorFeature,
    neighbours   : List<BienBanSketch.NeighbourParcel>,
    initial      : BienBan,
    /** Trạng thái đã lưu của tuỳ chọn ghi độ dài cạnh. */
    nhanCanhBanDau : Boolean = false,
    onDismiss    : () -> Unit,
    /** (nội dung, zoom, tâmN, tâmE, ghiNhãnCạnh, xuấtPdf) */
    onExport     : (BienBan, Float, Double, Double, Boolean, Boolean) -> Unit
) {
    // rawPoints VN-2000: (Easting, Northing) → (N, E)
    // Lọc đỉnh thừa NGAY TỪ ĐÂY: khung xem trước, bảng mốc trong form và bản
    // PDF xuất ra đều phải đánh số đỉnh giống nhau. Lọc ở ba nơi khác nhau là
    // sớm muộn cũng lệch.
    val vertsGoc = remember(feature.id) { feature.rawPoints.map { it.second to it.first } }
    val verts    = remember(vertsGoc) {
        com.hien.rtkmultidevice.report.LocDinh.loc(vertsGoc)
    }
    val soDinhBo = remember(verts) {
        com.hien.rtkmultidevice.report.LocDinh.soDinhDaBo(vertsGoc, verts)
    }
    val baseN = remember(verts) { (verts.minOf { it.first } + verts.maxOf { it.first }) / 2.0 }
    val baseE = remember(verts) { (verts.minOf { it.second } + verts.maxOf { it.second }) / 2.0 }
    val spanN = remember(verts) { (verts.maxOf { it.first } - verts.minOf { it.first }).coerceAtLeast(1.0) }

    // ── Trạng thái các ô nhập ──
    var ngay   by remember { mutableStateOf(initial.ngay.toString()) }
    var thang  by remember { mutableStateOf(initial.thang.toString()) }
    var nam    by remember { mutableStateOf(initial.nam.toString()) }
    var soThua by remember { mutableStateOf(initial.soThua) }
    var soTo   by remember { mutableStateOf(initial.soTo) }
    var xa     by remember { mutableStateOf(initial.xa) }
    var huyen  by remember { mutableStateOf(initial.huyen) }
    var dt     by remember { mutableStateOf(initial.dienTich) }
    var loai   by remember { mutableStateOf(initial.loaiDat) }
    var chu    by remember { mutableStateOf(initial.chuSuDung) }
    var xung   by remember { mutableStateOf(initial.xungHo) }
    var dcChu  by remember { mutableStateOf(initial.diaChiChu) }
    var gcnSo  by remember { mutableStateOf(initial.soPhatHanhGcn) }
    var gcnNgay by remember { mutableStateOf(initial.ngayCapGcn) }
    var gcnNoi by remember { mutableStateOf(initial.noiCapGcn) }
    var dvTen  by remember { mutableStateOf(initial.donViTen) }
    var dvDd   by remember { mutableStateOf(initial.donViDaiDien) }
    var dvCv   by remember { mutableStateOf(initial.donViChucVu) }
    var dvDc   by remember { mutableStateOf(initial.donViDiaChi) }
    var dvVp   by remember { mutableStateOf(initial.donViVpdd) }

    // ── Khung chụp sơ hoạ ──
    var zoom   by remember { mutableFloatStateOf(0.72f) }
    var offN   by remember { mutableDoubleStateOf(0.0) }   // dời tâm theo Bắc (m)
    var offE   by remember { mutableDoubleStateOf(0.0) }   // dời tâm theo Đông (m)
    var nhanCanh by remember { mutableStateOf(nhanCanhBanDau) }

    fun build() = BienBan(
        ngay = ngay.toIntOrNull() ?: 1,
        thang = thang.toIntOrNull() ?: 1,
        nam = nam.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR),
        soThua = soThua, soTo = soTo, xa = xa, huyen = huyen,
        dienTich = dt, loaiDat = loai,
        chuSuDung = chu, xungHo = xung, diaChiChu = dcChu,
        soPhatHanhGcn = gcnSo, ngayCapGcn = gcnNgay, noiCapGcn = gcnNoi,
        donViTen = dvTen, donViDaiDien = dvDd, donViChucVu = dvCv,
        donViDiaChi = dvDc, donViVpdd = dvVp,
        moc = BienBan.buildMoc(verts)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Biên bản bàn giao mốc giới", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            ) {
                // ── Đơn vị đo đạc: CHỈ ĐỌC, sửa ở Cài đặt ──
                // Một thông tin chỉ nên sửa ở MỘT nơi; ở đây chỉ hiển thị để
                // người dùng biết ngay đã khai báo đủ chưa trước khi xuất.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (dvTen.isBlank()) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Đơn vị đo đạc", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (dvTen.isBlank())
                                "CHƯA KHAI BÁO — vào Cài đặt toạ độ VN-2000 → mục \"Đơn vị đo đạc\""
                            else "$dvTen • $dvDd${if (dvCv.isNotBlank()) " ($dvCv)" else ""}",
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                // ── KHUNG CHỤP SƠ HOẠ ──
                Text("Khung sơ hoạ — kéo để dời, chụm 2 ngón để phóng",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Surface(
                    // Đúng tỉ lệ khung sơ hoạ trên báo cáo (235 × 300 pt)
                    modifier = Modifier.fillMaxWidth().aspectRatio(235f / 300f),
                    shape = RectangleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(feature.id) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(0.15f, 6f)
                                // Đổi dịch chuyển pixel → mét theo tỉ lệ đang hiển thị
                                val mPerPx = spanN / (size.height * zoom).coerceAtLeast(1f)
                                offE -= pan.x * mPerPx
                                offN += pan.y * mPerPx
                            }
                        }
                    ) {
                        drawIntoCanvas { cv ->
                            BienBanSketch.draw(
                                canvas = cv.nativeCanvas,
                                frame = android.graphics.RectF(0f, 0f, size.width, size.height),
                                mainVertices = verts,
                                mainLabel = BienBanSketch.ParcelLabel(soThua, dt, loai),
                                neighbours = neighbours,
                                zoom = zoom,
                                centerN = baseN + offN,
                                centerE = baseE + offE,
                                nhanCanh = nhanCanh
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { zoom = 0.72f; offN = 0.0; offE = 0.0 }) {
                        Text("Đặt lại khung", fontSize = 12.sp)
                    }
                    Text("Phóng ${"%.1f".format(zoom)}×", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (soDinhBo > 0) {
                    Text(
                        "Đã gộp $soDinhBo đỉnh thừa (gần nhau < 0,20 m hoặc lệch " +
                        "đường thẳng < 0,02 m) — sơ hoạ và bảng toạ độ còn ${verts.size} đỉnh.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { nhanCanh = !nhanCanh }) {
                    Checkbox(checked = nhanCanh, onCheckedChange = { nhanCanh = it })
                    Text("Ghi độ dài cạnh (m) lên sơ hoạ", fontSize = 12.sp)
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ── Thửa đất ──
                Sect("Thửa đất")
                Row {
                    F(soThua, { soThua = it }, "Số thửa", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    F(soTo, { soTo = it }, "Số tờ", Modifier.weight(1f))
                }
                Row {
                    F(dt, { dt = it }, "Diện tích (m²)", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    F(loai, { loai = it }, "Loại đất", Modifier.weight(1f))
                }
                F(xa, { xa = it }, "Xã/Phường")
                F(huyen, { huyen = it }, "Huyện/Thành phố")

                // ── Chủ sử dụng ──
                Sect("Chủ sử dụng đất")
                F(chu, { chu = it }, "Họ tên")
                Row {
                    F(xung, { xung = it }, "Xưng hô (ông/bà)", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Spacer(Modifier.weight(1f))
                }
                F(dcChu, { dcChu = it }, "Địa chỉ")

                // ── Giấy chứng nhận ──
                Sect("Giấy chứng nhận")
                Row {
                    F(gcnSo, { gcnSo = it }, "Số phát hành", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    F(gcnNgay, { gcnNgay = it }, "Ngày cấp", Modifier.weight(1f))
                }
                F(gcnNoi, { gcnNoi = it }, "Nơi cấp")

                // ── Ngày lập ──
                Sect("Ngày lập biên bản")
                Row {
                    F(ngay, { ngay = it }, "Ngày", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    F(thang, { thang = it }, "Tháng", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    F(nam, { nam = it }, "Năm", Modifier.weight(1f))
                }

                Spacer(Modifier.height(10.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(build(), zoom, baseN + offN, baseE + offE, nhanCanh, true) }) {
                Text("Xuất PDF", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onExport(build(), zoom, baseN + offN, baseE + offE, nhanCanh, false) }) {
                    Text("Lưu XML")
                }
                TextButton(onClick = onDismiss) { Text("Đóng") }
            }
        }
    )
}

@Composable
private fun Sect(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun F(
    value: String, onChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
        modifier = modifier.padding(vertical = 2.dp)
    )
}
