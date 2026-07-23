package com.hien.rtkmultidevice.core.connection.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.hien.rtkmultidevice.domain.model.DeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BluetoothDeviceSource — Nguồn cung cấp danh sách thiết bị BT.
 *
 * Chức năng:
 *   1. Lấy danh sách thiết bị đã ghép đôi (paired/bonded)
 *   2. Lọc thiết bị RTK theo tên phổ biến
 *
 * Dùng BluetoothManager thay vì BluetoothAdapter.getDefaultAdapter()
 * (deprecated từ API 31 / Android 12).
 */
@Singleton
class BluetoothDeviceSource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    /**
     * Lấy tất cả thiết bị BT đã ghép đôi.
     * @SuppressLint: Permission đã được check ở UI layer trước khi gọi.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<DeviceInfo> {
        val adapter = bluetoothManager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return adapter.bondedDevices.map { device ->
            DeviceInfo(
                name    = device.name ?: "Unknown Device",
                address = device.address,
                type    = DeviceInfo.ConnectionType.BLUETOOTH
            )
        }
    }

    /**
     * Lấy BluetoothDevice gốc từ địa chỉ MAC.
     * Dùng để tạo BluetoothConnectionImpl.
     */
    @SuppressLint("MissingPermission")
    fun getDeviceByAddress(address: String) =
        bluetoothManager?.adapter?.bondedDevices
            ?.find { it.address == address }

    /** Kiểm tra Bluetooth có khả dụng và bật không */
    fun isBluetoothAvailable(): Boolean =
        bluetoothManager?.adapter?.isEnabled == true

    /**
     * Danh sách từ khoá nhận diện máy RTK phổ biến.
     * Bổ sung thêm nếu dùng thiết bị khác.
     */
    companion object {
        val RTK_DEVICE_KEYWORDS = listOf(
            "GNSS", "RTK", "T31", "T7", "F9P",
            "Reach", "EVO", "HiPer", "GR-i", "iRTK",
            "CHC", "South", "Trimble", "Leica", "Topcon",
            // STEC (SE10181720xxxx) — tên Bluetooth có thể là số series hoặc "STEC-..."
            "STEC", "SE1018", "SE101",
            // SinoGNSS / ComNav (T30, M6 Pro...) — họ máy đang dùng
            "Sino", "ComNav", "T30", "M6", "N5", "N6"
        )
    }
}
