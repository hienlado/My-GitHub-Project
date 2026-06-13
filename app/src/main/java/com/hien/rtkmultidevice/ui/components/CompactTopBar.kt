package com.hien.rtkmultidevice.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CompactTopBar — Thanh tiêu đề thu gọn (42dp thay vì 64dp của TopAppBar).
 *
 * Mục đích: tối đa hoá không gian hiển thị bản đồ/đồ hoạ trên các màn hình
 * Đo điểm / Cắm mốc / Bản đồ. Chữ 13sp, icon 19dp, nút 38dp.
 *
 * Dùng kèm [CompactActionIcon] cho các nút hành động bên phải.
 */
@Composable
fun CompactTopBar(
    title    : String,
    subtitle : String? = null,
    onBack   : (() -> Unit)? = null,
    actions  : @Composable RowScope.() -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.primary, shadowElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(42.dp)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Quay lại",
                        Modifier.size(19.dp), tint = Color.White
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack == null) 10.dp else 2.dp)
            ) {
                Text(
                    title,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        fontSize = 9.sp,
                        color    = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            actions()
        }
    }
}

/** Nút icon thu gọn dùng trong [CompactTopBar] — 38dp, icon 19dp */
@Composable
fun CompactActionIcon(
    icon               : ImageVector,
    contentDescription : String?,
    tint               : Color = Color.White,
    enabled            : Boolean = true,
    onClick            : () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(38.dp)) {
        Icon(
            icon, contentDescription,
            Modifier.size(19.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.4f)
        )
    }
}
