package com.hien.rtkmultidevice.ui.screens.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hien.rtkmultidevice.core.cogo.Cogo
import java.util.Locale

/** Điểm chọn được cho COGO (từ điểm khảo sát / điểm import). */
data class CogoPoint(val name: String, val n: Double, val e: Double)

/**
 * Nút + hộp thoại COGO (4 công cụ). Toạ độ VN-2000 (N=Northing, E=Easting, mét).
 * @param points   Nguồn điểm để CHỌN TỪ DỮ LIỆU (khảo sát + import).
 * @param onStakeout != null: cho phép gửi điểm kết quả sang Cắm mốc.
 */
@Composable
fun CogoButton(
    modifier: Modifier = Modifier,
    points: List<CogoPoint> = emptyList(),
    onStakeout: ((name: String, n: Double, e: Double) -> Unit)? = null,
) {
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }, modifier = modifier) {
        Icon(Icons.Default.Calculate, contentDescription = "Công cụ COGO")
    }
    if (show) CogoDialog(points = points, onDismiss = { show = false }, onStakeout = onStakeout)
}

/** Thứ tự thẻ trong CogoDialog — dùng cho tham số `congCuBanDau`. */
object CogoTool {
    const val NGHICH_DAO = 0
    const val DIEM_PHUONG_VI = 1
    const val DIEN_TICH = 2
    const val GIAO_HOI = 3
}

private fun pd(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
private fun fmt(v: Double) = "%.3f".format(Locale.US, v)

@Composable
private fun numField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/** Một điểm nhập: nhãn + N + E + nút CHỌN từ danh sách điểm. */
@Composable
private fun PointInput(
    label: String,
    n: String, e: String,
    onN: (String) -> Unit, onE: (String) -> Unit,
    points: List<CogoPoint>,
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(30.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        numField(n, onN, "N", Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        numField(e, onE, "E", Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }, enabled = points.isNotEmpty()) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = "Chọn điểm từ dữ liệu")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (points.isEmpty())
                    DropdownMenuItem(text = { Text("Không có điểm") }, onClick = { menu = false })
                points.take(300).forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.name}  (${"%.2f".format(p.n)}, ${"%.2f".format(p.e)})", fontSize = 12.sp) },
                        onClick = { onN(fmt(p.n)); onE(fmt(p.e)); menu = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun resultText(s: String) {
    if (s.isNotEmpty())
        Text(s, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary)
}

@Composable
fun CogoDialog(
    onDismiss: () -> Unit,
    points: List<CogoPoint> = emptyList(),
    onStakeout: ((name: String, n: Double, e: Double) -> Unit)? = null,
    congCuBanDau: Int = CogoTool.NGHICH_DAO,
) {
    var tool by remember { mutableStateOf(congCuBanDau) }
    val tabs = listOf("Nghịch đảo", "Điểm P.vị", "Diện tích", "Giao hội")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Công cụ COGO") },
        text = {
            Column {
                // Hàng thẻ ĐỨNG YÊN, không cuộn theo nội dung — luôn đổi được
                // công cụ dù đang ở giữa một danh sách đỉnh dài.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    tabs.forEachIndexed { i, name ->
                        FilterChip(selected = tool == i, onClick = { tool = i }, label = { Text(name) })
                        if (i < tabs.lastIndex) Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 🔴 CUỘN Ở ĐÂY, VÀ CHỈ Ở ĐÂY. Trả giá 03/09/2026: AreaTool có
                // khung xem trước 150 dp + danh sách đỉnh 150 dp, đủ 4 đỉnh là
                // tổng chiều cao vượt quá khung AlertDialog, nút "Tính" bị đẩy
                // xuống dưới mép và BIẾN MẤT — không báo lỗi gì.
                // Compose CẤM lồng hai vùng cuộn cùng phương, nên vùng cuộn
                // trong AreaTool đã bị bỏ đi. Đừng thêm lại.
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (tool) {
                        CogoTool.NGHICH_DAO     -> InverseTool(points)
                        CogoTool.DIEM_PHUONG_VI -> PointTool(points, onStakeout)
                        CogoTool.DIEN_TICH      -> AreaTool(points)
                        else                    -> IntersectTool(points, onStakeout)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

/** Nghịch đảo: 2 điểm → khoảng cách + phương vị. */
@Composable
private fun InverseTool(points: List<CogoPoint>) {
    var n1 by remember { mutableStateOf("") }; var e1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }; var e2 by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    Column {
        PointInput("A", n1, e1, { n1 = it }, { e1 = it }, points)
        Spacer(Modifier.height(6.dp))
        PointInput("B", n2, e2, { n2 = it }, { e2 = it }, points)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val a = pd(n1); val b = pd(e1); val c = pd(n2); val d = pd(e2)
            out = if (a != null && b != null && c != null && d != null) {
                val r = Cogo.inverse(Cogo.Pt(a, b), Cogo.Pt(c, d))
                "Khoảng cách: %.3f m\nPhương vị: %.4f°  (%s)".format(r.distance, r.azimuthDeg, Cogo.azToDms(r.azimuthDeg))
            } else "Nhập/chọn đủ 2 điểm"
        }) { Text("Tính") }
        Spacer(Modifier.height(8.dp)); resultText(out)
    }
}

/** Điểm theo phương vị + khoảng cách từ 1 điểm gốc. */
@Composable
private fun PointTool(points: List<CogoPoint>, onStakeout: ((String, Double, Double) -> Unit)?) {
    var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
    var az by remember { mutableStateOf("") }; var dist by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<Cogo.Pt?>(null) }
    Column {
        PointInput("Gốc", n, e, { n = it }, { e = it }, points)
        Spacer(Modifier.height(6.dp))
        Row {
            numField(az, { az = it }, "Phương vị (°)", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); numField(dist, { dist = it }, "K.cách (m)", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val a = pd(n); val b = pd(e); val c = pd(az); val d = pd(dist)
            res = if (a != null && b != null && c != null && d != null)
                Cogo.pointByBearingDistance(Cogo.Pt(a, b), c, d) else null
        }) { Text("Tính") }
        Spacer(Modifier.height(8.dp))
        res?.let { p ->
            resultText("N = %.3f\nE = %.3f".format(p.n, p.e))
            if (onStakeout != null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onStakeout("COGO", p.n, p.e) }) { Text("Cắm mốc điểm này") }
            }
        }
    }
}

/**
 * Diện tích + chu vi. Chọn LOẠT ĐỈNH theo THỨ TỰ BẤM (thứ tự quyết định diện tích).
 * Menu KHÔNG đóng sau mỗi lần chọn → bấm liên tiếp nhiều đỉnh; danh sách đánh số + gỡ được.
 * Có thể xen kẽ nhập tay bằng ô "N E" rồi bấm ＋.
 */
@Composable
private fun AreaTool(points: List<CogoPoint>) {
    val verts = remember { mutableStateListOf<Cogo.Pt>() }
    val vertNames = remember { mutableStateListOf<String>() }
    var manN by remember { mutableStateOf("") }; var manE by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    fun add(name: String, p: Cogo.Pt) { verts.add(p); vertNames.add(name) }

    // TÍNH NGAY khi danh sách đỉnh đổi, không qua nút bấm.
    // Nút "Tính" từng nằm dưới cùng và bị khung thoại cắt mất khi đủ 4 đỉnh.
    // Bỏ hẳn nút thì không còn chỗ để hỏng, và người đo thấy diện tích lớn dần
    // theo từng đỉnh — bắt được lỗi chọn nhầm đỉnh sớm hơn.
    val out: String = if (verts.size < 3) {
        if (verts.isEmpty()) "" else "Cần ≥ 3 đỉnh (đang có ${verts.size})"
    } else {
        val r = Cogo.areaPerimeter(verts.toList())
        "Số đỉnh: %d\nDiện tích: %,.2f m²  (%.4f ha)\nChu vi: %,.3f m"
            .format(r.vertexCount, r.area, r.area / 10000.0, r.perimeter)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Loạt đỉnh (theo thứ tự bấm):", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }, enabled = points.isNotEmpty()) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = "Thêm đỉnh từ dữ liệu")
                }
                // Menu ở lại mở (không set menu=false trong onClick) để bấm liên tiếp.
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    points.take(300).forEach { p ->
                        DropdownMenuItem(
                            text = { Text("${p.name}  (${"%.2f".format(p.n)}, ${"%.2f".format(p.e)})", fontSize = 12.sp) },
                            onClick = { add(p.name, Cogo.Pt(p.n, p.e)) }
                        )
                    }
                }
            }
            if (verts.isNotEmpty())
                TextButton(onClick = { verts.clear(); vertNames.clear() }) { Text("Xoá hết") }
        }

        // ── XEM TRƯỚC HÌNH THỂ — kiểm tra thứ tự đỉnh trước khi tính ──
        // Chọn nhầm thứ tự sẽ tạo hình "thắt nút", diện tích sai mà nhìn số
        // không phát hiện được. Vẽ ra là thấy ngay.
        if (verts.size >= 2) {
            AreaPreview(verts.toList(), modifier = Modifier.fillMaxWidth().height(130.dp))
            Spacer(Modifier.height(4.dp))
        }

        // Kết quả đặt NGAY ĐÂY, trên danh sách đỉnh — thửa nhiều đỉnh thì danh
        // sách dài, để kết quả ở cuối là lại phải cuộn xuống mới thấy số.
        resultText(out)
        if (out.isNotEmpty()) Spacer(Modifier.height(6.dp))

        // Danh sách đỉnh đã chọn. KHÔNG đặt vùng cuộn ở đây — CogoDialog đã
        // bọc toàn bộ nội dung trong một vùng cuộn, lồng thêm là Compose ném
        // "Nesting scrollable in the same direction layouts is not allowed".
        Column(modifier = Modifier.fillMaxWidth()) {
            verts.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("${i + 1}. ${vertNames.getOrElse(i) { "" }}  (%.2f, %.2f)".format(p.n, p.e),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (i < verts.size) { verts.removeAt(i); vertNames.removeAt(i) } },
                        modifier = Modifier.width(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Bỏ đỉnh", modifier = Modifier.width(16.dp))
                    }
                }
            }
        }

        // Nhập tay 1 đỉnh rồi ＋
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            numField(manN, { manN = it }, "N", Modifier.weight(1f))
            Spacer(Modifier.width(4.dp)); numField(manE, { manE = it }, "E", Modifier.weight(1f))
            IconButton(onClick = {
                val a = pd(manN); val b = pd(manE)
                if (a != null && b != null) { add("tay", Cogo.Pt(a, b)); manN = ""; manE = "" }
            }) { Icon(Icons.Default.PlaylistAdd, contentDescription = "Thêm đỉnh nhập tay") }
        }
    }
}

/**
 * AreaPreview — Vẽ hình thể các đỉnh ĐÃ CHỌN theo đúng thứ tự bấm.
 *
 * Mục đích: phát hiện chọn nhầm thứ tự. Nối sai thứ tự sẽ ra hình "thắt nút"
 * (bow-tie) — diện tích tính ra vẫn có số nhưng SAI, nhìn con số không biết được.
 * Cạnh khép kín (đỉnh cuối → đỉnh đầu) vẽ nét đứt để phân biệt.
 */
@Composable
private fun AreaPreview(verts: List<Cogo.Pt>, modifier: Modifier = Modifier) {
    val outline   = MaterialTheme.colorScheme.primary
    val fillCol   = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val closeCol  = MaterialTheme.colorScheme.error
    val labelCol  = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            if (verts.size < 2) return@Canvas
            val minN = verts.minOf { it.n }; val maxN = verts.maxOf { it.n }
            val minE = verts.minOf { it.e }; val maxE = verts.maxOf { it.e }
            val spanN = (maxN - minN).coerceAtLeast(1e-6)
            val spanE = (maxE - minE).coerceAtLeast(1e-6)
            val pad = 22f
            val s = minOf((size.width - pad * 2) / spanE, (size.height - pad * 2) / spanN)
            val cx = size.width / 2f; val cy = size.height / 2f
            val mN = (minN + maxN) / 2.0; val mE = (minE + maxE) / 2.0
            fun px(e: Double) = (cx + (e - mE) * s).toFloat()
            fun py(n: Double) = (cy - (n - mN) * s).toFloat()   // Bắc lên trên

            val pts = verts.map { androidx.compose.ui.geometry.Offset(px(it.e), py(it.n)) }

            // Tô nền vùng (thấy ngay nếu hình bị thắt nút)
            if (pts.size >= 3) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, fillCol)
            }
            // Các cạnh theo thứ tự bấm
            for (i in 0 until pts.lastIndex) {
                drawLine(outline, pts[i], pts[i + 1], strokeWidth = 2.5f)
            }
            // Cạnh khép kín — nét đứt, màu khác
            if (pts.size >= 3) {
                drawLine(
                    closeCol, pts.last(), pts.first(), strokeWidth = 2f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect
                        .dashPathEffect(floatArrayOf(8f, 6f))
                )
            }
            // Đỉnh + số thứ tự
            pts.forEachIndexed { i, o ->
                drawCircle(outline, radius = 4f, center = o)
                drawContext.canvas.nativeCanvas.drawText(
                    "${i + 1}", o.x + 7f, o.y - 5f,
                    android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 26f
                        isFakeBoldText = true
                        color = android.graphics.Color.argb(
                            255, (labelCol.red * 255).toInt(),
                            (labelCol.green * 255).toInt(), (labelCol.blue * 255).toInt()
                        )
                    }
                )
            }
        }
    }
}

/** Giao hội: 3 kiểu (P.vị–P.vị, P.vị–K.cách, K.cách–K.cách). */
@Composable
private fun IntersectTool(points: List<CogoPoint>, onStakeout: ((String, Double, Double) -> Unit)?) {
    var mode by remember { mutableStateOf(0) }
    val modes = listOf("PV–PV", "PV–K.c", "K.c–K.c")
    var n1 by remember { mutableStateOf("") }; var e1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }; var e2 by remember { mutableStateOf("") }
    var v1 by remember { mutableStateOf("") }; var v2 by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<List<Cogo.Pt>>(emptyList()) }
    var msg by remember { mutableStateOf("") }

    Column {
        Row {
            modes.forEachIndexed { i, m ->
                FilterChip(selected = mode == i, onClick = { mode = i; res = emptyList(); msg = "" }, label = { Text(m) })
                if (i < modes.lastIndex) Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        PointInput("1", n1, e1, { n1 = it }, { e1 = it }, points)
        Spacer(Modifier.height(4.dp))
        numField(v1, { v1 = it }, if (mode == 2) "K.cách 1 (m)" else "Phương vị 1 (°)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PointInput("2", n2, e2, { n2 = it }, { e2 = it }, points)
        Spacer(Modifier.height(4.dp))
        numField(v2, { v2 = it }, if (mode == 0) "Phương vị 2 (°)" else "K.cách 2 (m)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val a = pd(n1); val b = pd(e1); val c = pd(n2); val d = pd(e2)
            val x = pd(v1); val y = pd(v2)
            if (a == null || b == null || c == null || d == null || x == null || y == null) {
                msg = "Nhập/chọn đủ tham số"; res = emptyList()
            } else {
                val p1 = Cogo.Pt(a, b); val p2 = Cogo.Pt(c, d)
                res = when (mode) {
                    0 -> Cogo.intersectBearingBearing(p1, x, p2, y)?.let { listOf(it) } ?: emptyList()
                    1 -> Cogo.intersectBearingDistance(p1, x, p2, y)
                    else -> Cogo.intersectDistanceDistance(p1, x, p2, y)
                }
                msg = if (res.isEmpty()) "Không có giao điểm" else ""
            }
        }) { Text("Tính") }
        Spacer(Modifier.height(8.dp))
        if (msg.isNotEmpty()) resultText(msg)
        res.forEachIndexed { i, p ->
            resultText("Giao ${i + 1}:  N = %.3f   E = %.3f".format(p.n, p.e))
            if (onStakeout != null)
                OutlinedButton(onClick = { onStakeout("GIAO${i + 1}", p.n, p.e) }) { Text("Cắm mốc giao ${i + 1}") }
        }
    }
}
