package com.hien.rtkmultidevice.core.gnss.nmea

/**
 * VtgData — Dữ liệu câu $GNVTG (Course Over Ground and Ground Speed).
 *
 * Cung cấp vận tốc và hướng di chuyển chính xác hơn RMC,
 * bao gồm cả hướng từ (magnetic course).
 *
 * Ví dụ:
 *   $GNVTG,45.30,T,46.80,M,0.012,N,0.022,K,A*2F
 *   [1]=courseTrue [3]=courseMag [5]=knots [7]=kmh [9]=mode
 */
data class VtgData(

    /** Hướng di chuyển thực (True North) 0°–360° */
    val courseTrue: Double,

    /** Hướng di chuyển từ (Magnetic North) 0°–360° */
    val courseMagnetic: Double,

    /** Vận tốc (hải lý/giờ) */
    val speedKnots: Double,

    /** Vận tốc (km/h) */
    val speedKmh: Double,

    /**
     * Chế độ:
     *   A = Autonomous
     *   D = Differential
     *   E = Estimated
     *   N = Not valid
     */
    val mode: Char = 'A'
) {
    /** True nếu đang thực sự di chuyển (> 0.5 km/h) */
    val isMoving: Boolean get() = speedKmh > 0.5

    /** Hướng la bàn 8 điểm từ courseTrue */
    val compassPoint: String
        get() {
            val dirs = listOf("N","NE","E","SE","S","SW","W","NW","N")
            val idx  = ((courseTrue + 22.5) / 45.0).toInt() % 8
            return dirs[idx]
        }
}
