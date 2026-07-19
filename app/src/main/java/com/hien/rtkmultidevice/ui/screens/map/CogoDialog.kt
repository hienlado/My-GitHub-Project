package com.hien.rtkmultidevice.ui.screens.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
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
) {
    var tool by remember { mutableStateOf(0) }
    val tabs = listOf("Nghịch đảo", "Điểm P.vị", "Diện tích", "Giao hội")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Công cụ COGO") },
        text = {
            Column {
                Row {
                    tabs.forEachIndexed { i, name ->
                        FilterChip(selected = tool == i, onClick = { tool = i }, label = { Text(name) })
                        if (i < tabs.lastIndex) Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                when (tool) {
                    0 -> InverseTool(points)
                    1 -> PointTool(points, onStakeout)
                    2 -> AreaTool(points)
                    else -> IntersectTool(points, onStakeout)
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

/** Diện tích + chu vi từ danh sách đỉnh (nhập tay hoặc thêm từ dữ liệu). */
@Composable
private fun AreaTool(points: List<CogoPoint>) {
    var text by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Đỉnh (mỗi dòng: N E)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }, enabled = points.isNotEmpty()) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = "Thêm đỉnh từ dữ liệu")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    points.take(300).forEach { p ->
                        DropdownMenuItem(
                            text = { Text("${p.name}  (${"%.2f".format(p.n)}, ${"%.2f".format(p.e)})", fontSize = 12.sp) },
                            onClick = {
                                text = if (text.isBlank()) "${fmt(p.n)} ${fmt(p.e)}"
                                       else "$text\n${fmt(p.n)} ${fmt(p.e)}"
                                menu = false
                            }
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Danh sách đỉnh") },
            modifier = Modifier.fillMaxWidth().height(130.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val pts = text.lineSequence().mapNotNull { line ->
                val parts = line.trim().split(',', ' ', '\t', ';').filter { it.isNotBlank() }
                if (parts.size < 2) null
                else { val a = pd(parts[0]); val b = pd(parts[1]); if (a != null && b != null) Cogo.Pt(a, b) else null }
            }.toList()
            out = if (pts.size < 3) "Cần ≥ 3 đỉnh hợp lệ"
            else {
                val r = Cogo.areaPerimeter(pts)
                "Số đỉnh: %d\nDiện tích: %,.2f m²  (%.4f ha)\nChu vi: %,.3f m"
                    .format(r.vertexCount, r.area, r.area / 10000.0, r.perimeter)
            }
        }) { Text("Tính") }
        Spacer(Modifier.height(8.dp)); resultText(out)
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
