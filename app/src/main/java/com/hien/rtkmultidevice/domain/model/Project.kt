package com.hien.rtkmultidevice.domain.model

import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Project — Domain model của dự án đo đạc.
 *
 * Dùng trong UI và business logic.
 * Được map từ ProjectEntity (data layer).
 */
data class Project(
    val id              : Int    = 0,
    val name            : String,
    val description     : String = "",
    val zoneWidthDeg    : Int    = 3,
    /** 0.0 = tự động theo GPS */
    val centralMeridian : Double = 0.0,
    val createdAt       : Long   = System.currentTimeMillis(),
    val lastModifiedAt  : Long   = System.currentTimeMillis(),
    val pointPrefix     : String = "P",
    val nextPointIndex  : Int    = 1,
    /** Số điểm đã đo — join từ query, không lưu trong bảng */
    val pointCount      : Int    = 0
) {
    /** Mã điểm tiếp theo: VD "P001", "P012" */
    val nextPointCode: String
        get() = "$pointPrefix%03d".format(nextPointIndex)

    /** Tên hệ toạ độ để hiển thị */
    val coordinateSystem: String
        get() = if (centralMeridian == 0.0)
            "VN-2000 / Múi ${zoneWidthDeg}° / Tự động"
        else
            Vn2000Zone.zoneName(centralMeridian, zoneWidthDeg)

    /** Ngày tạo định dạng dd/MM/yyyy */
    val createdDateFormatted: String
        get() = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            .format(Date(createdAt))

    /** Thời gian chỉnh sửa gần nhất */
    val lastModifiedFormatted: String
        get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(Date(lastModifiedAt))
}
