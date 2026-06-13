package com.hien.rtkmultidevice.core.coordinate

import com.hien.rtkmultidevice.domain.model.Vn2000Coordinate

/**
 * Vn2000Converter — Facade chuyển đổi WGS-84 → VN-2000 (N, E).
 *
 * Quy trình 3 bước:
 *   1. HelmertTransform:  WGS-84 (φ,λ,h) → VN-2000 (φ',λ',h')
 *   2. GaussKruger:       VN-2000 (φ',λ') → lưới phẳng (N, E) mét
 *   3. Đóng gói kết quả vào Vn2000Coordinate
 *
 * Sử dụng:
 *   val result = Vn2000Converter.convert(
 *       lat  = 21.0278,
 *       lon  = 105.8342,
 *       h    = 15.3,
 *       centralMeridianOverride = null   // auto-detect
 *   )
 *   println(result.northing)   // ≈ 2 325 000 m
 *   println(result.easting)    // ≈ 575 000 m
 */
object Vn2000Converter {

    // ────────────────────────────────────────────────────────
    // Chuyển đổi múi 3° (mặc định cho RTK Việt Nam)
    // ────────────────────────────────────────────────────────

    /**
     * Chuyển đổi WGS-84 → VN-2000 múi 3°.
     *
     * @param lat                    Vĩ độ WGS-84 (độ thập phân)
     * @param lon                    Kinh độ WGS-84 (độ thập phân)
     * @param h                      Độ cao ellipsoid WGS-84 (mét)
     * @param centralMeridianOverride Ghi đè kinh tuyến trục (null = tự động)
     * @return Vn2000Coordinate hoặc null nếu dữ liệu đầu vào không hợp lệ
     */
    fun convert(
        lat                    : Double,
        lon                    : Double,
        h                      : Double = 0.0,
        centralMeridianOverride : Double? = null
    ): Vn2000Coordinate? {
        // Kiểm tra phạm vi hợp lệ (Việt Nam ≈ 8°–24°N, 102°–118°E)
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat == 0.0 && lon == 0.0)  return null   // chưa có fix

        // Xác định kinh tuyến trục — dùng ZoneInfo.centralMeridian
        val cm = centralMeridianOverride ?: Vn2000Zone.autoSelect3Deg(lon).centralMeridian

        return try {
            // Bước 1: Helmert 7-parameter → VN-2000 địa lý
            val (latVn, lonVn, hVn) = HelmertTransform.wgs84ToVn2000(lat, lon, h)

            // Bước 2: Gauss-Krüger → VN-2000 lưới phẳng
            val (northing, easting) = GaussKruger.project(
                latDeg        = latVn,
                lonDeg        = lonVn,
                centralMerDeg = cm,
                k0            = Vn2000Zone.SCALE_FACTOR,
                falseEasting  = Vn2000Zone.FALSE_EASTING,
                falseNorthing = Vn2000Zone.FALSE_NORTHING
            )

            Vn2000Coordinate(
                northing         = northing,
                easting          = easting,
                centralMeridian  = cm,
                zoneWidthDeg     = 3,
                latVn2000        = latVn,
                lonVn2000        = lonVn
            )
        } catch (e: Exception) {
            null
        }
    }

    // ────────────────────────────────────────────────────────
    // Chuyển đổi múi 6° (UTM-style)
    // ────────────────────────────────────────────────────────

    /**
     * Chuyển đổi WGS-84 → VN-2000 múi 6°.
     *
     * @param lat                    Vĩ độ WGS-84 (độ thập phân)
     * @param lon                    Kinh độ WGS-84 (độ thập phân)
     * @param h                      Độ cao ellipsoid WGS-84 (mét)
     * @param centralMeridianOverride Ghi đè kinh tuyến trục (null = tự động)
     */
    fun convert6Deg(
        lat                    : Double,
        lon                    : Double,
        h                      : Double = 0.0,
        centralMeridianOverride : Double? = null
    ): Vn2000Coordinate? {
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat == 0.0 && lon == 0.0)  return null

        val cm = centralMeridianOverride ?: Vn2000Zone.autoSelect6Deg(lon).centralMeridian

        return try {
            val (latVn, lonVn, hVn) = HelmertTransform.wgs84ToVn2000(lat, lon, h)

            val (northing, easting) = GaussKruger.project(
                latDeg        = latVn,
                lonDeg        = lonVn,
                centralMerDeg = cm,
                k0            = Vn2000Zone.SCALE_FACTOR,
                falseEasting  = Vn2000Zone.FALSE_EASTING,
                falseNorthing = Vn2000Zone.FALSE_NORTHING
            )

            Vn2000Coordinate(
                northing        = northing,
                easting         = easting,
                centralMeridian = cm,
                zoneWidthDeg    = 6,
                latVn2000       = latVn,
                lonVn2000       = lonVn
            )
        } catch (e: Exception) {
            null
        }
    }
}
