package com.hien.rtkmultidevice.core.gnss.nmea

/**
 * GgaData — Dữ liệu từ câu NMEA $GNGGA / $GPGGA.
 *
 * GGA = Global Positioning System Fix Data
 * Đây là câu NMEA quan trọng nhất cho RTK vì chứa:
 *   - Toạ độ lat/lon
 *   - Chất lượng fix (fixQuality)
 *   - Số vệ tinh, HDOP, độ cao
 *
 * data class → Kotlin tự sinh equals(), hashCode(), copy(), toString()
 */
data class GgaData(

    /** Vĩ độ (độ thập phân, S âm) */
    val latitude: Double,

    /** Kinh độ (độ thập phân, W âm) */
    val longitude: Double,

    /** Độ cao ellipsoid (mét) */
    val altitude: Double,

    /** Số vệ tinh đang theo dõi */
    val satelliteCount: Int,

    /**
     * Chất lượng fix:
     *   0 = Invalid
     *   1 = GPS / SINGLE
     *   2 = DGPS
     *   4 = RTK Fixed   ← Độ chính xác cm
     *   5 = RTK Float   ← Độ chính xác dm
     */
    val fixQuality: Int,

    /** HDOP — Hệ số pha loãng độ chính xác ngang (càng nhỏ càng tốt) */
    val hdop: Double,

    /** Thời gian UTC từ header câu GGA (hhmmss.ss) */
    val utcTime: String = "",

    /** Undulation — chênh lệch geoid/ellipsoid (mét) */
    val geoidSeparation: Double = 0.0
) {
    /**
     * Trạng thái fix dạng chuỗi để hiển thị UI.
     */
    val fixStatus: String
        get() = when (fixQuality) {
            4    -> "RTK FIXED"
            5    -> "RTK FLOAT"
            2    -> "DGPS"
            1    -> "SINGLE"
            else -> "NO FIX"
        }

    /**
     * Màu sắc fix (dạng ARGB hex string) — dùng trong Compose.
     * Chuẩn màu ngành trắc địa:
     *   FIXED = xanh lá, FLOAT = vàng, DGPS = cam, SINGLE = đỏ
     */
    val fixColorHex: String
        get() = when (fixQuality) {
            4    -> "#FF4CAF50"  // Xanh lá
            5    -> "#FFFFEB3B"  // Vàng
            2    -> "#FFFF9800"  // Cam
            1    -> "#FFF44336"  // Đỏ
            else -> "#FF9E9E9E"  // Xám
        }
}
