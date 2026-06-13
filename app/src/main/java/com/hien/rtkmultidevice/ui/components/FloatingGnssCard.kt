package com.hien.rtkmultidevice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hien.rtkmultidevice.domain.model.GnssStatus
import kotlin.math.roundToInt

/**
 * FloatingGnssCard — Card trạng thái GNSS nổi, có thể kéo đến bất kỳ vị trí nào.
 *
 * Dùng trên SurveyScreen và StakeoutScreen để user luôn theo dõi tín hiệu
 * mà không cần rời màn hình chính.
 *
 * Kéo bằng handle (icon ≡ ở góc trái).
 * Vị trí được ghi nhớ trong session (mỗi lần mở lại về góc dưới phải).
 */
@Composable
fun FloatingGnssCard(
    gnss     : GnssStatus,
    modifier : Modifier = Modifier
) {
    // Vị trí offset hiện tại — bắt đầu từ góc trên phải
    var offset by remember { mutableStateOf(Offset(0f, 0f)) }

    // Màu theo fix quality
    val fixColor = when (gnss.fixQuality) {
        4    -> Color(0xFF2E7D32)   // RTK Fixed — xanh đậm
        5    -> Color(0xFF558B2F)   // RTK Float — xanh vừa
        2    -> Color(0xFF1565C0)   // DGPS — xanh dương
        1    -> Color(0xFFF57F17)   // Single — vàng
        else -> Color(0xFFB71C1C)   // No fix — đỏ
    }
    val fixLabel = when (gnss.fixQuality) {
        4    -> "FIXED"
        5    -> "FLOAT"
        2    -> "DGPS"
        1    -> "SINGLE"
        else -> "NO FIX"
    }
    val fixIcon = when {
        gnss.fixQuality >= 4 -> Icons.Default.GpsFixed
        gnss.fixQuality >= 1 -> Icons.Default.GpsNotFixed
        else                 -> Icons.Default.GpsOff
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
    ) {
        Surface(
            shape         = RoundedCornerShape(10.dp),
            color         = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                // ── Drag handle ────────────────────────────────
                Icon(
                    imageVector        = Icons.Default.DragIndicator,
                    contentDescription = "Kéo thả",
                    tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier           = Modifier
                        .size(16.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                offset = Offset(
                                    x = offset.x + dragAmount.x,
                                    y = offset.y + dragAmount.y
                                )
                            }
                        }
                )

                Spacer(Modifier.width(4.dp))

                // ── Fix indicator ──────────────────────────────
                Icon(
                    imageVector        = fixIcon,
                    contentDescription = null,
                    tint               = fixColor,
                    modifier           = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    fixLabel,
                    fontSize   = 11.sp,
                    color      = fixColor,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.width(8.dp))
                VerticalDivider(
                    modifier  = Modifier.height(16.dp),
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                )
                Spacer(Modifier.width(8.dp))

                // ── GNSS values ────────────────────────────────
                if (gnss.vn2000 != null) {
                    Column {
                        Text(
                            "X: ${"%.3f".format(gnss.vn2000!!.northing)} m",
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color      = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Y: ${"%.3f".format(gnss.vn2000!!.easting)} m",
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color      = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Column {
                        Text(
                            "φ: ${"%.6f".format(gnss.latitude)}°",
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color      = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "λ: ${"%.6f".format(gnss.longitude)}°",
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color      = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                // ── HDOP + sats ────────────────────────────────
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "H:${"%.1f".format(gnss.hdop)}",
                        fontSize = 10.sp,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        "${gnss.satelliteCount}sv",
                        fontSize = 10.sp,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
