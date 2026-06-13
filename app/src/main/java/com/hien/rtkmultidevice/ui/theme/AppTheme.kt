package com.hien.rtkmultidevice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * RTKAppTheme — Theme field-friendly cho ứng dụng RTK.
 *
 * Màu chủ đạo: xanh dương đậm (dễ đọc dưới nắng)
 * Dark mode: hỗ trợ đầy đủ (dùng ban đêm ngoài thực địa)
 */

// ── Màu sắc cơ bản ──────────────────────────────────────────
private val RtkBlue    = Color(0xFF1565C0)  // Xanh dương đậm
private val RtkBlueLight = Color(0xFF5E92F3)
private val RtkSurface = Color(0xFFF8F9FA)

private val LightColors = lightColorScheme(
    primary         = RtkBlue,
    onPrimary       = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    secondary       = Color(0xFF546E7A),
    background      = RtkSurface,
    surface         = Color.White,
    error           = Color(0xFFB00020)
)

private val DarkColors = darkColorScheme(
    primary         = RtkBlueLight,
    onPrimary       = Color.Black,
    primaryContainer = Color(0xFF1A3A6E),
    secondary       = Color(0xFF90A4AE),
    background      = Color(0xFF121212),
    surface         = Color(0xFF1E1E1E),
    error           = Color(0xFFCF6679)
)

@Composable
fun RTKAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content     = content
    )
}
