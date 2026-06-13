package com.hien.rtkmultidevice.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SurveyPointEntity — Bảng lưu điểm đo RTK.
 *
 * Mỗi bản ghi = một lần bấm "Lưu điểm" ngoài thực địa.
 * Lưu đầy đủ toạ độ WGS-84 + VN-2000 + metadata chất lượng.
 *
 * ForeignKey → tự động xoá điểm khi xoá dự án (CASCADE).
 * Index trên projectId → query nhanh khi lấy điểm theo dự án.
 */
@Entity(
    tableName  = "survey_points",
    foreignKeys = [
        ForeignKey(
            entity        = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns  = ["projectId"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class SurveyPointEntity(

    @PrimaryKey(autoGenerate = true)
    val id              : Int    = 0,

    /** Khoá ngoại → Projects.id */
    val projectId       : Int,

    /** Mã điểm (VD: "P001", "MC01", "BT-12") */
    val pointCode       : String,

    // ── WGS-84 ─────────────────────────────────────────────
    val latitude        : Double,
    val longitude       : Double,
    /** Độ cao ellipsoid (mét) */
    val altitude        : Double,
    /** Undulation geoid (mét) — từ GGA field 11 */
    val geoidSeparation : Double = 0.0,

    // ── VN-2000 ─────────────────────────────────────────────
    /** X (Northing) mét */
    val northing        : Double,
    /** Y (Easting) mét */
    val easting         : Double,
    /** Kinh tuyến trục đã dùng để tính VN-2000 */
    val centralMeridian : Double,
    /** Độ rộng múi đã dùng */
    val zoneWidthDeg    : Int = 3,

    // ── Chất lượng GNSS khi đo ──────────────────────────────
    /** Fix quality: 1=Single, 2=DGPS, 4=RTK Fixed, 5=RTK Float */
    val fixQuality      : Int,
    /** Horizontal DOP */
    val hdop            : Double,
    /** Position DOP */
    val pdop            : Double = 0.0,
    /** Số vệ tinh đang dùng */
    val satelliteCount  : Int,

    // ── Metadata ─────────────────────────────────────────────
    /** Thời điểm đo (Unix ms) */
    val timestamp       : Long   = System.currentTimeMillis(),
    /** Ghi chú của kỹ sư (tuỳ chọn) */
    val note            : String = "",
    /** Thứ tự trong dự án (để sort theo thứ tự đo) */
    val orderIndex      : Int    = 0
) {
    /**
     * Nhãn Fix để export/hiển thị.
     * Giữ logic ở entity để CSV export không cần import domain layer.
     */
    val fixLabel: String get() = when (fixQuality) {
        4    -> "RTK FIXED"
        5    -> "RTK FLOAT"
        2    -> "DGPS"
        1    -> "SINGLE"
        else -> "NO FIX"
    }
}
