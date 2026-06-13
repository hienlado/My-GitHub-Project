package com.hien.rtkmultidevice.core.coordinate

/**
 * Vn2000Zone — Kinh tuyến trục và logic chọn múi VN-2000.
 *
 * Danh sách kinh tuyến trục chính thức của hệ VN-2000 (múi 3°)
 * theo Quyết định 83/2000/QĐ-TTg và Thông tư 973/2001/TT-TCDC:
 *
 *   103°00'  — Tây Bắc (Lai Châu, Điện Biên, Sơn La tây)
 *   104°00'  — Tây Bắc (Sơn La, Hoà Bình tây)
 *   104°30'  — Bắc Bộ (Lào Cai, Yên Bái)
 *   104°45'  — Bắc Bộ (Phú Thọ, Tuyên Quang)
 *   105°00'  — Đồng Bằng Bắc Bộ (Hà Nội)
 *   105°30'  — Bắc + Bắc Trung Bộ
 *   105°45'  — Đông Bắc + Bắc Trung Bộ
 *   106°00'  — Quảng Ninh, Thanh Hoá
 *   106°15'  — Nam Định, Ninh Bình, Thanh Hoá
 *   106°30'  — Nghệ An, Hà Tĩnh
 *   107°00'  — Quảng Bình, Quảng Trị
 *   107°15'  — Thừa Thiên Huế, Đà Nẵng
 *   107°45'  — Quảng Nam, Quảng Ngãi
 *   108°00'  — Bình Định, Phú Yên
 *   108°15'  — Khánh Hoà, Đắk Lắk
 *   108°30'  — Nam Trung Bộ, Đông Nam Bộ (TP.HCM)
 *
 * Tham số chiếu chung VN-2000:
 *   k₀ = 0.9999, FE = 500 000 m, FN = 0 m
 */
object Vn2000Zone {

    /** Hệ số tỉ lệ k₀ */
    const val SCALE_FACTOR  = 0.9999

    /** False Easting (mét) */
    const val FALSE_EASTING = 500_000.0

    /** False Northing (mét) */
    const val FALSE_NORTHING = 0.0

    // ── Kinh tuyến trục múi 3° (chính thức VN-2000) ─────────
    data class ZoneInfo(
        val centralMeridian : Double,   // độ thập phân
        val label           : String,   // hiển thị trên UI
        val description     : String    // vùng áp dụng
    )

    val ZONES_3DEG: List<ZoneInfo> = listOf(
        ZoneInfo(103.0,    "103°00'E", "Lai Châu, Điện Biên, Sơn La (tây)"),
        ZoneInfo(104.0,    "104°00'E", "Sơn La, Hoà Bình (tây)"),
        ZoneInfo(104.5,    "104°30'E", "Lào Cai, Yên Bái"),
        ZoneInfo(104.75,   "104°45'E", "Phú Thọ, Tuyên Quang"),
        ZoneInfo(105.0,    "105°00'E", "Hà Nội, Đồng Bằng Bắc Bộ"),
        ZoneInfo(105.5,    "105°30'E", "Bắc + Bắc Trung Bộ"),
        ZoneInfo(105.75,   "105°45'E", "Đông Bắc + Bắc Trung Bộ"),
        ZoneInfo(106.0,    "106°00'E", "Quảng Ninh, Thanh Hoá"),
        ZoneInfo(106.25,   "106°15'E", "Nam Định, Ninh Bình, Thanh Hoá"),
        ZoneInfo(106.5,    "106°30'E", "Nghệ An, Hà Tĩnh"),
        ZoneInfo(107.0,    "107°00'E", "Quảng Bình, Quảng Trị"),
        ZoneInfo(107.25,   "107°15'E", "Thừa Thiên Huế, Đà Nẵng"),
        ZoneInfo(107.75,   "107°45'E", "Quảng Nam, Quảng Ngãi"),
        ZoneInfo(108.0,    "108°00'E", "Bình Định, Phú Yên"),
        ZoneInfo(108.25,   "108°15'E", "Khánh Hoà, Đắk Lắk"),
        ZoneInfo(108.5,    "108°30'E", "Nam Trung Bộ, Đông Nam Bộ, TP.HCM")
    )

    // ── Kinh tuyến trục múi 6° (UTM-style, ít dùng) ─────────
    val ZONES_6DEG: List<ZoneInfo> = listOf(
        ZoneInfo(103.5,  "103°30'E", "Múi 48 (102°E–105°E)"),
        ZoneInfo(106.5,  "106°30'E", "Múi 49 (105°E–111°E)"),
        ZoneInfo(109.5,  "109°30'E", "Múi 50 (108°E–114°E)")
    )

    // ────────────────────────────────────────────────────────
    // Auto-select
    // ────────────────────────────────────────────────────────

    /**
     * Tự động chọn kinh tuyến trục múi 3° gần nhất với kinh độ hiện tại.
     *
     * Chọn ZoneInfo có |CM − longitude| nhỏ nhất trong danh sách ZONES_3DEG.
     *
     * @param longitude Kinh độ WGS-84 (độ thập phân)
     * @return ZoneInfo phù hợp nhất (mặc định CM = 105°00' nếu lỗi)
     */
    fun autoSelect3Deg(longitude: Double): ZoneInfo =
        ZONES_3DEG.minByOrNull { kotlin.math.abs(it.centralMeridian - longitude) }
            ?: ZONES_3DEG[4]  // fallback: 105°00'

    /**
     * Tự động chọn kinh tuyến trục múi 6° gần nhất.
     */
    fun autoSelect6Deg(longitude: Double): ZoneInfo =
        ZONES_6DEG.minByOrNull { kotlin.math.abs(it.centralMeridian - longitude) }
            ?: ZONES_6DEG[1]  // fallback: 106°30'

    /**
     * Tìm ZoneInfo theo giá trị centralMeridian (double).
     * Dùng khi đọc từ DataStore (lưu dạng Double).
     */
    fun findZone3Deg(centralMeridian: Double): ZoneInfo =
        ZONES_3DEG.minByOrNull { kotlin.math.abs(it.centralMeridian - centralMeridian) }
            ?: ZONES_3DEG[4]

    fun findZone6Deg(centralMeridian: Double): ZoneInfo =
        ZONES_6DEG.minByOrNull { kotlin.math.abs(it.centralMeridian - centralMeridian) }
            ?: ZONES_6DEG[1]

    // ────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────

    /**
     * Tên múi đầy đủ để hiển thị (VD: "VN-2000 / Múi 3° / CM = 105°00'E")
     */
    fun zoneName(centralMeridian: Double, zoneWidthDeg: Int): String {
        val zones = if (zoneWidthDeg == 3) ZONES_3DEG else ZONES_6DEG
        val label = zones.minByOrNull { kotlin.math.abs(it.centralMeridian - centralMeridian) }
            ?.label ?: "${centralMeridian}°E"
        return "VN-2000 / Múi ${zoneWidthDeg}° / CM = $label"
    }
}
