package com.hien.rtkmultidevice.report

import kotlin.math.hypot

/**
 * BienBanModel — Mô hình dữ liệu BIÊN BẢN BÀN GIAO MỐC GIỚI.
 *
 * Toàn bộ nội dung biên bản gom vào một object để:
 *   1. Ghi ra XML cho người dùng đọc/sửa (số GCN, địa chỉ, ngày tháng…)
 *   2. Đọc XML đã sửa → dựng lại object → xuất PDF hoàn chỉnh
 *
 * Quy trình: chọn thửa → xuất XML → kiểm tra/sửa → xuất PDF.
 */
data class BienBan(

    // ── Thời gian & địa điểm ────────────────────────────────
    val ngay    : Int,
    val thang   : Int,
    val nam     : Int,

    // ── Thửa đất ────────────────────────────────────────────
    val soThua      : String = "",
    val soTo        : String = "",
    /** Đơn vị hành chính HIỆN HÀNH. Từ 2025 cả nước bỏ cấp huyện,
     *  chỉ còn XÃ/PHƯỜNG và TỈNH/THÀNH PHỐ. */
    val xa          : String = "",
    val tinh        : String = "",
    /** Địa chỉ hành chính CŨ, ghi trong ngoặc để đối chiếu hồ sơ trước sáp nhập.
     *  Ví dụ: "xã Kim Long, huyện Châu Đức, tỉnh Bà Rịa - Vũng Tàu". */
    val diaChiCu    : String = "",
    /** @deprecated Cấp huyện đã bỏ. Giữ để đọc lại XML cũ, không in ra biên bản. */
    val huyen       : String = "",
    val dienTich    : String = "",
    val loaiDat     : String = "",

    // ── Chủ sử dụng đất (bên nhận mốc) ──────────────────────
    val chuSuDung       : String = "",
    val diaChiChu       : String = "",
    /** Xưng hô: "ông" / "bà" */
    val xungHo          : String = "ông",

    // ── Giấy chứng nhận ─────────────────────────────────────
    val soPhatHanhGcn   : String = "",
    val noiCapGcn       : String = "Văn phòng đăng ký đất đai tỉnh Bà Rịa - Vũng Tàu",
    val ngayCapGcn      : String = "",

    // ── Đơn vị đo đạc (bên giao mốc) — lấy từ Cài đặt ───────
    val donViTen        : String = "",
    val donViDaiDien    : String = "",
    val donViChucVu     : String = "",
    val donViDiaChi     : String = "",
    val donViVpdd       : String = "",

    // ── Hệ toạ độ ───────────────────────────────────────────
    val kinhTuyenTruc   : String = "107°45'",
    val muiChieu        : String = "3°",

    // ── Bảng kê toạ độ mốc giới ─────────────────────────────
    val moc : List<MocGioi> = emptyList()
) {
    /** Số mốc bàn giao, định dạng 2 chữ số như mẫu ("05"). */
    val soMocText: String get() = "%02d".format(moc.size)

    /**
     * Một mốc giới trong bảng kê.
     * @param khoangCach Khoảng cách (m) từ mốc này tới mốc KẾ TIẾP; mốc cuối = null.
     */
    data class MocGioi(
        val dinh        : String,
        val x           : Double,   // Northing
        val y           : Double,   // Easting
        val khoangCach  : Double? = null
    )

    companion object {
        /**
         * Dựng bảng kê từ danh sách đỉnh (N, E).
         *
         * Theo mẫu: đỉnh đầu được LẶP LẠI ở cuối để khép kín đường bao,
         * và cột K/C ghi khoảng cách tới đỉnh kế tiếp.
         */
        fun buildMoc(vertices: List<Pair<Double, Double>>, closed: Boolean = true): List<MocGioi> {
            if (vertices.isEmpty()) return emptyList()
            // Khép kín: thêm đỉnh 1 vào cuối nếu chưa trùng
            val pts = if (closed && vertices.first() != vertices.last())
                vertices + vertices.first() else vertices

            return pts.mapIndexed { i, (n, e) ->
                val next = pts.getOrNull(i + 1)
                MocGioi(
                    // Đỉnh cuối lặp lại mang số hiệu 1 (như mẫu)
                    dinh = if (i == pts.lastIndex && closed) "1" else "${i + 1}",
                    x    = n,
                    y    = e,
                    khoangCach = next?.let { (n2, e2) -> hypot(n2 - n, e2 - e) }
                )
            }
        }
    }
}
