package com.hien.rtkmultidevice.data.repository

import com.hien.rtkmultidevice.data.db.dao.DeviceDao
import com.hien.rtkmultidevice.data.db.entity.DeviceEntity
import com.hien.rtkmultidevice.domain.model.DeviceInfo
import com.hien.rtkmultidevice.domain.repository.IDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeviceRepositoryImpl — Implementation của IDeviceRepository dùng Room DB.
 *
 * Nhiệm vụ chính:
 *   - Chuyển đổi giữa DeviceEntity (DB) và DeviceInfo (Domain)
 *   - Cách ly ViewModel khỏi chi tiết của Room
 *
 * Đây là pattern "Mapper":
 *   DeviceEntity ←→ DeviceInfo
 *   (DB layer)        (Domain layer)
 */
@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val deviceDao: DeviceDao
) : IDeviceRepository {

    /**
     * Flow<List<DeviceInfo>> — tự cập nhật khi DB thay đổi.
     * .map { } chuyển đổi List<DeviceEntity> → List<DeviceInfo>
     */
    override fun getRecentDevices(): Flow<List<DeviceInfo>> =
        deviceDao.getRecentDevices().map { entities ->
            entities.map { it.toDomain() }
        }

    /** Lưu thiết bị mới hoặc cập nhật nếu đã tồn tại */
    override suspend fun saveDevice(device: DeviceInfo) {
        deviceDao.upsertDevice(device.toEntity())
    }

    /** Cập nhật timestamp lần dùng gần nhất */
    override suspend fun updateLastUsed(address: String) {
        deviceDao.updateLastUsed(address, System.currentTimeMillis())
    }

    /** Xoá khỏi lịch sử */
    override suspend fun deleteDevice(address: String) {
        deviceDao.deleteDevice(address)
    }

    // ── Mapper functions ──────────────────────────────────────

    private fun DeviceEntity.toDomain() = DeviceInfo(
        name           = name,
        address        = address,
        type           = when (connectionType) {
            "TCP_WIFI" -> DeviceInfo.ConnectionType.TCP_WIFI
            else       -> DeviceInfo.ConnectionType.BLUETOOTH
        },
        isRecentlyUsed = true
    )

    private fun DeviceInfo.toEntity() = DeviceEntity(
        address        = address,
        name           = name,
        connectionType = type.name,   // enum.name → "BLUETOOTH" hay "TCP_WIFI"
        lastUsedAt     = System.currentTimeMillis()
    )
}
