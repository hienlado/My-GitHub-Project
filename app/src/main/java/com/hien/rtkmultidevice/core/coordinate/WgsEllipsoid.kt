package com.hien.rtkmultidevice.core.coordinate

import kotlin.math.sqrt

/**
 * WgsEllipsoid — Hằng số ellipsoid WGS-84.
 *
 * WGS-84 (World Geodetic System 1984) là hệ quy chiếu được dùng bởi GPS.
 * VN-2000 cũng dùng ellipsoid WGS-84 nhưng có datum khác (dịch chuyển gốc tọa độ).
 *
 * Nguồn: NIMA TR8350.2, Edition 3 (2000)
 */
object WgsEllipsoid {

    /** Bán trục lớn (semi-major axis), đơn vị mét */
    const val A = 6_378_137.0

    /** Độ dẹt (flattening): f = 1/298.257223563 */
    const val F = 1.0 / 298.257223563

    /** Bán trục nhỏ (semi-minor axis): b = a(1 - f) */
    val B: Double = A * (1.0 - F)

    /** Bình phương tâm sai thứ nhất: e² = 2f - f² */
    val E2: Double = 2.0 * F - F * F

    /** Bình phương tâm sai thứ hai: e'² = e²/(1-e²) */
    val E_PRIME2: Double = E2 / (1.0 - E2)

    /**
     * Bán kính cong mặt pháp tuyến đứng (prime vertical radius of curvature)
     * tại vĩ độ φ (radian).
     *
     * N(φ) = a / √(1 - e²·sin²φ)
     */
    fun primeVerticalRadius(sinPhi: Double): Double =
        A / sqrt(1.0 - E2 * sinPhi * sinPhi)
}
