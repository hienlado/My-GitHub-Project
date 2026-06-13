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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    // Cuộn đến đỉnh được chạm
    LaunchedEffect(highlightIdx) {
        if (highlightIdx in feature.rawPoints.indices) {
            listState.scrollToItem(highlightIdx.coerceAtLeast(0))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Bảng toạ độ đỉnh", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "$typeLabel \"$baseLbl\" — ${feature.rawPoints.size} đỉnh • chạm dòng để cắm mốc",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
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
                    itemsIndexed(feature.rawPoints) { idx, raw ->
                        // VN-2000: raw.first = Easting (Y), raw.second = Northing (X)
                        val easting  = raw.first
                        val northing = raw.second
                        val vLabel   = vertexLabel(feature, baseLbl, idx)
                        val dist = if (hasDist)
                            hypot(northing - currentNorthing!!, easting - currentEasting!!)
                        else null

                        Surface(
                            color = if (idx == highlightIdx)
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
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Đóng") }
        }
    )
}

/** Nhãn đỉnh: tên feature + Đầu/Cuối/số thứ tự đỉnh */
private fun vertexLabel(
    feature : VectorLayerImporter.VectorFeature,
    baseLbl : String,
    idx     : Int
): String = when (feature.type) {
    VectorLayerImporter.FeatureType.POINT -> baseLbl
    else -> when (idx) {
        0                            -> "$baseLbl-Đầu"
        feature.rawPoints.lastIndex  -> "$baseLbl-Cuối"
        else                         -> "$baseLbl-Đ$idx"
    }
}

private fun formatDistance(d: Double): String = when {
    d < 1_000.0     -> "%.1f".format(Locale.US, d)
    d < 100_000.0   -> "%.2fkm".format(Locale.US, d / 1000.0)
    else            -> "%.0fkm".format(Locale.US, d / 1000.0)
}
