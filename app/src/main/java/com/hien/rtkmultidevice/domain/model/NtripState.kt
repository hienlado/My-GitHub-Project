package com.hien.rtkmultidevice.domain.model

/**
 * NtripState — Trạng thái kết nối NTRIP.
 *
 * sealed class → exhaustive when() trong UI:
 *   khi viết when(ntripState) { ... } Kotlin ép phải
 *   xử lý TẤT CẢ các trường hợp → không bỏ sót.
 */
sealed class NtripState {

    /** Chưa kết nối NTRIP */
    data object Disconnected : NtripState()

    /** Đang kết nối */
    data object Connecting : NtripState()

    /**
     * Proxy server đang lắng nghe kết nối từ thiết bị GNSS.
     * @param phoneIp IP điện thoại trên WiFi thiết bị (ví dụ 192.168.1.10)
     * @param port    Port proxy đang lắng nghe (mặc định 2101)
     * @param gatewayIp IP gateway — địa chỉ web interface của thiết bị (ví dụ 192.168.1.1)
     */
    data class ProxyListening(
        val phoneIp  : String,
        val port     : Int,
        val gatewayIp: String = "192.168.1.1"
    ) : NtripState()

    /**
     * Đã kết nối và đang nhận RTCM.
     * @param mountPoint Điểm mount đang dùng
     * @param bytesReceived Tổng bytes RTCM đã nhận
     * @param packetsReceived Tổng số packet đã nhận
     */
    data class Connected(
        val mountPoint      : String,
        val bytesReceived   : Long = 0L,
        val packetsReceived : Int  = 0,
        val bytesForwarded  : Long = 0L,
        val packetsForwarded: Int  = 0,
        val ggaSentCount    : Int  = 0,
        val forwardError    : String? = null
    ) : NtripState() {

        /** Định dạng dung lượng đã nhận để hiển thị */
        val bytesFormatted: String
            get() = when {
                bytesReceived >= 1_048_576 -> "%.1f MB".format(bytesReceived / 1_048_576.0)
                bytesReceived >= 1_024     -> "%.1f KB".format(bytesReceived / 1_024.0)
                else                       -> "$bytesReceived B"
            }

        val forwardedFormatted: String
            get() = when {
                bytesForwarded >= 1_048_576 -> "%.1f MB".format(bytesForwarded / 1_048_576.0)
                bytesForwarded >= 1_024     -> "%.1f KB".format(bytesForwarded / 1_024.0)
                else                        -> "$bytesForwarded B"
            }
    }

    /** Kết nối thất bại */
    data class Error(val message: String) : NtripState()

    /** Nhãn trạng thái để hiển thị trên UI */
    val label: String
        get() = when (this) {
            is Disconnected -> "Chưa kết nối"
            is Connecting      -> "Đang kết nối..."
            is ProxyListening  -> "📡 Proxy đang chờ thiết bị — ${phoneIp}:${port}"
            is Connected    -> "Đã kết nối • $mountPoint"
            is Error        -> "Lỗi: $message"
        }
}
