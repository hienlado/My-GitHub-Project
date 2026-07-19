package com.hien.rtkmultidevice.ui.screens.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hien.rtkmultidevice.core.cogo.Cogo

/**
 * Nút + hộp thoại COGO (4 công cụ): Nghịch đảo, Điểm theo phương vị–k.cách,
 * Diện tích+chu vi, Giao hội. Toạ độ VN-2000 (N=Northing, E=Easting, mét).
 *
 * onStakeout != null: cho phép gửi điểm kết quả sang Cắm mốc.
 */
@Composable
fun CogoButton(
    modifier: Modifier = Modifier,
    onStakeout: ((name: String, n: Double, e: Double) -> Unit)? = null,
) {
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }, modifier = modifier) {
        Icon(Icons.Default.Calculate, contentDescription = "Công cụ COGO")
    }
    if (show) CogoDialog(onDismiss = { show = false }, onStakeout = onStakeout)
}

private fun pd(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

@Composable
private fun numField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
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
    onStakeout: ((name: String, n: Double, e: Double) -> Unit)? = null,
) {
    var tool by remember { mutableStateOf(0) }
    val tabs = listOf("Nghịch đảo", "Điểm P.vị", "Diện tích", "Giao hội")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Công cụ COGO") },
        text = {
            Column {
                // Chọn công cụ
                Row {
                    tabs.forEachIndexed { i, name ->
                        FilterChip(selected = tool == i, onClick = { tool = i }, label = { Text(name) })
                        if (i < tabs.lastIndex) Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                when (tool) {
                    0 -> InverseTool()
                    1 -> PointTool(onStakeout)
                    2 -> AreaTool()
                    else -> IntersectTool(onStakeout)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

/** Nghịch đảo: 2 điểm → khoảng cách + phương vị. */
@Composable
private fun InverseTool() {
    var n1 by remember { mutableStateOf("") }; var e1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }; var e2 by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    Column {
        Row {
            numField(n1, { n1 = it }, "N1 (X)", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); numField(e1, { e1 = it }, "E1 (Y)", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            numField(n2, { n2 = it }, "N2 (X)", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); numField(e2, { e2 = it }, "E2 (Y)", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val a = pd(n1); val b = pd(e1); val c = pd(n2); val d = pd(e2)
            out = if (a != null && b != null && c != null && d != null) {
                val r = Cogo.inverse(Cogo.Pt(a, b), Cogo.Pt(c, d))
                "Khoảng cách: %.3f m\nPhương vị: %.4f°  (%s)".format(r.distance, r.azimuthDeg, Cogo.azToDms(r.azimuthDeg))
            } else "Nhập đủ 4 toạ độ"
        }) { Text("Tính") }
        Spacer(Modifier.height(8.dp)); resultText(out)
    }
}

/** Điểm theo phương vị + khoảng cách từ 1 điểm gốc. */
@Composable
private fun PointTool(onStakeout: ((String, Double, Double) -> Unit)?) {
    var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
    var az by remember { mutableStateOf("") }; var dist by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<Cogo.Pt?>(null) }
    Column {
        Row {
            numField(n, { n = it }, "N gốc", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); numField(e, { e = it }, "E gốc", Modifier.weight(1f))
        }
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

/** Diện tích + chu vi từ danh sách đỉnh nhập tay (mỗi dòng: N E hoặc N,E). */
@Composable
private fun AreaTool() {
    var text by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    Column {
        Text("Nhập đỉnh, mỗi dòng: N E (hoặc N,E)", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Danh sách đỉnh") },
            modifier = Modifier.fillMaxWidth().height(140.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val pts = text.lineSequence().mapNotNull { line ->
                val parts = line.trim().split(',', ' ', '\t', ';').filter { it.isNotBlank() }
                if (parts.size < 2) null
                else {
                    val a = pd(parts[0]); val b = pd(parts[1])
                    if (a != null && b != null) Cogo.Pt(a, b) else null
                }
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
private fun IntersectTool(onStakeout: ((String, Double, Double) -> Unit)?) {
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
        Row {
            numField(n1, { n1 = it }, "N1", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); numField(e1, { e1 = it }, "E1", Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        numField(v1, { v1 = it }, if (mode == 2) "K.cách 1 (m)" else "Phương vị 1 (°)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            numField(n2, { n2 = it }, "N2", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); numField(e2, { e2 = it }, "E2", Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        numField(v2, { v2 = it }, if (mode == 0) "Phương vị 2 (°)" else "K.cách 2 (m)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val a = pd(n1); val b = pd(e1); val c = pd(n2); val d = pd(e2)
            val x = pd(v1); val y = pd(v2)
            if (a == null || b == null || c == null || d == null || x == null || y == null) {
                msg = "Nhập đủ tham số"; res = emptyList()
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
            if (onStakeout != null) {
                OutlinedButton(onClick = { onStakeout("GIAO${i + 1}", p.n, p.e) }) { Text("Cắm mốc giao ${i + 1}") }
            }
        }
    }
}
