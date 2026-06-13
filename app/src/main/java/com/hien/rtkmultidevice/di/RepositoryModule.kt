package com.hien.rtkmultidevice.di

import com.hien.rtkmultidevice.data.repository.DeviceRepositoryImpl
import com.hien.rtkmultidevice.data.repository.ProjectRepositoryImpl
import com.hien.rtkmultidevice.data.repository.SurveyPointRepositoryImpl
import com.hien.rtkmultidevice.data.repository.TraverseRepositoryImpl
import com.hien.rtkmultidevice.domain.repository.ITraverseRepository
import com.hien.rtkmultidevice.domain.repository.IDeviceRepository
import com.hien.rtkmultidevice.domain.repository.IProjectRepository
import com.hien.rtkmultidevice.domain.repository.ISurveyPointRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): IDeviceRepository

    @Binds @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): IProjectRepository

    @Binds @Singleton
    abstract fun bindSurveyPointRepository(impl: SurveyPointRepositoryImpl): ISurveyPointRepository

    @Binds @Singleton
    abstract fun bindTraverseRepository(impl: TraverseRepositoryImpl): ITraverseRepository
}
