package com.hien.rtkmultidevice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.hien.rtkmultidevice.ui.navigation.AppNavGraph
import com.hien.rtkmultidevice.ui.theme.RTKAppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity — Entry point của ứng dụng.
 *
 * So sánh với code cũ:
 *   ❌ Cũ: ~480 dòng code, chứa logic BT + NMEA + NTRIP + UI
 *   ✅ Mới: ~20 dòng, chỉ khởi tạo Compose và NavGraph
 *
 * @AndroidEntryPoint → Hilt inject vào Activity này.
 * Bắt buộc phải có khi Activity dùng @HiltViewModel.
 *
 * Toàn bộ logic đã chuyển đến:
 *   - ConnectionViewModel (kết nối thiết bị)
 *   - GnssViewModel (xử lý GNSS)
 *   - GnssDataManager (parse NMEA + NTRIP)
 *   - ConnectionManager (quản lý kết nối)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()  // Hiển thị toàn màn hình (edge-to-edge)

        setContent {
            RTKAppTheme {
                val navController = rememberNavController()
                AppNavGraph(navController = navController)
            }
        }
    }
}
