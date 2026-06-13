package com.hien.rtkmultidevice.domain.model

/**
 * SatelliteInfo — Thông tin một vệ tinh trong tầm nhìn.
 *
 * Dữ liệu lấy từ câu NMEA GSV (Satellites in View).
 * Dùng để vẽ biểu đồ cột tín hiệu (signal bar chart).
 *
 * Hệ thống vệ tinh (Constellation):
 *   GPS     → Mỹ      (PRN 1–32,   prefix GP)
 *   GLONASS → Nga     (PRN 65–96,  prefix GL)
 *   Galileo → EU      (PRN 1–36,   prefix GA)
 *   BeiDou  → TQ      (PRN 1–63,   prefix GB)
 *   QZSS    → Nhật    (PRN 193–202, prefix GQ)
 */
data class SatelliteInfo(

    /** Số hiệu vệ tinh (PRN — Pseudo-Random Number) */
    val prn: Int,

    /** Góc ngẩng (elevation angle) — 0° = chân trời, 90° = đỉnh đầu */
    val elevation: Int,

    /** Góc phương vị (azimuth) — 0°–360° từ Bắc theo chiều kim đồng hồ */
    val azimuth: Int,

    /**
     * Tỷ số tín hiệu/nhiễu (SNR / C/N0) — đơn vị dBHz.
     *   0      = không có tín hiệu
     *   1–20   = tín hiệu yếu
     *   21–35  = tín hiệu trung bình
     *   36–45  = tín hiệu tốt
     *   > 45   = tín hiệu rất tốt (lý tưởng cho RTK)
     */
    val snr: Int,

    /** Hệ thống vệ tinh */
    val constellation: Constellation,

    /** True nếu vệ tinh này đang được dùng để tính vị trí (từ GSA) */
    val isUsed: Boolean = false
) {
    enum class Constellation(
        val label      : String,
        val colorHex   : String   // ARGB hex cho biểu đồ
    ) {
        GPS     ("GPS",     "#FF2196F3"),  // Xanh dương
        GLONASS ("GLONASS", "#FFF44336"),  // Đỏ
        GALILEO ("Galileo", "#FF4CAF50"),  // Xanh lá
        BEIDOU  ("BeiDou",  "#FFFF9800"),  // Cam
        QZSS    ("QZSS",    "#FF9C27B0"),  // Tím
        SBAS    ("SBAS",    "#FF607D8B"),  // Xanh xám
        UNKNOWN ("???",     "#FF9E9E9E")   // Xám
    }

    /** Mức tín hiệu 0–4 để hiển thị icon */
    val signalLevel: Int
        get() = when {
            snr <= 0  -> 0
            snr <= 20 -> 1
            snr <= 30 -> 2
            snr <= 40 -> 3
            else      -> 4
        }

    /** Chiều cao cột tương đối (0.0–1.0) cho biểu đồ */
    val normalizedSnr: Float
        get() = (snr.coerceIn(0, 50) / 50f)
}
