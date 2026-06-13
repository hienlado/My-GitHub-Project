package com.hien.rtkmultidevice.di

import android.content.Context
import androidx.room.Room
import com.hien.rtkmultidevice.data.db.AppDatabase
import com.hien.rtkmultidevice.data.db.AppDatabase.Companion.MIGRATION_1_2
import com.hien.rtkmultidevice.data.db.AppDatabase.Companion.MIGRATION_2_3
import com.hien.rtkmultidevice.data.db.dao.DeviceDao
import com.hien.rtkmultidevice.data.db.dao.ProjectDao
import com.hien.rtkmultidevice.data.db.dao.SurveyPointDao
import com.hien.rtkmultidevice.data.db.dao.TraverseDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DatabaseModule — Hilt module cung cấp Room Database và DAO.
 *
 * Version 2: Thêm MIGRATION_1_2 để nâng cấp schema không mất dữ liệu.
 * Không dùng destructive migration để tránh xoá dữ liệu đo ngoài thực địa.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    @Provides
    @Singleton
    fun provideDeviceDao(db: AppDatabase): DeviceDao = db.deviceDao()

    @Provides
    @Singleton
    fun provideProjectDao(db: AppDatabase): ProjectDao = db.projectDao()

    @Provides
    @Singleton
    fun provideSurveyPointDao(db: AppDatabase): SurveyPointDao = db.surveyPointDao()

    @Provides
    @Singleton
    fun provideTraverseDao(db: AppDatabase): TraverseDao = db.traverseDao()
}
