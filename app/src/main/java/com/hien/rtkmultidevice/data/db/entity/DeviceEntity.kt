package com.hien.rtkmultidevice.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * DeviceEntity — Bảng lưu lịch sử thiết bị đã kết nối.
 *
 * @Entity(tableName = "devices") → Room sẽ tạo bảng "devices" trong SQLite.
 *
 * Tại sao cần lưu lịch sử?
 *   → Ngoài thực địa, kỹ sư thường dùng 1-2 thiết bị cố định.
 *     Lưu lịch sử → tap 1 lần để kết nối lại, không cần
 *     mở BT settings hay nhập IP mỗi lần.
 */
@Entity(tableName = "devices")
data class DeviceEntity(

    /**
     * Primary key = địa chỉ thiết bị.
     * BT  → MAC address (VD: "AA:BB:CC:DD:EE:FF")
     * TCP → "host:port" (VD: "192.168.1.100:2000")
     *
     * Dùng address làm PK để upsert (insert or replace)
     * khi kết nối lại → cập nhật lastUsedAt thay vì tạo bản ghi mới.
     */
    @PrimaryKey
    val address: String,

    /** Tên thiết bị */
    val name: String,

    /**
     * Loại kết nối: "BLUETOOTH" hoặc "TCP_WIFI"
     * Lưu dạng String để Room dễ serialize.
     */
    val connectionType: String,

    /**
     * Thời gian sử dụng gần nhất (Unix timestamp ms).
     * Dùng để sort "gần đây nhất" lên đầu.
     */
    val lastUsedAt: Long = System.currentTimeMillis(),

    /**
     * Số lần kết nối thành công.
     * Có thể dùng để hiển thị badge "hay dùng".
     */
    val successCount: Int = 0
)
