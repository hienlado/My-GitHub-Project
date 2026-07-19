package com.hien.rtkmultidevice.core.coordinate

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.pow

/**
 * HelmertTransform — Chuyển đổi 7 thông số Bursa-Wolf từ WGS-84 sang VN-2000.
 *
 * Mô hình Bursa-Wolf. Ma trận burstWolf() theo dạng coordinate-frame:
 *   [X']     [1+m    Rz   -Ry] [X]   [ΔX0]
 *   [Y']  =  [-Rz   1+m    Rx] [Y] + [ΔY0]
 *   [Z']     [Ry   -Rx   1+m] [Z]   [ΔZ0]
 *
 * LƯU Ý QUY ƯỚC (sửa 2026): để phép này là NGHỊCH ĐẢO ĐÚNG của VN-2000→WGS-84
 * (ProjNet/TOWGS84, position-vector) đã kiểm chứng, trong code RY và RZ được ĐẢO DẤU
 * so với bảng tham số hiển thị bên dưới (RX giữ nguyên). Sai quy ước trước đây gây lệch
 * ~0,35 m Bắc / ~0,5 m Đông; sau sửa sai số khứ hồi = 0.
 *
 * Các bước thực hiện:
 *   1. WGS-84 φ,λ,h  →  Geocentric XYZ (WGS-84)
 *   2. Bursa-Wolf:   XYZ_WGS84  →  XYZ_VN2000
 *   3. XYZ_VN2000   →  VN-2000 φ',λ',h'
 *
 * Thông số WGS-84 → VN-2000 chính thức (QCVN 04:2009/BTNMT):
 *   ΔX0 = +191.90441429 m    ΔY0 = +39.30318279 m    ΔZ0 = +111.45032835 m
 *   ω0  = +0.00928836"       ψ0  = −0.01975479"       ε0  = +0.00427372"
 *   k   = −0.252906278 ppm   →  m = 1 + k×10⁻⁶ = 1 − 0.252906278×10⁻⁶
 */
object HelmertTransform {

    // ── Thông số chuyển đổi WGS-84 → VN-2000 (QCVN 04:2009) ─
    private const val DX = 191.90441429   // mét (dương)
    private const val DY =  39.30318279   // mét (dương)
    private const val DZ = 111.45032835   // mét (dương)

    // Góc xoay (arc-second → radian).
    // SỬA (2026): ma trận burstWolf() dưới đây theo quy ước coordinate-frame; để KHỚP đúng
    // nghịch đảo của phép VN-2000→WGS-84 (ProjNet/TOWGS84, position-vector) đã kiểm chứng,
    // RY và RZ phải ĐẢO DẤU so với bảng tham số hiển thị. Trước đây sai quy ước gây lệch
    // ~0,35 m Bắc / ~0,5 m Đông tại mốc. Sau sửa: sai số khứ hồi = 0.
    private const val ARCSEC_TO_RAD = PI / (180.0 * 3600.0)
    // HOÀN NGUYÊN (2026): mốc thật cho thấy bộ dấu gốc gần hơn -> khôi phục RY, RZ như ban đầu.
    // Cần toạ độ mốc (WGS-84 + VN-2000 chính thức) để hiệu chỉnh chính xác thay vì đoán quy ước.
    private val RX =  0.00928836 * ARCSEC_TO_RAD   // ω0
    private val RY = -0.01975479 * ARCSEC_TO_RAD   // ψ0
    private val RZ =  0.00427372 * ARCSEC_TO_RAD   // ε0

    // Hệ số tỉ lệ: k = −0.252906278 ppm → M_PPM = k×10⁻⁶ (âm)
    private const val M_PPM = -0.252906278e-6

    // ────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────

    /**
     * Chuyển đổi toạ độ WGS-84 (φ, λ, h) → VN-2000 (φ', λ', h').
     *
     * @param latWgs  Vĩ độ WGS-84 (độ thập phân, dương = Bắc)
     * @param lonWgs  Kinh độ WGS-84 (độ thập phân, dương = Đông)
     * @param hWgs    Độ cao ellipsoid WGS-84 (mét)
     * @return Triple(latVn2000°, lonVn2000°, hVn2000m)
     */
    fun wgs84ToVn2000(
        latWgs : Double,
        lonWgs : Double,
        hWgs   : Double = 0.0
    ): Triple<Double, Double, Double> {
        // Bước 1: Geographic → Geocentric Cartesian (WGS-84)
        val (x1, y1, z1) = geographicToCartesian(
            latDeg = latWgs,
            lonDeg = lonWgs,
            h      = hWgs
        )

        // Bước 2: Bursa-Wolf transformation
        val (x2, y2, z2) = burstWolf(x1, y1, z1)

        // Bước 3: Geocentric Cartesian → Geographic (VN-2000 = WGS-84 ellipsoid)
        return cartesianToGeographic(x2, y2, z2)
    }

    // ────────────────────────────────────────────────────────
    // Private: Bước 1 — Geographic → Cartesian
    // ────────────────────────────────────────────────────────

    private fun geographicToCartesian(
        latDeg : Double,
        lonDeg : Double,
        h      : Double
    ): Triple<Double, Double, Double> {
        val phi    = Math.toRadians(latDeg)
        val lambda = Math.toRadians(lonDeg)

        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val sinLam = sin(lambda)
        val cosLam = cos(lambda)

        val N = WgsEllipsoid.primeVerticalRadius(sinPhi)

        val x = (N + h) * cosPhi * cosLam
        val y = (N + h) * cosPhi * sinLam
        val z = (N * (1.0 - WgsEllipsoid.E2) + h) * sinPhi

        return Triple(x, y, z)
    }

    // ────────────────────────────────────────────────────────
    // Private: Bước 2 — Bursa-Wolf 7-parameter
    // ────────────────────────────────────────────────────────

    private fun burstWolf(
        x : Double,
        y : Double,
        z : Double
    ): Triple<Double, Double, Double> {
        val m = M_PPM          // scale delta (không phải 1 + m)
        val xp = (1.0 + m) * x  + RZ * y       - RY * z  + DX
        val yp = -RZ        * x + (1.0 + m) * y + RX * z  + DY
        val zp =  RY        * x  - RX * y       + (1.0 + m) * z + DZ
        return Triple(xp, yp, zp)
    }

    // ────────────────────────────────────────────────────────
    // Private: Bước 3 — Cartesian → Geographic (iterative Bowring)
    // ────────────────────────────────────────────────────────

    private fun cartesianToGeographic(
        x : Double,
        y : Double,
        z : Double
    ): Triple<Double, Double, Double> {
        val a  = WgsEllipsoid.A
        val b  = WgsEllipsoid.B
        val e2 = WgsEllipsoid.E2

        val p      = sqrt(x * x + y * y)
        val lambda = atan2(y, x)

        // Bowring iterative (hội tụ nhanh sau 3-4 vòng)
        var phi = atan2(z, p * (1.0 - e2))
        repeat(10) {
            val sinPhi = sin(phi)
            val N      = WgsEllipsoid.primeVerticalRadius(sinPhi)
            phi = atan2(z + e2 * N * sinPhi, p)
        }

        val sinPhi = sin(phi)
        val N      = WgsEllipsoid.primeVerticalRadius(sinPhi)
        val h      = p / cos(phi) - N

        return Triple(
            Math.toDegrees(phi),
            Math.toDegrees(lambda),
            h
        )
    }
}
