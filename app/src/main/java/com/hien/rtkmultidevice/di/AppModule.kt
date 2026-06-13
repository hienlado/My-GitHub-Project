package com.hien.rtkmultidevice.di

import com.hien.rtkmultidevice.core.connection.bluetooth.BluetoothDeviceSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AppModule — Hilt DI module cấp SingletonComponent.
 *
 * Tại sao cần Hilt?
 *   Thay vì viết:
 *     val settings = AppSettings(context)
 *     val manager  = ConnectionManager()
 *     val gnss     = GnssDataManager(manager)
 *
 *   Ta chỉ cần:
 *     @Inject lateinit var gnssDataManager: GnssDataManager
 *
 *   Hilt lo việc tạo và quản lý vòng đời các object.
 *
 * Các class có @Inject constructor() và @Singleton được Hilt
 * tự động bind mà không cần khai báo ở đây:
 *   ✅ ConnectionManager
 *   ✅ GnssDataManager
 *   ✅ BluetoothDeviceSource
 *   ✅ AppSettings
 *
 * Module này chỉ cần khai báo những thứ cần config đặc biệt.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Ví dụ: nếu cần inject interface thay vì concrete class,
     * khai báo binding ở đây. Hiện tại các class đã dùng
     * @Inject constructor nên module này chủ yếu là placeholder.
     *
     * Phase 2+ sẽ thêm binding cho Room Database, Repository, v.v.
     */
}
