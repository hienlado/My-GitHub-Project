package com.hien.rtkmultidevice.data.db.dao

import androidx.room.*
import com.hien.rtkmultidevice.data.db.entity.SurveyPointEntity
import kotlinx.coroutines.flow.Flow

/**
 * SurveyPointDao — Truy xuất điểm đo RTK.
 */
@Dao
interface SurveyPointDao {

    // ── Đọc ────────────────────────────────────────────────

    /** Tất cả điểm của một dự án, theo thứ tự đo */
    @Query("SELECT * FROM survey_points WHERE projectId = :projectId ORDER BY orderIndex ASC")
    fun getPointsByProject(projectId: Int): Flow<List<SurveyPointEntity>>

    /** Lấy điểm theo id */
    @Query("SELECT * FROM survey_points WHERE id = :id")
    suspend fun getPointById(id: Int): SurveyPointEntity?

    /** Lấy orderIndex lớn nhất trong dự án → để gán cho điểm tiếp theo */
    @Query("SELECT COALESCE(MAX(orderIndex), 0) FROM survey_points WHERE projectId = :projectId")
    suspend fun getMaxOrderIndex(projectId: Int): Int

    /** Kiểm tra mã điểm đã tồn tại trong dự án chưa */
    @Query("SELECT COUNT(*) FROM survey_points WHERE projectId = :projectId AND pointCode = :code")
    suspend fun countByCode(projectId: Int, code: String): Int

    // ── Ghi ────────────────────────────────────────────────

    /** Lưu điểm mới — trả về id được gán */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoint(point: SurveyPointEntity): Long

    /** Cập nhật điểm (sửa mã, ghi chú...) */
    @Update
    suspend fun updatePoint(point: SurveyPointEntity)

    /** Xoá một điểm */
    @Delete
    suspend fun deletePoint(point: SurveyPointEntity)

    /** Xoá tất cả điểm của một dự án */
    @Query("DELETE FROM survey_points WHERE projectId = :projectId")
    suspend fun deleteAllPointsInProject(projectId: Int)
}
