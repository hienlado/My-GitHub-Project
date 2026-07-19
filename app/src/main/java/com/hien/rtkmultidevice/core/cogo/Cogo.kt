package com.hien.rtkmultidevice.core.cogo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cogo — lõi tính toán COGO trên mặt phẳng VN-2000 (toạ độ N=Northing, E=Easting, mét).
 *
 * Quy ước: phương vị (azimuth) tính từ hướng BẮC theo chiều kim đồng hồ, đơn vị độ [0,360).
 * Vector hướng của phương vị α trong hệ (N,E) là (cosα, sinα): +N là Bắc, +E là Đông.
 *
 * Thuần Kotlin — không phụ thuộc UI/Android, dễ kiểm thử. Dùng cho:
 *   - Nghịch đảo 2 điểm (khoảng cách + phương vị)
 *   - Điểm theo phương vị–khoảng cách
 *   - Diện tích + chu vi (đa giác)
 *   - Giao hội: phương vị–phương vị, phương vị–khoảng cách, khoảng cách–khoảng cách
 */
object Cogo {

    /** Điểm mặt phẳng VN-2000. n=Northing (X), e=Easting (Y). */
    data class Pt(val n: Double, val e: Double)

    private const val DEG = Math.PI / 180.0
    private const val RAD = 180.0 / Math.PI

    /** Chuẩn hoá góc về [0,360). */
    fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    // ── Nghịch đảo: 2 điểm -> khoảng cách + phương vị ─────────────────────────
    data class Inverse(
        val distance: Double,     // mét (ngang)
        val azimuthDeg: Double,   // phương vị p1->p2, [0,360)
        val dN: Double, val dE: Double
    )

    fun inverse(p1: Pt, p2: Pt): Inverse {
        val dN = p2.n - p1.n
        val dE = p2.e - p1.e
        val dist = hypot(dN, dE)
        val az = norm360(atan2(dE, dN) * RAD)   // atan2(E,N): từ Bắc theo chiều kim đồng hồ
        return Inverse(dist, az, dN, dE)
    }

    // ── Thuận: điểm theo phương vị + khoảng cách ─────────────────────────────
    fun pointByBearingDistance(from: Pt, azimuthDeg: Double, distance: Double): Pt {
        val a = azimuthDeg * DEG
        return Pt(from.n + distance * cos(a), from.e + distance * sin(a))
    }

    // ── Diện tích + chu vi (đa giác) ─────────────────────────────────────────
    data class AreaResult(val area: Double, val perimeter: Double, val vertexCount: Int)

    /**
     * Diện tích (shoelace, luôn dương) + chu vi (tự khép về đỉnh đầu).
     * Cần >= 3 đỉnh; nếu đỉnh cuối trùng đỉnh đầu sẽ tự bỏ để không đếm 2 lần.
     */
    fun areaPerimeter(ptsIn: List<Pt>): AreaResult {
        val pts = if (ptsIn.size >= 2 && ptsIn.first() == ptsIn.last())
            ptsIn.dropLast(1) else ptsIn
        val n = pts.size
        if (n < 3) return AreaResult(0.0, perimeterOpen(pts), n)
        var a2 = 0.0
        var per = 0.0
        for (i in 0 until n) {
            val p = pts[i]; val q = pts[(i + 1) % n]
            a2 += p.e * q.n - q.e * p.n   // shoelace theo (E,N)
            per += hypot(q.n - p.n, q.e - p.e)
        }
        return AreaResult(abs(a2) / 2.0, per, n)
    }

    private fun perimeterOpen(pts: List<Pt>): Double {
        var s = 0.0
        for (i in 0 until pts.size - 1) s += hypot(pts[i + 1].n - pts[i].n, pts[i + 1].e - pts[i].e)
        return s
    }

    // ── Giao hội: phương vị – phương vị ──────────────────────────────────────
    /** Trả điểm giao 2 tia/đường (theo phương vị) từ 2 điểm; null nếu song song. */
    fun intersectBearingBearing(p1: Pt, az1Deg: Double, p2: Pt, az2Deg: Double): Pt? {
        val a1 = az1Deg * DEG; val a2 = az2Deg * DEG
        val det = sin(a1 - a2)                 // = 0 khi song song
        if (abs(det) < 1e-12) return null
        val dN = p2.n - p1.n; val dE = p2.e - p1.e
        val t = (-dN * sin(a2) + dE * cos(a2)) / det
        return Pt(p1.n + t * cos(a1), p1.e + t * sin(a1))
    }

    // ── Giao hội: phương vị – khoảng cách ────────────────────────────────────
    /** Đường qua p1 theo phương vị az1 cắt đường tròn tâm p2 bán kính d2. 0..2 nghiệm. */
    fun intersectBearingDistance(p1: Pt, az1Deg: Double, p2: Pt, d2: Double): List<Pt> {
        val a = az1Deg * DEG
        val ux = cos(a); val uy = sin(a)                 // hướng theo (N,E)
        val fx = p1.n - p2.n; val fy = p1.e - p2.e
        // |p1 + t*u - p2|^2 = d2^2  ->  t^2 + 2(f·u)t + (f·f - d2^2) = 0  (|u|=1)
        val b = 2.0 * (fx * ux + fy * uy)
        val c = (fx * fx + fy * fy) - d2 * d2
        val disc = b * b - 4.0 * c
        if (disc < -1e-9) return emptyList()
        val sq = sqrt(if (disc < 0) 0.0 else disc)
        val ts = if (sq < 1e-9) listOf(-b / 2.0) else listOf((-b - sq) / 2.0, (-b + sq) / 2.0)
        return ts.map { t -> Pt(p1.n + t * ux, p1.e + t * uy) }
    }

    // ── Giao hội: khoảng cách – khoảng cách (2 đường tròn) ────────────────────
    /** Giao 2 đường tròn: tâm p1 bán kính d1, tâm p2 bán kính d2. 0..2 nghiệm. */
    fun intersectDistanceDistance(p1: Pt, d1: Double, p2: Pt, d2: Double): List<Pt> {
        val dN = p2.n - p1.n; val dE = p2.e - p1.e
        val dist = hypot(dN, dE)
        if (dist < 1e-9) return emptyList()                       // đồng tâm
        if (dist > d1 + d2 + 1e-9) return emptyList()             // rời nhau
        if (dist < abs(d1 - d2) - 1e-9) return emptyList()        // 1 tròn trong tròn kia
        val aLen = (d1 * d1 - d2 * d2 + dist * dist) / (2.0 * dist)
        val h2 = d1 * d1 - aLen * aLen
        val h = if (h2 < 0) 0.0 else sqrt(h2)
        // điểm chân trên đường nối tâm
        val mn = p1.n + aLen * dN / dist
        val me = p1.e + aLen * dE / dist
        if (h < 1e-9) return listOf(Pt(mn, me))
        // vuông góc với (dN,dE): (-dE,dN)/dist
        val on = -dE / dist * h
        val oe =  dN / dist * h
        return listOf(Pt(mn + on, me + oe), Pt(mn - on, me - oe))
    }

    // ── Tiện ích định dạng phương vị Độ-Phút-Giây ────────────────────────────
    fun azToDms(azDeg: Double): String {
        val a = norm360(azDeg)
        var d = a.toInt()
        var mFull = (a - d) * 60.0
        var m = mFull.toInt()
        var s = (mFull - m) * 60.0
        if (s >= 59.9995) { s = 0.0; m += 1 }
        if (m >= 60) { m = 0; d += 1 }
        return "%d°%02d'%05.2f\"".format(d, m, s)
    }
}
