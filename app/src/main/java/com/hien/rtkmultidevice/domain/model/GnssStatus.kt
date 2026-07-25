package com.hien.rtkmultidevice.domain.model

import com.hien.rtkmultidevice.core.gnss.nmea.GgaData
import com.hien.rtkmultidevice.domain.model.Vn2000Coordinate

/**
 * GnssStatus — Trạng thái GNSS tổng hợp để hiển thị trên UI.
 *
 * Kết hợp thông tin từ nhiều câu NMEA (GGA + GSA + RMC + VTG + GSV)
 * thành một object đơn giản cho ViewModel sử dụng.
 *
 * Dùng data class với giá trị mặc định → dễ copy/update từng trường:
 *   status.copy(pdop = 1.2, speedKmh = 3.5)
 */
data class GnssStatus(

    // ── Vị trí (từ GGA) ──────────────────────────────────────
    /** Vĩ độ (WGS-84, độ thập phân) */
    val latitude: Double = 0.0,

    /** Kinh độ (WGS-84, độ thập phân) */
    val longitude: Double = 0.0,

    /** Độ cao ellipsoid (mét) */
    val altitude: Double = 0.0,

    /** Chất lượng fix (0=NoFix,1=Single,2=DGPS,4=RTK Fixed,5=RTK Float) */
    val fixQuality: Int = 0,

    /** Số vệ tinh đang được dùng tính vị trí */
    val satelliteCount: Int = 0,

    /** HDOP — Horizontal Dilution of Precision */
    val hdop: Double = 0.0,

    /** PDOP — Position Dilution of Precision (từ GSA) */
    val pdop: Double = 0.0,

    /** VDOP — Vertical Dilution of Precision (từ GSA) */
    val vdop: Double = 0.0,

    // ── Độ chính xác thực tế (từ GST) ────────────────────────
    /**
     * H — sai số MẶT BẰNG (mét) do máy thu ước lượng.
     * 0.0 = máy chưa phát câu GST.
     */
    val hAccuracy: Double = 0.0,

    /**
     * V — sai số ĐỘ CAO (mét) do máy thu ước lượng.
     * 0.0 = máy chưa phát câu GST.
     */
    val vAccuracy: Double = 0.0,

    /** Dung lượng pin máy thu (%). -1 = chưa đọc được (chưa nối WiFi máy / dùng Bluetooth). */
    val batteryPercent: Int = -1,

    /** Thời gian UTC định dạng hh:mm:ss */
    val utcTime: String = "--:--:--",

    /** Undulation geoid (mét) — hiệu chỉnh độ cao */
    val geoidSeparation: Double = 0.0,

    // ── Chuyển động (từ RMC / VTG) ───────────────────────────
    /** Vận tốc di chuyển (km/h) */
    val speedKmh: Double = 0.0,

    /** Hướng di chuyển thực (True Course) 0°–360° */
    val courseTrue: Double = 0.0,

    /**
     * Ngày đo định dạng "DD/MM/YYYY"
     * (chuyển đổi từ DDMMYY trong câu RMC)
     */
    val date: String = "--/--/----",

    // ── Vệ tinh (từ GSV) ─────────────────────────────────────
    /**
     * Danh sách tất cả vệ tinh nhìn thấy, từ tất cả constellation.
     * Được cập nhật sau mỗi chu kỳ GSV hoàn chỉnh.
     */
    val satellites: List<SatelliteInfo> = emptyList(),

    // ── VN-2000 (từ Helmert + Gauss-Krüger) ──────────────────
    /**
     * Toạ độ VN-2000 sau khi chuyển đổi.
     * Null nếu chưa có fix hoặc chưa tính xong.
     */
    val vn2000: Vn2000Coordinate? = null

) {
    // ── Computed properties ───────────────────────────────────

    /** Nhãn chất lượng fix */
    val fixLabel: String
        get() = when (fixQuality) {
            4    -> "RTK FIXED"
            5    -> "RTK FLOAT"
            2    -> "DGPS"
            1    -> "SINGLE"
            else -> "NO FIX"
        }

    /** Màu hex ARGB cho banner fix */
    val fixColorHex: String
        get() = when (fixQuality) {
            4    -> "#FF1B5E20"   // Xanh đậm — RTK Fixed
            5    -> "#FF33691E"   // Xanh nhạt — RTK Float
            2    -> "#FF1565C0"   // Xanh dương — DGPS
            1    -> "#FFF57F17"   // Vàng — Single
            else -> "#FFB71C1C"   // Đỏ — No Fix
        }

    /** True nếu đang có fix (bất kỳ loại nào) */
    val hasFix: Boolean get() = fixQuality > 0

    /** True nếu đang ở chế độ RTK (Fixed hoặc Float) */
    val isRtk: Boolean get() = fixQuality == 4 || fixQuality == 5

    /** True nếu đang thực sự di chuyển (> 0.5 km/h) */
    val isMoving: Boolean get() = speedKmh > 0.5

    /** Tổng số vệ tinh nhìn thấy (có thể nhiều hơn satelliteCount đang dùng) */
    val satellitesInView: Int get() = satellites.size

    // ── H / V hiển thị trên thanh trạng thái ─────────────────
    /**
     * Sai số mặt bằng H (m) để HIỂN THỊ.
     * Ưu tiên số thật từ câu GST; nếu máy không phát GST thì ƯỚC LƯỢNG
     * từ HDOP × sai số đo khoảng cách điển hình theo loại fix.
     */
    val hDisplay: Double
        get() = if (hAccuracy > 0.0) hAccuracy else hdop * baseSigma

    /** Sai số độ cao V (m) để hiển thị. Độ cao luôn kém hơn mặt bằng (~1.5–2 lần). */
    val vDisplay: Double
        get() = when {
            vAccuracy > 0.0 -> vAccuracy
            vdop > 0.0      -> vdop * baseSigma
            else            -> hDisplay * 1.8
        }

    /** true nếu H/V là số thật từ máy (GST), false nếu chỉ là ước lượng từ DOP. */
    val accuracyIsMeasured: Boolean get() = hAccuracy > 0.0

    /** Sai số đo khoảng cách điển hình (m) theo loại lời giải — dùng khi ước lượng. */
    private val baseSigma: Double
        get() = when (fixQuality) {
            4    -> 0.010   // RTK Fixed  ~1 cm
            5    -> 0.30    // RTK Float  ~30 cm
            2    -> 1.0     // DGPS       ~1 m
            1    -> 2.5     // Single     ~2.5 m
            else -> 0.0
        }

    /**
     * Chuỗi hiển thị gọn, VD "H 0.012  V 0.021" (mét, tới mm).
     * Có dấu ~ nghĩa là ước lượng từ DOP (máy chưa bật câu GST).
     */
    /** Có đọc được pin máy thu không. */
    val hasBattery: Boolean get() = batteryPercent in 0..100

    /** Pin yếu — cảnh báo trước khi ra thực địa xa. */
    val batteryLow: Boolean get() = batteryPercent in 0..19

    val accuracyText: String
        get() {
            if (!hasFix) return "H --  V --"
            val e = if (accuracyIsMeasured) "" else "~"
            return "H $e%.3f  V $e%.3f".format(hDisplay, vDisplay)
        }

    /** Số vệ tinh đang dùng theo từng constellation */
    val usedSatelliteCount: Int get() = satellites.count { it.isUsed }

    companion object {
        /** Tạo GnssStatus từ GgaData đã parse */
        fun fromGga(gga: GgaData) = GnssStatus(
            latitude        = gga.latitude,
            longitude       = gga.longitude,
            altitude        = gga.altitude,
            fixQuality      = gga.fixQuality,
            satelliteCount  = gga.satelliteCount,
            hdop            = gga.hdop,
            utcTime         = formatTime(gga.utcTime),
            geoidSeparation = gga.geoidSeparation
        )

        /** Trạng thái mặc định — chưa có tín hiệu */
        val NoFix = GnssStatus()

        /** Định dạng thời gian hhmmss.ss → hh:mm:ss (vẫn là UTC) */
        fun formatTime(raw: String): String {
            if (raw.length < 6) return "--:--:--"
            return "${raw.substring(0, 2)}:${raw.substring(2, 4)}:${raw.substring(4, 6)}"
        }
    }

    // ── Thời gian UTC+7 (Hà Nội / Bangkok) ───────────────────

    /**
     * Thời gian GPS hiển thị theo múi giờ UTC+7 (Asia/Ho_Chi_Minh).
     * `utcTime` từ GGA là UTC thuần — cộng thêm 7 tiếng để hiển thị.
     */
    val localTime: String
        get() {
            if (utcTime == "--:--:--") return "--:--:--"
            return try {
                val h    = utcTime.substring(0, 2).toInt()
                val rest = utcTime.substring(2)          // ":MM:SS"
                "%02d%s".format((h + 7) % 24, rest)
            } catch (_: Exception) { utcTime }
        }

}
