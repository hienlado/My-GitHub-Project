package com.hien.rtkmultidevice.ui.screens.map

/**
 * MapLabelConfig — Cấu hình hiển thị nhãn trên bản đồ.
 *
 * Người dùng có thể bật/tắt từng loại nhãn để phù hợp với nhu cầu thực địa.
 * Khi nhiều nhãn hiển thị cùng lúc, chúng được xếp thành nhiều dòng dưới marker.
 */
data class MapLabelConfig(
    /** Hiển thị mã điểm (P001, A1, ...) */
    val showPointCode  : Boolean = true,
    /** Hiển thị độ cao ellipsoid h (mét) */
    val showElevation  : Boolean = false,
    /** Hiển thị loại fix (FIXED, FLOAT, SINGLE) */
    val showFixQuality : Boolean = false,
    /** Hiển thị HDOP */
    val showHdop       : Boolean = false,
    /** Hiển thị tọa độ VN-2000 (X, Y) */
    val showVn2000     : Boolean = false,
    /** Hiển thị tập điểm Import (CSV/TXT) trên màn khảo sát */
    val showImportedPoints : Boolean = true,
    /** Hiển thị tuyến tạo từ điểm Import (2 điểm) trên màn khảo sát */
    val showImportedLine   : Boolean = true
)
