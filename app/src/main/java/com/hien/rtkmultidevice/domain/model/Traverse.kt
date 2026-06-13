package com.hien.rtkmultidevice.domain.model

import kotlin.math.*

/**
 * Traverse — Domain model của tuyến đo (polyline).
 *
 * Tuyến đo là tập hợp điểm đo liên tiếp tạo thành đường gấp khúc.
 * Ứng dụng: đo ranh thửa đất, đường tim tuyến, vùng rừng...
 */
data class Traverse(
    val id          : Int    = 0,
    val projectId   : Int,
    val name        : String,
    val description : String = "",
    val isClosed    : Boolean = false,
    val points      : List<TraversePoint> = emptyList(),
    val createdAt   : Long = System.currentTimeMillis(),
    val updatedAt   : Long = System.currentTimeMillis()
) {
    /** Số điểm trong tuyến */
    val pointCount: Int get() = points.size

    /** Tổng chiều dài tuyến (mét, tính từ VN-2000 nếu có, WGS-84 nếu không) */
    val totalLengthM: Double get() {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += points[i].distanceTo(points[i + 1])
        }
        // Nếu tuyến đóng, cộng thêm đoạn cuối về đầu
        if (isClosed && points.size >= 3) {
            total += points.last().distanceTo(points.first())
        }
        return total
    }

    /** Format tổng chiều dài */
    val totalLengthFormatted: String get() = when {
        totalLengthM < 1.0   -> "${"%.3f".format(totalLengthM)}m"
        totalLengthM < 1000.0 -> "${"%.2f".format(totalLengthM)}m"
        else                  -> "${"%.3f".format(totalLengthM / 1000.0)}km"
    }

    /** Diện tích bao phủ (chỉ tính nếu tuyến đóng ≥ 3 điểm, đơn vị m²) */
    val areaM2: Double get() {
        if (!isClosed || points.size < 3) return 0.0
        // Gauss Shoelace formula trên tọa độ phẳng VN-2000
        val pts = if (points.all { it.northing != 0.0 }) points
                  else return 0.0
        var area = 0.0
        val n = pts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i].northing * pts[j].easting
            area -= pts[j].northing * pts[i].easting
        }
        return abs(area) / 2.0
    }

    val areaFormatted: String get() = when {
        areaM2 == 0.0      -> "-"
        areaM2 < 10_000.0  -> "${"%.1f".format(areaM2)} m²"
        else               -> "${"%.4f".format(areaM2 / 10_000.0)} ha"
    }
}

/**
 * TraversePoint — Một điểm trong tuyến đo.
 */
data class TraversePoint(
    val id             : Int    = 0,
    val traverseId     : Int    = 0,
    val orderIndex     : Int,
    val pointCode      : String,
    val latitude       : Double,
    val longitude      : Double,
    val altitude       : Double,
    val geoidSep       : Double = 0.0,
    val northing       : Double = 0.0,
    val easting        : Double = 0.0,
    val centralMeridian: Double = 0.0,
    val zoneWidthDeg   : Int    = 3,
    val fixQuality     : Int    = 0,
    val hdop           : Double = 0.0,
    val satelliteCount : Int    = 0,
    val timestamp      : Long   = System.currentTimeMillis(),
    val note           : String = "",
    val surveyPointRef : Int?   = null
) {
    /** Khoảng cách đến điểm kế tiếp (mét, ưu tiên VN-2000) */
    fun distanceTo(other: TraversePoint): Double {
        return if (northing != 0.0 && other.northing != 0.0) {
            val dN = other.northing - northing
            val dE = other.easting  - easting
            sqrt(dN * dN + dE * dE)
        } else {
            // Haversine fallback
            val r = 6_371_000.0
            val lat1 = Math.toRadians(latitude);  val lat2 = Math.toRadians(other.latitude)
            val dLat = Math.toRadians(other.latitude - latitude)
            val dLon = Math.toRadians(other.longitude - longitude)
            val a = sin(dLat/2).pow(2) + cos(lat1)*cos(lat2)*sin(dLon/2).pow(2)
            r * 2 * atan2(sqrt(a), sqrt(1-a))
        }
    }

    val fixLabel: String get() = when (fixQuality) {
        4 -> "Fixed"; 5 -> "Float"; 2 -> "DGPS"; 1 -> "Single"; else -> "NoFix"
    }
}
