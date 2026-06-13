package com.hien.rtkmultidevice.core.gnss.nmea

/**
 * GsaData — Dữ liệu từ câu NMEA $GNGSA / $GPGSA.
 *
 * GSA = GNSS DOP and Active Satellites
 * Cung cấp thông tin về độ chính xác hình học (DOP) và
 * danh sách vệ tinh đang được dùng để tính vị trí.
 *
 * DOP (Dilution of Precision) — Hệ số pha loãng độ chính xác:
 *   < 1    : Lý tưởng
 *   1–2    : Xuất sắc
 *   2–5    : Tốt
 *   5–10   : Trung bình
 *   > 10   : Kém
 */
data class GsaData(

    /**
     * Chế độ chọn vệ tinh:
     *   M = Manual (người dùng chỉ định)
     *   A = Automatic
     */
    val selectionMode: Char,

    /**
     * Fix mode:
     *   1 = No fix
     *   2 = 2D fix
     *   3 = 3D fix
     */
    val fixMode: Int,

    /** Danh sách ID vệ tinh đang được dùng (tối đa 12) */
    val satelliteIds: List<Int>,

    /** PDOP — Position DOP (3D) */
    val pdop: Double,

    /** HDOP — Horizontal DOP */
    val hdop: Double,

    /** VDOP — Vertical DOP */
    val vdop: Double
)
