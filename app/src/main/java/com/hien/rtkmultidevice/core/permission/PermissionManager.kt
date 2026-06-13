package com.hien.rtkmultidevice.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PermissionManager — Kiểm tra trạng thái các permission cần thiết.
 *
 * Android chia permission BT theo API level:
 *   API < 31 (Android 11 trở xuống):
 *     → Chỉ cần BLUETOOTH + BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION
 *   API >= 31 (Android 12+):
 *     → Cần BLUETOOTH_SCAN + BLUETOOTH_CONNECT (không cần LOCATION nếu không scan)
 *
 * Lưu ý: Lớp này CHỈ KIỂM TRA, không REQUEST.
 * Việc request phải thực hiện từ Activity/Composable
 * vì cần Activity context và launcher.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Danh sách permission cần thiết theo API level.
     * Trả về array rỗng nếu tất cả đã được cấp.
     */
    fun getRequiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // Android 11 trở xuống
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    /** True nếu tất cả BT permission đã được cấp */
    fun hasBluetoothPermissions(): Boolean =
        getRequiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }

    /** True nếu permission location đã cấp (cần cho BT scan trên API < 31) */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Lấy danh sách permission chưa được cấp.
     * Dùng để chỉ request những cái còn thiếu.
     */
    fun getMissingPermissions(): Array<String> =
        getRequiredBluetoothPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    /** Trạng thái permission tổng hợp để UI hiển thị */
    fun getPermissionState(): BluetoothPermissionState {
        val missing = getMissingPermissions()
        return when {
            missing.isEmpty()                               -> BluetoothPermissionState.Granted
            missing.size == getRequiredBluetoothPermissions().size -> BluetoothPermissionState.AllDenied
            else                                            -> BluetoothPermissionState.PartiallyDenied(missing)
        }
    }
}

/**
 * Trạng thái permission Bluetooth cho UI.
 */
sealed class BluetoothPermissionState {
    data object Granted : BluetoothPermissionState()
    data object AllDenied : BluetoothPermissionState()
    data class PartiallyDenied(val missing: Array<String>) : BluetoothPermissionState()
}
