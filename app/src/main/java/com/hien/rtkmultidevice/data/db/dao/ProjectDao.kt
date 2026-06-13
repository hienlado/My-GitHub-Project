package com.hien.rtkmultidevice.data.db.dao

import androidx.room.*
import com.hien.rtkmultidevice.data.db.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * ProjectDao — Truy xuất dữ liệu dự án đo đạc.
 *
 * Tất cả query trả về Flow → Compose tự cập nhật khi DB thay đổi.
 * Các thao tác ghi dùng suspend → gọi từ coroutine (ViewModel).
 */

/**
 * POJO để Room map kết quả JOIN — ProjectEntity + pointCount.
 * Không phải Entity (không có @Entity), chỉ dùng để đọc.
 */
data class ProjectWithCount(
    @Embedded val project: ProjectEntity,
    @ColumnInfo(name = "pointCount") val pointCount: Int = 0
)

@Dao
interface ProjectDao {

    // ── Đọc ────────────────────────────────────────────────

    /**
     * Tất cả dự án kèm số điểm đã đo, mới nhất lên trên.
     * LEFT JOIN để dự án chưa có điểm vẫn xuất hiện (COUNT = 0).
     */
    @Query("""
        SELECT p.*, COALESCE(COUNT(s.id), 0) AS pointCount
        FROM projects p
        LEFT JOIN survey_points s ON p.id = s.projectId
        GROUP BY p.id
        ORDER BY p.lastModifiedAt DESC
    """)
    fun getAllProjectsWithCount(): Flow<List<ProjectWithCount>>

    /** Lấy một dự án theo id */
    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: Int): ProjectEntity?

    /** Lấy một dự án theo id, trả về Flow (observe liên tục) */
    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeProject(id: Int): Flow<ProjectEntity?>

    /** Đếm tổng điểm của một dự án */
    @Query("SELECT COUNT(*) FROM survey_points WHERE projectId = :projectId")
    fun getPointCount(projectId: Int): Flow<Int>

    // ── Ghi ────────────────────────────────────────────────

    /** Tạo dự án mới — trả về id được gán */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProject(project: ProjectEntity): Long

    /** Cập nhật dự án (tên, mô tả, kinh tuyến trục...) */
    @Update
    suspend fun updateProject(project: ProjectEntity)

    /** Xoá dự án (kèm CASCADE xoá toàn bộ điểm) */
    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    /** Cập nhật nextPointIndex sau mỗi lần tạo điểm */
    @Query("UPDATE projects SET nextPointIndex = :nextIndex, lastModifiedAt = :ts WHERE id = :id")
    suspend fun updateNextPointIndex(id: Int, nextIndex: Int, ts: Long = System.currentTimeMillis())

    /** Cập nhật lastModifiedAt */
    @Query("UPDATE projects SET lastModifiedAt = :ts WHERE id = :id")
    suspend fun touchProject(id: Int, ts: Long = System.currentTimeMillis())
}
