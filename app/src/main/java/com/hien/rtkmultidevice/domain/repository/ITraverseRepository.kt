package com.hien.rtkmultidevice.domain.repository

import com.hien.rtkmultidevice.domain.model.Traverse
import com.hien.rtkmultidevice.domain.model.TraversePoint
import kotlinx.coroutines.flow.Flow

interface ITraverseRepository {
    fun getTraversesByProject(projectId: Int): Flow<List<Traverse>>
    suspend fun getTraverseById(id: Int): Traverse?
    suspend fun createTraverse(traverse: Traverse): Int
    suspend fun updateTraverse(traverse: Traverse)
    suspend fun deleteTraverse(traverse: Traverse)
    suspend fun addPoint(traverseId: Int, point: TraversePoint): Long
    suspend fun deletePoint(point: TraversePoint)
    suspend fun getNextOrderIndex(traverseId: Int): Int
}
