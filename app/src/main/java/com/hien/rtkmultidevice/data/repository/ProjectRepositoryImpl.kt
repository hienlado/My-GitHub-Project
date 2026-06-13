package com.hien.rtkmultidevice.data.repository

import com.hien.rtkmultidevice.data.db.dao.ProjectDao
import com.hien.rtkmultidevice.data.db.dao.ProjectWithCount
import com.hien.rtkmultidevice.data.db.dao.SurveyPointDao
import com.hien.rtkmultidevice.data.db.entity.ProjectEntity
import com.hien.rtkmultidevice.data.db.entity.SurveyPointEntity
import com.hien.rtkmultidevice.domain.model.Project
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import com.hien.rtkmultidevice.domain.repository.IProjectRepository
import com.hien.rtkmultidevice.domain.repository.ISurveyPointRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// ════════════════════════════════════════════════════════════
// ProjectRepositoryImpl
// ════════════════════════════════════════════════════════════

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao
) : IProjectRepository {

    override fun getAllProjects(): Flow<List<Project>> =
        projectDao.getAllProjectsWithCount().map { list -> list.map { it.toDomain() } }

    override suspend fun getProjectById(id: Int): Project? =
        projectDao.getProjectById(id)?.toDomain()          // pointCount = 0 (không cần khi lấy 1 dự án)

    override fun observeProject(id: Int): Flow<Project?> =
        projectDao.observeProject(id).map { it?.toDomain() }

    override suspend fun createProject(project: Project): Int =
        projectDao.insertProject(project.toEntity()).toInt()

    override suspend fun updateProject(project: Project) =
        projectDao.updateProject(project.toEntity())

    override suspend fun deleteProject(project: Project) =
        projectDao.deleteProject(project.toEntity())

    override suspend fun incrementPointIndex(projectId: Int) {
        val current = projectDao.getProjectById(projectId) ?: return
        projectDao.updateNextPointIndex(
            id        = projectId,
            nextIndex = current.nextPointIndex + 1
        )
    }
}

// ════════════════════════════════════════════════════════════
// SurveyPointRepositoryImpl
// ════════════════════════════════════════════════════════════

@Singleton
class SurveyPointRepositoryImpl @Inject constructor(
    private val surveyPointDao: SurveyPointDao
) : ISurveyPointRepository {

    override fun getPointsByProject(projectId: Int): Flow<List<SurveyPoint>> =
        surveyPointDao.getPointsByProject(projectId).map { list -> list.map { it.toDomain() } }

    override suspend fun getPointById(id: Int): SurveyPoint? =
        surveyPointDao.getPointById(id)?.toDomain()

    override suspend fun savePoint(point: SurveyPoint): Long {
        val maxOrder = surveyPointDao.getMaxOrderIndex(point.projectId)
        return surveyPointDao.insertPoint(point.toEntity(orderIndex = maxOrder + 1))
    }

    override suspend fun updatePoint(point: SurveyPoint) =
        surveyPointDao.updatePoint(point.toEntity())

    override suspend fun deletePoint(point: SurveyPoint) =
        surveyPointDao.deletePoint(point.toEntity())

    override suspend fun deleteAllPointsInProject(projectId: Int) =
        surveyPointDao.deleteAllPointsInProject(projectId)

    override suspend fun isCodeExists(projectId: Int, code: String): Boolean =
        surveyPointDao.countByCode(projectId, code) > 0
}

// ════════════════════════════════════════════════════════════
// Mappers — Entity ↔ Domain
// ════════════════════════════════════════════════════════════

// mapper khi không có join (getProjectById, observeProject)
private fun ProjectEntity.toDomain(pointCount: Int = 0) = Project(
    id              = id,
    name            = name,
    description     = description,
    zoneWidthDeg    = zoneWidthDeg,
    centralMeridian = centralMeridian,
    createdAt       = createdAt,
    lastModifiedAt  = lastModifiedAt,
    pointPrefix     = pointPrefix,
    nextPointIndex  = nextPointIndex,
    pointCount      = pointCount
)

// mapper khi có JOIN (getAllProjectsWithCount)
private fun ProjectWithCount.toDomain() = project.toDomain(pointCount = pointCount)

private fun Project.toEntity() = ProjectEntity(
    id              = id,
    name            = name,
    description     = description,
    zoneWidthDeg    = zoneWidthDeg,
    centralMeridian = centralMeridian,
    createdAt       = createdAt,
    lastModifiedAt  = lastModifiedAt,
    pointPrefix     = pointPrefix,
    nextPointIndex  = nextPointIndex
)

private fun SurveyPointEntity.toDomain() = SurveyPoint(
    id              = id,
    projectId       = projectId,
    pointCode       = pointCode,
    latitude        = latitude,
    longitude       = longitude,
    altitude        = altitude,
    geoidSeparation = geoidSeparation,
    northing        = northing,
    easting         = easting,
    centralMeridian = centralMeridian,
    zoneWidthDeg    = zoneWidthDeg,
    fixQuality      = fixQuality,
    hdop            = hdop,
    pdop            = pdop,
    satelliteCount  = satelliteCount,
    timestamp       = timestamp,
    note            = note,
    orderIndex      = orderIndex
)

private fun SurveyPoint.toEntity(orderIndex: Int = this.orderIndex) = SurveyPointEntity(
    id              = id,
    projectId       = projectId,
    pointCode       = pointCode,
    latitude        = latitude,
    longitude       = longitude,
    altitude        = altitude,
    geoidSeparation = geoidSeparation,
    northing        = northing,
    easting         = easting,
    centralMeridian = centralMeridian,
    zoneWidthDeg    = zoneWidthDeg,
    fixQuality      = fixQuality,
    hdop            = hdop,
    pdop            = pdop,
    satelliteCount  = satelliteCount,
    timestamp       = timestamp,
    note            = note,
    orderIndex      = orderIndex
)
