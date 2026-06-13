package com.hien.rtkmultidevice.domain.model

/**
 * Vn2000Coordinate — Kết quả chuyển đổi toạ độ VN-2000.
 *
 * Lưu trữ toạ độ phẳng (N, E) sau khi chiếu Gauss-Krüger,
 * cùng thông tin múi chiếu để hiển thị và kiểm tra.
 *
 * Định dạng hiển thị chuẩn ngành (Việt Nam):
 *   X (Northing): 2 325 478.234 m
 *   Y (Easting):    575 123.456 m
 *   Múi 3° / CM: 105°E
 *
 * Lưu ý quy ước trục VN-2000:
 *   X = Northing (hướng Bắc) — trục X trong hệ toạ độ phẳng Việt Nam
 *   Y = Easting  (hướng Đông) — trục Y
 *   (Khác với GIS quốc tế thường dùng (Easting, Northing) = (x, y))
 */
data class Vn2000Coordinate(

    /** Toạ độ X — Northing (mét), hướng Bắc */
    val northing: Double,

    /** Toạ độ Y — Easting (mét), hướng Đông */
    val easting: Double,

    /** Kinh tuyến trục múi chiếu (độ thập phân) */
    val centralMeridian: Double,

    /** Độ rộng múi: 3 hoặc 6 */
    val zoneWidthDeg: Int,

    /** Vĩ độ địa lý VN-2000 (sau Helmert, trước chiếu) — để debug */
    val latVn2000: Double = 0.0,

    /** Kinh độ địa lý VN-2000 (sau Helmert, trước chiếu) — để debug */
    val lonVn2000: Double = 0.0
) {
    // ── Tên múi chiếu để hiển thị ────────────────────────────
    val zoneName: String
        get() {
            val cmStr = if (centralMeridian == centralMeridian.toLong().toDouble())
                "${centralMeridian.toLong()}°E"
            else
                "${centralMeridian}°E"
            return "VN-2000 / Múi ${zoneWidthDeg}° / CM = $cmStr"
        }

    // ── Định dạng hiển thị theo quy ước Việt Nam (X, Y) ─────
    /** X (Northing) hiển thị dạng "2 325 478.234" */
    val northingFormatted: String
        get() = formatCoord(northing)

    /** Y (Easting) hiển thị dạng "575 123.456" */
    val eastingFormatted: String
        get() = formatCoord(easting)

    /** Định dạng đầy đủ cho copy/export */
    val fullCoordString: String
        get() = "X = $northingFormatted  Y = $eastingFormatted  ($zoneName)"

    // ── Helper ────────────────────────────────────────────────
    private fun formatCoord(value: Double): String {
        val intPart   = value.toLong()
        val fracPart  = ((value - intPart) * 1000).toLong()
        // Thêm dấu chấm phân cách hàng nghìn: 2325478 → 2 325 478
        val intStr    = intPart.toString().reversed()
            .chunked(3)
            .joinToString(" ")
            .reversed()
        return "%s.%03d".format(intStr, kotlin.math.abs(fracPart))
    }
}
