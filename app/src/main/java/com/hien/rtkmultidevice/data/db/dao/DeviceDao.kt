package com.hien.rtkmultidevice.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hien.rtkmultidevice.data.db.entity.DeviceEntity
import kotlinx.coroutines.flow.Flow

/**
 * DeviceDao — Data Access Object cho bảng "devices".
 *
 * @Dao → Room biết đây là interface truy vấn database.
 * Room tự sinh code implementation tại compile time (không cần viết thủ công).
 *
 * Quy tắc:
 *   - Query trả về Flow<> → tự động cập nhật khi DB thay đổi
 *   - Các hàm write dùng suspend → không block UI thread
 */
@Dao
interface DeviceDao {

    /**
     * Lấy tất cả thiết bị, sort theo lần dùng gần nhất.
     *
     * Flow<List<DeviceEntity>>: khi có bản ghi mới được thêm,
     * Flow tự emit list mới → UI tự động cập nhật (không cần reload).
     */
    @Query("SELECT * FROM devices ORDER BY lastUsedAt DESC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    /**
     * Lấy tối đa 5 thiết bị gần đây nhất cho màn hình Connection.
     */
    @Query("SELECT * FROM devices ORDER BY lastUsedAt DESC LIMIT 5")
    fun getRecentDevices(): Flow<List<DeviceEntity>>

    /**
     * Lưu hoặc cập nhật thiết bị.
     * OnConflictStrategy.REPLACE → nếu address đã tồn tại, thay thế
     * → upsert: kết nối lại cùng thiết bị chỉ cập nhật lastUsedAt.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceEntity)

    /**
     * Cập nhật thời gian + số lần kết nối sau mỗi lần dùng.
     */
    @Query("""
        UPDATE devices
        SET lastUsedAt = :timestamp, successCount = successCount + 1
        WHERE address = :address
    """)
    suspend fun updateLastUsed(address: String, timestamp: Long)

    /**
     * Xoá thiết bị khỏi lịch sử.
     */
    @Query("DELETE FROM devices WHERE address = :address")
    suspend fun deleteDevice(address: String)

    /**
     * Xoá toàn bộ lịch sử.
     */
    @Query("DELETE FROM devices")
    suspend fun clearAll()
}
