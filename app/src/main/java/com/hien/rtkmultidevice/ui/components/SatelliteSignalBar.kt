package com.hien.rtkmultidevice.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hien.rtkmultidevice.domain.model.SatelliteInfo

/**
 * SatelliteSignalBar — Biểu đồ cột tín hiệu vệ tinh.
 *
 * Hiển thị toàn bộ danh sách vệ tinh dưới dạng bar chart:
 *   - Chiều cao cột tỉ lệ với SNR (0–50 dBHz)
 *   - Màu theo constellation (GPS=xanh, GLONASS=đỏ, Galileo=xanh lá, BeiDou=cam)
 *   - Cột sáng hơn = vệ tinh đang được dùng tính vị trí
 *   - Nhãn PRN bên dưới mỗi cột
 *
 * @param satellites Danh sách vệ tinh cần hiển thị
 * @param modifier Modifier tùy chỉnh layout
 * @param barWidth Độ rộng mỗi cột (dp)
 * @param maxHeight Chiều cao tối đa biểu đồ (dp)
 */
@Composable
fun SatelliteSignalBar(
    satellites: List<SatelliteInfo>,
    modifier: Modifier = Modifier,
    barWidth: Float = 16f,
    maxHeight: Float = 120f
) {
    if (satellites.isEmpty()) {
        EmptySatelliteHint(modifier)
        return
    }

    Column(modifier = modifier) {
        // ── Biểu đồ cột ────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxHeight.dp)
        ) {
            val canvasWidth  = size.width
            val canvasHeight = size.height

            val totalBars  = satellites.size
            val spacing    = 4f   // khoảng cách giữa các cột (px)
            val barWidthPx = barWidth * density

            // Tính toán vị trí bắt đầu để căn giữa toàn bộ biểu đồ
            val totalWidth = totalBars * (barWidthPx + spacing) - spacing
            var startX     = (canvasWidth - totalWidth) / 2f
            if (startX < 0) startX = 0f

            satellites.forEach { sat ->
                val color      = parseColor(sat.constellation.colorHex)
                val barAlpha   = if (sat.isUsed) 1f else 0.4f
                val barColor   = color.copy(alpha = barAlpha)

                // Chiều cao cột tỉ lệ với normalizedSnr (0.0–1.0)
                val barHeight  = (sat.normalizedSnr * (canvasHeight - 12.dp.toPx()))
                    .coerceAtLeast(2.dp.toPx())  // cột tối thiểu 2dp để thấy vệ tinh
                val top        = canvasHeight - barHeight - 2.dp.toPx()

                // Vẽ cột với góc bo tròn ở trên
                drawRoundRect(
                    color       = barColor,
                    topLeft     = Offset(startX, top),
                    size        = Size(barWidthPx, barHeight),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )

                // Đánh dấu vệ tinh đang dùng bằng chấm trắng nhỏ trên đỉnh cột
                if (sat.isUsed) {
                    drawCircle(
                        color  = Color.White,
                        radius = 2.dp.toPx(),
                        center = Offset(startX + barWidthPx / 2f, top - 4.dp.toPx())
                    )
                }

                startX += barWidthPx + spacing
            }
        }

        // ── Nhãn PRN bên dưới ──────────────────────────────
        // Hiển thị số PRN cho mỗi cột
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            val barWidthDp = barWidth.dp
            val spacingDp  = 4.dp

            satellites.forEach { sat ->
                Text(
                    text      = sat.prn.toString(),
                    modifier  = Modifier.width(barWidthDp),
                    fontSize  = 6.sp,
                    textAlign = TextAlign.Center,
                    color     = parseColor(sat.constellation.colorHex)
                        .copy(alpha = if (sat.isUsed) 1f else 0.5f)
                )
                Spacer(modifier = Modifier.width(spacingDp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Legend ──────────────────────────────────────────
        ConstellationLegend(satellites)
    }
}

/**
 * Legend hiển thị số lượng vệ tinh theo constellation.
 */
@Composable
private fun ConstellationLegend(satellites: List<SatelliteInfo>) {
    val groups = satellites.groupBy { it.constellation }
    if (groups.isEmpty()) return

    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment   = Alignment.CenterVertically
    ) {
        groups.entries.take(5).forEach { (constellation, sats) ->
            val color  = parseColor(constellation.colorHex)
            val used   = sats.count { it.isUsed }
            val total  = sats.size

            Row(
                modifier          = Modifier.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Chấm màu
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text      = "${constellation.label} $used/$total",
                    fontSize  = 9.sp,
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Màn hình rỗng khi chưa có dữ liệu vệ tinh.
 */
@Composable
private fun EmptySatelliteHint(modifier: Modifier = Modifier) {
    Box(
        modifier          = modifier.height(160.dp),
        contentAlignment  = Alignment.Center
    ) {
        Text(
            text      = "Chưa nhận được dữ liệu vệ tinh\n(Đang chờ câu GSV...)",
            textAlign = TextAlign.Center,
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

/**
 * Chuyển đổi hex color "#FFRRGGBB" hoặc "#RRGGBB" sang Compose Color.
 */
private fun parseColor(hex: String): Color {
    return try {
        val clean = hex.removePrefix("#")
        when (clean.length) {
            8 -> Color(clean.toLong(16).toInt())   // ARGB 8 digits
            6 -> Color(("FF$clean").toLong(16).toInt())  // RGB 6 digits
            else -> Color.Gray
        }
    } catch (e: Exception) {
        Color.Gray
    }
}

/**
 * SatelliteSnrText — Hiển thị SNR dạng số cho một vệ tinh.
 * Dùng khi cần tooltip hoặc chi tiết.
 */
@Composable
fun SatelliteSnrText(
    satellite: SatelliteInfo,
    modifier: Modifier = Modifier
) {
    val color = parseColor(satellite.constellation.colorHex)
    Text(
        text     = "${satellite.prn}: ${satellite.snr} dBHz",
        modifier = modifier,
        color    = color,
        fontSize = 11.sp,
        fontWeight = if (satellite.isUsed) FontWeight.Bold else FontWeight.Normal
    )
}
