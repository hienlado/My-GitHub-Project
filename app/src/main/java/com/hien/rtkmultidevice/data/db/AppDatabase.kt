package com.hien.rtkmultidevice.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hien.rtkmultidevice.data.db.dao.DeviceDao
import com.hien.rtkmultidevice.data.db.dao.ProjectDao
import com.hien.rtkmultidevice.data.db.dao.SurveyPointDao
import com.hien.rtkmultidevice.data.db.dao.TraverseDao
import com.hien.rtkmultidevice.data.db.entity.DeviceEntity
import com.hien.rtkmultidevice.data.db.entity.ProjectEntity
import com.hien.rtkmultidevice.data.db.entity.SurveyPointEntity
import com.hien.rtkmultidevice.data.db.entity.TraverseEntity
import com.hien.rtkmultidevice.data.db.entity.TraversePointEntity

/**
 * AppDatabase — Room Database duy nhất của ứng dụng.
 *
 * Version history:
 *   v1 → Thiết bị (DeviceEntity) — Phase 2
 *   v2 → Thêm dự án + điểm đo (ProjectEntity, SurveyPointEntity) — Phase 5
 *
 * Migration strategy:
 *   - MIGRATION_1_2: tạo bảng projects + survey_points từ v1 → v2
 *   - Các version sau phải thêm migration rõ ràng để không mất dữ liệu đo.
 */
@Database(
    entities = [
        DeviceEntity::class,
        ProjectEntity::class,
        SurveyPointEntity::class,
        TraverseEntity::class,
        TraversePointEntity::class
    ],
    version      = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun deviceDao()      : DeviceDao
    abstract fun projectDao()     : ProjectDao
    abstract fun surveyPointDao() : SurveyPointDao
    abstract fun traverseDao()    : TraverseDao

    companion object {
        const val DATABASE_NAME = "rtk_field_db"

        /**
         * Migration v1 → v2: thêm 2 bảng mới.
         *
         * Bảng devices giữ nguyên, không bị ảnh hưởng.
         * NOT NULL với DEFAULT để tránh lỗi SQLite strict mode.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Bảng projects
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS projects (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name            TEXT    NOT NULL,
                        description     TEXT    NOT NULL DEFAULT '',
                        zoneWidthDeg    INTEGER NOT NULL DEFAULT 3,
                        centralMeridian REAL    NOT NULL DEFAULT 0.0,
                        createdAt       INTEGER NOT NULL,
                        lastModifiedAt  INTEGER NOT NULL,
                        pointPrefix     TEXT    NOT NULL DEFAULT 'P',
                        nextPointIndex  INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())

                // Bảng survey_points
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS survey_points (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        projectId       INTEGER NOT NULL,
                        pointCode       TEXT    NOT NULL,
                        latitude        REAL    NOT NULL,
                        longitude       REAL    NOT NULL,
                        altitude        REAL    NOT NULL,
                        geoidSeparation REAL    NOT NULL DEFAULT 0.0,
                        northing        REAL    NOT NULL,
                        easting         REAL    NOT NULL,
                        centralMeridian REAL    NOT NULL,
                        zoneWidthDeg    INTEGER NOT NULL DEFAULT 3,
                        fixQuality      INTEGER NOT NULL,
                        hdop            REAL    NOT NULL,
                        pdop            REAL    NOT NULL DEFAULT 0.0,
                        satelliteCount  INTEGER NOT NULL,
                        timestamp       INTEGER NOT NULL,
                        note            TEXT    NOT NULL DEFAULT '',
                        orderIndex      INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_survey_points_projectId ON survey_points(projectId)"
                )
            }
        }

        /**
         * Migration v2 → v3: thêm bảng traverses + traverse_points.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS traverses (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        projectId   INTEGER NOT NULL,
                        name        TEXT    NOT NULL,
                        description TEXT    NOT NULL DEFAULT '',
                        isClosed    INTEGER NOT NULL DEFAULT 0,
                        createdAt   INTEGER NOT NULL,
                        updatedAt   INTEGER NOT NULL,
                        FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_traverses_projectId ON traverses(projectId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS traverse_points (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        traverseId      INTEGER NOT NULL,
                        orderIndex      INTEGER NOT NULL,
                        pointCode       TEXT    NOT NULL,
                        latitude        REAL    NOT NULL,
                        longitude       REAL    NOT NULL,
                        altitude        REAL    NOT NULL,
                        geoidSep        REAL    NOT NULL DEFAULT 0.0,
                        northing        REAL    NOT NULL DEFAULT 0.0,
                        easting         REAL    NOT NULL DEFAULT 0.0,
                        centralMeridian REAL    NOT NULL DEFAULT 0.0,
                        zoneWidthDeg    INTEGER NOT NULL DEFAULT 3,
                        fixQuality      INTEGER NOT NULL DEFAULT 0,
                        hdop            REAL    NOT NULL DEFAULT 0.0,
                        satelliteCount  INTEGER NOT NULL DEFAULT 0,
                        timestamp       INTEGER NOT NULL,
                        note            TEXT    NOT NULL DEFAULT '',
                        surveyPointRef  INTEGER,
                        FOREIGN KEY(traverseId) REFERENCES traverses(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_traverse_points_traverseId ON traverse_points(traverseId)")
            }
        }
    }
}
