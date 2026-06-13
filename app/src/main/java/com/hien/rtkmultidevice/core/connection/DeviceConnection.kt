package com.hien.rtkmultidevice.core.connection

import kotlinx.coroutines.flow.Flow

/**
 * Interface chung cho mọi loại kết nối tới thiết bị GNSS.
 *
 * Tại sao dùng Interface?
 *   → Cho phép hoán đổi linh hoạt giữa Bluetooth, TCP, USB
 *     mà không thay đổi code ở tầng trên.
 *   → Giống như "ổ cắm điện chuẩn" — thiết bị nào cắm vào
 *     cũng hoạt động miễn đúng tiêu chuẩn.
 */
interface DeviceConnection {

    /**
     * Thực hiện kết nối tới thiết bị.
     * Trả về [Result.success] nếu kết nối thành công,
     * [Result.failure] kèm Exception nếu thất bại.
     *
     * Hàm suspend → chạy bất đồng bộ, không block UI thread.
     */
    suspend fun connect(): Result<Unit>

    /**
     * Ngắt kết nối và giải phóng tài nguyên.
     */
    fun disconnect()

    /**
     * Flow phát ra từng dòng NMEA nhận được từ thiết bị.
     *
     * Flow là luồng dữ liệu liên tục (giống máy bơm nước):
     *   emit → dòng NMEA mới → ViewModel thu thập → cập nhật UI
     */
    fun nmeaFlow(): Flow<String>

    /**
     * Gửi dữ liệu (bytes) đến thiết bị — dùng để truyền RTCM.
     */
    suspend fun sendBytes(data: ByteArray)
}

/**
 * Trạng thái kết nối — sealed class đảm bảo chỉ có những
 * trạng thái được định nghĩa ở đây mới tồn tại.
 *
 * Tương tự "trạng thái đo RTK": chưa kết nối → đang kết nối
 * → đã kết nối → lỗi.
 */
sealed class ConnectionState {

    /** Chưa kết nối */
    data object Disconnected : ConnectionState()

    /** Đang thực hiện kết nối */
    data object Connecting : ConnectionState()

    /** Đã kết nối thành công */
    data class Connected(
        val deviceName: String,
        val deviceAddress: String
    ) : ConnectionState()

    /** Kết nối thất bại */
    data class Error(val message: String) : ConnectionState()
}
