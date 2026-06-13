package com.hien.rtkmultidevice.core.coordinate

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * GaussKruger — Chiếu hình Transverse Mercator (Gauss-Krüger).
 *
 * Áp dụng cho hệ VN-2000 theo tiêu chuẩn Việt Nam:
 *   - Múi 3°: k₀ = 0.9999, FE = 500 000 m, FN = 0 m
 *   - Múi 6°: k₀ = 0.9999, FE = 500 000 m, FN = 0 m
 *
 * Sử dụng chuỗi Redfearn (series expansion) — độ chính xác mm
 * trong phạm vi ±3° kinh độ từ kinh tuyến trục.
 *
 * Tài liệu tham khảo:
 *   - Redfearn (1948) "Transverse Mercator formulae"
 *   - ICSM (2020) "GDA2020 Technical Manual"
 */
object GaussKruger {

    // ── Hằng số ellipsoid WGS-84 ────────────────────────────
    private val a   = WgsEllipsoid.A
    private val e2  = WgsEllipsoid.E2
    private val ep2 = WgsEllipsoid.E_PRIME2

    // ── Hệ số cung kinh tuyến ────────────────────────────────
    // M = a(A0·φ − A2·sin2φ + A4·sin4φ − A6·sin6φ)
    private val cA0 = 1.0 - e2/4.0 - 3.0*e2*e2/64.0 - 5.0*e2*e2*e2/256.0
    private val cA2 = 3.0/8.0  * (e2 + e2*e2/4.0 + 15.0*e2*e2*e2/128.0)
    private val cA4 = 15.0/256.0 * (e2*e2 + 3.0*e2*e2*e2/4.0)
    private val cA6 = 35.0*e2*e2*e2/3072.0

    // ────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────

    /**
     * Chuyển đổi toạ độ địa lý → lưới chiếu.
     *
     * @param latDeg        Vĩ độ (độ thập phân)
     * @param lonDeg        Kinh độ (độ thập phân)
     * @param centralMerDeg Kinh tuyến trục (độ thập phân)
     * @param k0            Hệ số tỉ lệ (0.9999 cho VN-2000)
     * @param falseEasting  Dịch chuyển Đông (500 000 m)
     * @param falseNorthing Dịch chuyển Bắc (0 m)
     * @return Pair(northing, easting) đơn vị mét
     */
    fun project(
        latDeg        : Double,
        lonDeg        : Double,
        centralMerDeg : Double,
        k0            : Double = 0.9999,
        falseEasting  : Double = 500_000.0,
        falseNorthing : Double = 0.0
    ): Pair<Double, Double> {

        val phi    = Math.toRadians(latDeg)
        val lambda = Math.toRadians(lonDeg)
        val lam0   = Math.toRadians(centralMerDeg)

        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)

        val N  = a / sqrt(1.0 - e2 * sinPhi * sinPhi)   // bán kính cong
        val T  = tanPhi * tanPhi                          // tan²φ
        val C  = ep2 * cosPhi * cosPhi                    // e'²cos²φ
        val A  = cosPhi * (lambda - lam0)                 // cung kinh độ
        val M  = meridionalArc(phi)

        // ── Easting ──────────────────────────────────────────
        val x = k0 * N * (
            A
            + (1.0 - T + C)          * A.pow(3) / 6.0
            + (5.0 - 18.0*T + T*T
               + 72.0*C - 58.0*ep2)  * A.pow(5) / 120.0
        )

        // ── Northing ─────────────────────────────────────────
        val y = k0 * (
            M
            + N * tanPhi * (
                A.pow(2) / 2.0
                + (5.0 - T + 9.0*C + 4.0*C*C)                   * A.pow(4) / 24.0
                + (61.0 - 58.0*T + T*T + 600.0*C - 330.0*ep2)   * A.pow(6) / 720.0
            )
        )

        return Pair(
            falseNorthing + y,          // N (Northing)
            falseEasting  + x           // E (Easting)
        )
    }

    // ────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────

    /**
     * Cung kinh tuyến từ xích đạo đến vĩ độ φ (radian).
     * M(φ) = a [A0·φ − A2·sin2φ + A4·sin4φ − A6·sin6φ]
     */
    private fun meridionalArc(phi: Double): Double {
        return a * (
            cA0 * phi
            - cA2 * sin(2.0 * phi)
            + cA4 * sin(4.0 * phi)
            - cA6 * sin(6.0 * phi)
        )
    }
}
