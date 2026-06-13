package com.hien.rtkmultidevice.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TraverseEntity — Tuyến đo (polyline) trong một dự án.
 *
 * Mỗi tuyến có danh sách điểm được lưu trong [TraversePointEntity].
 * Tuyến có thể là:
 *   - OPEN: tuyến mở (điểm đầu ≠ điểm cuối)
 *   - CLOSED: tuyến đóng (tự đóng về điểm xuất phát)
 */
@Entity(
    tableName = "traverses",
    foreignKeys = [ForeignKey(
        entity        = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns  = ["projectId"],
        onDelete      = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class TraverseEntity(
    @PrimaryKey(autoGenerate = true)
    val id          : Int    = 0,
    val projectId   : Int,
    val name        : String,
    val description : String = "",
    val isClosed    : Boolean = false,  // true = tuyến đóng
    val createdAt   : Long   = System.currentTimeMillis(),
    val updatedAt   : Long   = System.currentTimeMillis()
)

/**
 * TraversePointEntity — Điểm thuộc một tuyến đo.
 *
 * Mỗi điểm có thứ tự [orderIndex] trong tuyến và tọa độ WGS-84 + VN-2000.
 * Có thể tham chiếu đến một [SurveyPointEntity] đã lưu (nếu lấy từ survey)
 * hoặc là điểm mới đo thẳng vào tuyến.
 */
@Entity(
    tableName = "traverse_points",
    foreignKeys = [ForeignKey(
        entity        = TraverseEntity::class,
        parentColumns = ["id"],
        childColumns  = ["traverseId"],
        onDelete      = ForeignKey.CASCADE
    )],
    indices = [Index("traverseId")]
)
data class TraversePointEntity(
    @PrimaryKey(autoGenerate = true)
    val id           : Int    = 0,
    val traverseId   : Int,
    val orderIndex   : Int,
    val pointCode    : String,

    // WGS-84
    val latitude     : Double,
    val longitude    : Double,
    val altitude     : Double,
    val geoidSep     : Double = 0.0,

    // VN-2000
    val northing     : Double = 0.0,
    val easting      : Double = 0.0,
    val centralMeridian : Double = 0.0,
    val zoneWidthDeg : Int    = 3,

    // Chất lượng
    val fixQuality   : Int    = 0,
    val hdop         : Double = 0.0,
    val satelliteCount: Int   = 0,

    // Metadata
    val timestamp    : Long   = System.currentTimeMillis(),
    val note         : String = "",

    // Tham chiếu đến survey point (tuỳ chọn)
    val surveyPointRef: Int? = null
)
