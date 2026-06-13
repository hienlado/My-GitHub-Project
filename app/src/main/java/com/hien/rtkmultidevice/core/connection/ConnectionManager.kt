package com.hien.rtkmultidevice.core.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ConnectionManager — Singleton quản lý kết nối đang hoạt động.
 *
 * Tại sao cần class này?
 *   → Nhiều ViewModel (ConnectionViewModel, GnssViewModel) đều
 *     cần biết "thiết bị nào đang kết nối". Thay vì truyền
 *     qua navigation arguments, ta dùng một Singleton được Hilt
 *     inject vào tất cả nơi cần.
 *   → Giống như "bảng điều khiển trung tâm" của thiết bị đo:
 *     mọi người đều nhìn lên cùng một màn hình trạng thái.
 *
 * @Singleton → Hilt đảm bảo chỉ có MỘT instance trong suốt
 *             vòng đời ứng dụng.
 */
@Singleton
class ConnectionManager @Inject constructor() {

    // ── Trạng thái kết nối ──────────────────────────────────
    private val _connectionState = MutableStateFlow<ConnectionState>(
        ConnectionState.Disconnected
    )

    /**
     * StateFlow để các ViewModel observe (theo dõi).
     * StateFlow luôn giữ giá trị hiện tại → khi subscribe
     * lần đầu sẽ nhận ngay giá trị mới nhất.
     */
    val connectionState: StateFlow<ConnectionState> =
        _connectionState.asStateFlow()

    // ── Kết nối đang hoạt động ──────────────────────────────
    private var _activeConnection: DeviceConnection? = null

    /**
     * Cập nhật kết nối mới sau khi connect() thành công.
     * Gọi từ ConnectionViewModel sau khi user chọn thiết bị.
     */
    fun setConnection(
        connection: DeviceConnection,
        state: ConnectionState.Connected
    ) {
        _activeConnection?.disconnect() // Đóng kết nối cũ nếu có
        _activeConnection = connection
        _connectionState.value = state
    }

    /**
     * Lấy kết nối đang hoạt động để đọc NMEA / gửi RTCM.
     * Trả về null nếu chưa kết nối.
     */
    fun getActiveConnection(): DeviceConnection? = _activeConnection

    /**
     * Ngắt kết nối và reset về Disconnected.
     */
    fun disconnect() {
        _activeConnection?.disconnect()
        _activeConnection = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Cập nhật trạng thái từ bên ngoài (VD: khi đang kết nối) */
    fun updateState(state: ConnectionState) {
        _connectionState.value = state
    }
}
