package com.hien.rtkmultidevice.domain.repository

import com.hien.rtkmultidevice.domain.model.Project
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import kotlinx.coroutines.flow.Flow

interface IProjectRepository {
    fun getAllProjects(): Flow<List<Project>>
    suspend fun getProjectById(id: Int): Project?
    fun observeProject(id: Int): Flow<Project?>
    suspend fun createProject(project: Project): Int   // trả về id
    suspend fun updateProject(project: Project)
    suspend fun deleteProject(project: Project)
    /** Tăng nextPointIndex sau khi lưu điểm */
    suspend fun incrementPointIndex(projectId: Int)
}

interface ISurveyPointRepository {
    fun getPointsByProject(projectId: Int): Flow<List<SurveyPoint>>
    suspend fun getPointById(id: Int): SurveyPoint?
    suspend fun savePoint(point: SurveyPoint): Long   // trả về id
    suspend fun updatePoint(point: SurveyPoint)
    suspend fun deletePoint(point: SurveyPoint)
    suspend fun deleteAllPointsInProject(projectId: Int)
    /** Kiểm tra mã điểm đã tồn tại trong dự án chưa */
    suspend fun isCodeExists(projectId: Int, code: String): Boolean
}
