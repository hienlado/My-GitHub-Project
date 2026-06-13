package com.hien.rtkmultidevice.core.gnss.nmea

/**
 * RmcData — Dữ liệu câu $GNRMC (Recommended Minimum Specific GNSS Data).
 *
 * Đây là câu NMEA "tối thiểu" chứa: thời gian, vị trí, vận tốc, hướng.
 * Thường dùng để ghi thời gian đo và vận tốc di chuyển.
 *
 * Ví dụ:
 *   $GNRMC,025147.00,A,1050.12345,N,10610.56789,E,0.012,45.3,250526,,,A*68
 *   [1]=time [2]=A/V [3..6]=lat/lon [7]=knots [8]=course [9]=date
 */
data class RmcData(

    /** Thời gian UTC dạng "hhmmss.ss" */
    val utcTime: String,

    /**
     * Trạng thái:
     *   A = Active (dữ liệu hợp lệ)
     *   V = Void   (dữ liệu không hợp lệ)
     */
    val status: Char,

    /** Vĩ độ (độ thập phân) */
    val latitude: Double,

    /** Kinh độ (độ thập phân) */
    val longitude: Double,

    /** Vận tốc (hải lý/giờ — knots) */
    val speedKnots: Double,

    /** Hướng di chuyển thực (True Course) 0°–360° */
    val courseTrue: Double,

    /**
     * Ngày dạng "DDMMYY"
     * VD: "250526" = 25/05/2026
     */
    val date: String
) {
    /** True nếu dữ liệu hợp lệ */
    val isValid: Boolean get() = status == 'A'

    /** Vận tốc chuyển đổi sang km/h */
    val speedKmh: Double get() = speedKnots * 1.852

    /** Ngày định dạng DD/MM/YY để hiển thị */
    val dateFormatted: String
        get() = if (date.length == 6)
            "${date.substring(0,2)}/${date.substring(2,4)}/20${date.substring(4,6)}"
        else date
}
