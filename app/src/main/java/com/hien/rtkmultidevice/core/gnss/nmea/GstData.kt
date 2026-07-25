package com.hien.rtkmultidevice.core.gnss.nmea

import kotlin.math.sqrt

/**
 * GstData — Dữ liệu từ câu NMEA $GNGST / $GPGST.
 *
 * GST = GNSS Pseudorange Error Statistics — câu DUY NHẤT trong NMEA cho biết
 * SAI SỐ THỰC TẾ (mét) mà máy thu tự ước lượng. Đây là nguồn của 2 số H/V
 * mà phần mềm đo đạc thương mại hiển thị trên thanh trạng thái.
 *
 * Cấu trúc:
 *   $xxGST,time,rms,majorDev,minorDev,orient,latDev,lonDev,altDev*cs
 *     [1] time      giờ UTC
 *     [2] rms       RMS của trị đo khoảng cách
 *     [3] majorDev  độ lệch chuẩn bán trục lớn ellipse sai số (m)
 *     [4] minorDev  độ lệch chuẩn bán trục nhỏ (m)
 *     [5] orient    hướng bán trục lớn (độ)
 *     [6] latDev    độ lệch chuẩn theo vĩ độ (m)
 *     [7] lonDev    độ lệch chuẩn theo kinh độ (m)
 *     [8] altDev    độ lệch chuẩn theo độ cao (m)
 *
 * LƯU Ý: đây là ĐỘ CHÍNH XÁC NỘI BỘ do máy tự đánh giá (precision), không phải
 * sai số so với mốc chuẩn (accuracy). Vẫn là chỉ số tốt nhất để biết
 * "lúc này đo có tin được không".
 */
data class GstData(
    /** Độ lệch chuẩn theo vĩ độ (m) */
    val latStdDev: Double,
    /** Độ lệch chuẩn theo kinh độ (m) */
    val lonStdDev: Double,
    /** Độ lệch chuẩn theo độ cao (m) — chính là V */
    val altStdDev: Double
) {
    /**
     * H — sai số MẶT BẰNG (m): gộp sai số vĩ độ và kinh độ.
     * H = √(σlat² + σlon²)
     */
    val horizontalAccuracy: Double
        get() = sqrt(latStdDev * latStdDev + lonStdDev * lonStdDev)

    /** V — sai số ĐỘ CAO (m) */
    val verticalAccuracy: Double get() = altStdDev
}
