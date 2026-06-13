package com.hien.rtkmultidevice.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ProjectEntity — Bảng lưu trữ dự án đo đạc.
 *
 * Mỗi dự án là một công trình/khu vực đo đạc độc lập.
 * Các điểm đo (SurveyPoint) được liên kết qua projectId.
 */
@Entity(tableName = "projects")
data class ProjectEntity(

    @PrimaryKey(autoGenerate = true)
    val id              : Int    = 0,

    /** Tên dự án (bắt buộc) */
    val name            : String,

    /** Mô tả ngắn (tuỳ chọn) */
    val description     : String = "",

    /** Độ rộng múi VN-2000: 3 hoặc 6 */
    val zoneWidthDeg    : Int    = 3,

    /**
     * Kinh tuyến trục (Double).
     * 0.0 = tự động theo GPS khi đo.
     */
    val centralMeridian : Double = 0.0,

    /** Thời điểm tạo (Unix timestamp ms) */
    val createdAt       : Long   = System.currentTimeMillis(),

    /** Thời điểm chỉnh sửa lần cuối */
    val lastModifiedAt  : Long   = System.currentTimeMillis(),

    /** Tiền tố mã điểm tự động (VD: "P" → P001, P002) */
    val pointPrefix     : String = "P",

    /**
     * Số thứ tự tiếp theo cho mã điểm tự động.
     * VD: nextPointIndex = 5 → mã tiếp theo là P005
     */
    val nextPointIndex  : Int    = 1
)
