package com.hien.rtkmultidevice.domain.repository

import com.hien.rtkmultidevice.domain.model.DeviceInfo
import kotlinx.coroutines.flow.Flow

/**
 * IDeviceRepository — Interface định nghĩa hành vi lưu trữ thiết bị.
 *
 * Tại sao cần interface?
 *   → ViewModel chỉ phụ thuộc vào interface, không biết
 *     bên dưới là Room DB, file hay API. Khi test, có thể
 *     dùng FakeDeviceRepository thay vì DB thật.
 *   → Clean Architecture: Domain layer không phụ thuộc Data layer.
 */
interface IDeviceRepository {

    /** Flow danh sách thiết bị gần đây (tự cập nhật) */
    fun getRecentDevices(): Flow<List<DeviceInfo>>

    /** Lưu thiết bị sau khi kết nối thành công */
    suspend fun saveDevice(device: DeviceInfo)

    /** Cập nhật lần dùng gần nhất */
    suspend fun updateLastUsed(address: String)

    /** Xoá thiết bị khỏi lịch sử */
    suspend fun deleteDevice(address: String)
}
