package com.hien.rtkmultidevice.ui.screens.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.hypot

/**
 * VertexTableDialog — Bảng toạ độ các đỉnh của một đối tượng vector
 * (line/polyline/polygon/point) được import từ DXF/SHP.
 *
 * Mỗi dòng: STT | X (Northing) | Y (Easting) | Δ khoảng cách đến vị trí hiện tại.
 * Chạm vào dòng → chọn đỉnh đó làm điểm cắm mốc (stakeout).
 *
 * Chỉ dùng cho feature VN-2000 (rawPoints: first=Easting, second=Northing).
 */
@Composable
fun VertexTableDialog(
    feature         : VectorLayerImporter.VectorFeature,
    /** Đỉnh được chạm trên bản đồ — highlight + cuộn đến */
    highlightIdx    : Int = -1,
    /** Vị trí hiện tại (VN-2000) để tính cột khoảng cách — null nếu chưa có GPS */
    currentNorthing : Double? = null,
    currentEasting  : Double? = null,
    onPick          : (label: String, northing: Double, easting: Double) -> Unit,
    /** Gửi toàn bộ đỉnh sang ngăn "Đỉnh thửa" ở Danh sách (null = ẩn nút) */
    onSendToList    : (() -> Unit)? = null,
    /** Xuất Biên bản dạng XML để kiểm tra/sửa */
    onExportXml     : (() -> Unit)? = null,
    /** Xuất Biên bản PDF (sau khi XML đã hoàn chỉnh) */
    onExportPdf     : (() -> Unit)? = null,
    onDismiss       : () -> Unit
) {
    val typeLabel = when (feature.type) {
        VectorLayerImporter.FeatureType.POINT    -> "Điểm"
        VectorLayerImporter.FeatureType.POLYLINE -> "Đường"
        VectorLayerImporter.FeatureType.POLYGON  -> "Vùng"
    }
    val baseLbl  = feature.label.ifEmpty { "VEC-${feature.id}" }
    val hasDist  = currentNorthing != null && currentEasting != null
    val listState = rememberLazyListState()

    // ── LỌC ĐỈNH THỪA — chỉ với VÙNG (thửa đất) ──────────────────────────
    // Bảng này chính là "bảng toạ độ" đi kèm biên bản, nên nó phải đánh số
    // đỉnh GIỐNG HỆT sơ hoạ ở trang 2. Lọc bên này mà không lọc bên kia là
    // biên bản ghi mốc số 7 còn sơ hoạ chỉ tới số 5.
    // ĐƯỜNG và ĐIỂM giữ nguyên: chúng dùng để cắm mốc từng đỉnh một, bỏ bớt
    // đỉnh ở đó là bỏ mất mốc người ta cần ra thực địa tìm.
    val laVung = feature.type == VectorLayerImporter.FeatureType.POLYGON
    val diem = remember(feature.id, laVung) {
        if (!laVung) feature.rawPoints
        else com.hien.rtkmultidevice.report.LocDinh
            .loc(feature.rawPoints.map { it.second to it.first })
            .map { it.second to it.first }          // trả về (E, N) như rawPoints
    }
    val soDinhBo = remember(diem) {
        (feature.rawPoints.size - diem.size).coerceAtLeast(0)
    }

    // Đỉnh được chạm trên bản đồ mang chỉ số của danh sách GỐC. Sau khi lọc,
    // chỉ số lệch đi — nên dò lại theo TOẠ ĐỘ, không dùng thẳng chỉ số.
    val highlightLoc = remember(highlightIdx, diem) {
        val g = feature.rawPoints.getOrNull(highlightIdx) ?: return@remember -1
        diem.indices.minByOrNull {
            hypot(diem[it].first - g.first, diem[it].second - g.second)
        } ?: -1
    }

    // Cuộn đến đỉnh được chạm
    LaunchedEffect(highlightLoc) {
        if (highlightLoc in diem.indices) {
            listState.scrollToItem(highlightLoc.coerceAtLeast(0))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Bảng toạ độ đỉnh", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "$typeLabel \"$baseLbl\" — ${diem.size} đỉnh" +
                    (if (soDinhBo > 0) " (đã gộp $soDinhBo)" else "") +
                    " • chạm dòng để cắm mốc",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                // ── Hành động chính — đặt ở ĐẦU để luôn nhìn thấy ──
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (onExportPdf != null && diem.size >= 3) {
                        Button(
                            onClick = onExportPdf,
                            modifier = Modifier.weight(1f)
                        ) { Text("Lập biên bản", fontSize = 12.sp) }
                        Spacer(Modifier.width(6.dp))
                    }
                    // Đưa toàn bộ đỉnh sang ngăn "Đỉnh thửa" ở Danh sách để
                    // định vị điểm / cạnh / cả đường bao.
                    if (onSendToList != null && diem.size >= 2) {
                        OutlinedButton(
                            onClick = { onSendToList(); onDismiss() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Gửi Danh sách", fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // ── Header bảng ─────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("#", Modifier.width(30.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("X — Northing", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Y — Easting",  Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (hasDist) {
                        Text("Δ (m)", Modifier.width(62.dp), fontSize = 11.sp,
                             fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    }
                }
                HorizontalDivider()

                // ── Các dòng đỉnh ───────────────────────────
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(diem) { idx, raw ->
                        // VN-2000: raw.first = Easting (Y), raw.second = Northing (X)
                        val easting  = raw.first
                        val northing = raw.second
                        val vLabel   = vertexLabel(feature, baseLbl, idx, diem.lastIndex)
                        val dist = if (hasDist)
                            hypot(northing - currentNorthing!!, easting - currentEasting!!)
                        else null

                        Surface(
                            color = if (idx == highlightLoc)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(vLabel, northing, easting) }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${idx + 1}", Modifier.width(30.dp),
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "%.3f".format(Locale.US, northing), Modifier.weight(1f),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "%.3f".format(Locale.US, easting), Modifier.weight(1f),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                                )
                                if (dist != null) {
                                    Text(
                                        formatDistance(dist), Modifier.width(62.dp),
                                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.End,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        )
                    }
                }
            }
        },
        // CHỈ để "Đóng" ở hàng nút. Các hành động chính đã đưa lên ĐẦU thân
        // hộp thoại — hàng nút của AlertDialog hẹp, nhãn tiếng Việt dài sẽ
        // bị tràn/xuống dòng và nút "Biên bản" biến mất khỏi tầm nhìn.
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Đóng") }
        }
    )
}

/** Nhãn đỉnh: tên feature + Đầu/Cuối/số thứ tự đỉnh */
private fun vertexLabel(
    feature : VectorLayerImporter.VectorFeature,
    baseLbl : String,
    idx     : Int,
    /** Chỉ số đỉnh cuối SAU KHI LỌC — không dùng feature.rawPoints.lastIndex nữa. */
    lastIdx : Int
): String = when (feature.type) {
    VectorLayerImporter.FeatureType.POINT -> baseLbl
    else -> when (idx) {
        0       -> "$baseLbl-Đầu"
        lastIdx -> "$baseLbl-Cuối"
        else    -> "$baseLbl-Đ$idx"
    }
}

private fun formatDistance(d: Double): String = when {
    d < 1_000.0     -> "%.1f".format(Locale.US, d)
    d < 100_000.0   -> "%.2fkm".format(Locale.US, d / 1000.0)
    else            -> "%.0fkm".format(Locale.US, d / 1000.0)
}
