package com.hien.rtkmultidevice.data.repository

import com.hien.rtkmultidevice.data.db.dao.TraverseDao
import com.hien.rtkmultidevice.data.db.entity.TraverseEntity
import com.hien.rtkmultidevice.data.db.entity.TraversePointEntity
import com.hien.rtkmultidevice.domain.model.Traverse
import com.hien.rtkmultidevice.domain.model.TraversePoint
import com.hien.rtkmultidevice.domain.repository.ITraverseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TraverseRepositoryImpl @Inject constructor(
    private val dao: TraverseDao
) : ITraverseRepository {

    override fun getTraversesByProject(projectId: Int): Flow<List<Traverse>> =
        dao.getTraversesByProject(projectId).map { entities ->
            entities.map { entity ->
                val pts = dao.getPointsByTraverseDirect(entity.id)
                entity.toDomain(pts.map { it.toDomain() })
            }
        }

    override suspend fun getTraverseById(id: Int): Traverse? {
        val entity = dao.getTraverseById(id) ?: return null
        val pts = dao.getPointsByTraverseDirect(id)
        return entity.toDomain(pts.map { it.toDomain() })
    }

    override suspend fun createTraverse(traverse: Traverse): Int =
        dao.insertTraverse(traverse.toEntity()).toInt()

    override suspend fun updateTraverse(traverse: Traverse) =
        dao.updateTraverse(traverse.toEntity())

    override suspend fun deleteTraverse(traverse: Traverse) =
        dao.deleteTraverse(traverse.toEntity())

    override suspend fun addPoint(traverseId: Int, point: TraversePoint): Long =
        dao.insertPoint(point.toEntity(traverseId))

    override suspend fun deletePoint(point: TraversePoint) =
        dao.deletePoint(point.toEntity(point.traverseId))

    override suspend fun getNextOrderIndex(traverseId: Int): Int =
        (dao.getMaxOrderIndex(traverseId) ?: -1) + 1

    // ── Mappers ───────────────────────────────────────────

    private fun TraverseEntity.toDomain(pts: List<TraversePoint> = emptyList()) = Traverse(
        id          = id,
        projectId   = projectId,
        name        = name,
        description = description,
        isClosed    = isClosed,
        points      = pts,
        createdAt   = createdAt,
        updatedAt   = updatedAt
    )

    private fun Traverse.toEntity() = TraverseEntity(
        id          = id,
        projectId   = projectId,
        name        = name,
        description = description,
        isClosed    = isClosed,
        createdAt   = createdAt,
        updatedAt   = updatedAt
    )

    private fun TraversePointEntity.toDomain() = TraversePoint(
        id              = id,
        traverseId      = traverseId,
        orderIndex      = orderIndex,
        pointCode       = pointCode,
        latitude        = latitude,
        longitude       = longitude,
        altitude        = altitude,
        geoidSep        = geoidSep,
        northing        = northing,
        easting         = easting,
        centralMeridian = centralMeridian,
        zoneWidthDeg    = zoneWidthDeg,
        fixQuality      = fixQuality,
        hdop            = hdop,
        satelliteCount  = satelliteCount,
        timestamp       = timestamp,
        note            = note,
        surveyPointRef  = surveyPointRef
    )

    private fun TraversePoint.toEntity(tId: Int) = TraversePointEntity(
        id              = id,
        traverseId      = tId,
        orderIndex      = orderIndex,
        pointCode       = pointCode,
        latitude        = latitude,
        longitude       = longitude,
        altitude        = altitude,
        geoidSep        = geoidSep,
        northing        = northing,
        easting         = easting,
        centralMeridian = centralMeridian,
        zoneWidthDeg    = zoneWidthDeg,
        fixQuality      = fixQuality,
        hdop            = hdop,
        satelliteCount  = satelliteCount,
        timestamp       = timestamp,
        note            = note,
        surveyPointRef  = surveyPointRef
    )
}
