package com.hien.rtkmultidevice.data.db.dao

import androidx.room.*
import com.hien.rtkmultidevice.data.db.entity.TraverseEntity
import com.hien.rtkmultidevice.data.db.entity.TraversePointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TraverseDao {

    // ── Traverse CRUD ─────────────────────────────────────

    @Query("SELECT * FROM traverses WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun getTraversesByProject(projectId: Int): Flow<List<TraverseEntity>>

    @Query("SELECT * FROM traverses WHERE id = :id")
    suspend fun getTraverseById(id: Int): TraverseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTraverse(traverse: TraverseEntity): Long

    @Update
    suspend fun updateTraverse(traverse: TraverseEntity)

    @Delete
    suspend fun deleteTraverse(traverse: TraverseEntity)

    @Query("DELETE FROM traverses WHERE projectId = :projectId")
    suspend fun deleteAllInProject(projectId: Int)

    // ── TraversePoint CRUD ────────────────────────────────

    @Query("SELECT * FROM traverse_points WHERE traverseId = :traverseId ORDER BY orderIndex ASC")
    fun getPointsByTraverse(traverseId: Int): Flow<List<TraversePointEntity>>

    @Query("SELECT * FROM traverse_points WHERE traverseId = :traverseId ORDER BY orderIndex ASC")
    suspend fun getPointsByTraverseDirect(traverseId: Int): List<TraversePointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: TraversePointEntity): Long

    @Delete
    suspend fun deletePoint(point: TraversePointEntity)

    @Query("DELETE FROM traverse_points WHERE traverseId = :traverseId")
    suspend fun deleteAllPointsInTraverse(traverseId: Int)

    @Query("SELECT COUNT(*) FROM traverse_points WHERE traverseId = :traverseId")
    suspend fun getPointCount(traverseId: Int): Int

    @Query("SELECT MAX(orderIndex) FROM traverse_points WHERE traverseId = :traverseId")
    suspend fun getMaxOrderIndex(traverseId: Int): Int?
}
