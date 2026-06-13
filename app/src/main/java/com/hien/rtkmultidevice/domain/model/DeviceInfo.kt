package com.hien.rtkmultidevice.domain.model

/**
 * DeviceInfo — Thông tin thiết bị GNSS có thể kết nối.
 *
 * Dùng cho màn hình ConnectionScreen: hiển thị danh sách
 * thiết bị BT và TCP để người dùng chọn.
 */
data class DeviceInfo(

    /** Tên thiết bị (VD: "T31_GNSS", "CHC i90") */
    val name: String,

    /**
     * Địa chỉ:
     *   Bluetooth → MAC address (VD: "AA:BB:CC:DD:EE:FF")
     *   TCP       → "host:port" (VD: "192.168.1.100:2000")
     */
    val address: String,

    /** Loại kết nối */
    val type: ConnectionType,

    /** Đã từng kết nối thành công chưa (từ lịch sử) */
    val isRecentlyUsed: Boolean = false
) {
    enum class ConnectionType {
        BLUETOOTH,
        TCP_WIFI
    }

    /** Icon tương ứng với loại kết nối (tên Material Icon) */
    val iconName: String
        get() = when (type) {
            ConnectionType.BLUETOOTH -> "bluetooth"
            ConnectionType.TCP_WIFI  -> "wifi"
        }

    /** Nhãn loại kết nối để hiển thị */
    val typeLabel: String
        get() = when (type) {
            ConnectionType.BLUETOOTH -> "Bluetooth"
            ConnectionType.TCP_WIFI  -> "WiFi / TCP"
        }
}
