package com.hien.rtkmultidevice.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hien.rtkmultidevice.core.gnss.ntrip.NtripConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AppSettings — Lưu trữ cài đặt ứng dụng bằng DataStore.
 *
 * Tại sao DataStore thay vì SharedPreferences?
 *   ✅ Bất đồng bộ (suspend/Flow) — không block UI thread
 *   ✅ An toàn với Coroutines
 *   ✅ Type-safe với Preferences keys
 *
 * Nhóm cài đặt:
 *   1. NTRIP — host/port/mountpoint/user/pass/interval
 *   2. Toạ độ — múi chiếu VN-2000, ghi đè kinh tuyến trục
 */
private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "rtk_app_settings")

@Singleton
class AppSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val secureStringCipher = SecureStringCipher()

    companion object {
        // ── NTRIP ────────────────────────────────────────────
        private val KEY_NTRIP_HOST         = stringPreferencesKey("ntrip_host")
        private val KEY_NTRIP_PORT         = intPreferencesKey("ntrip_port")
        private val KEY_NTRIP_MOUNTPOINT   = stringPreferencesKey("ntrip_mountpoint")
        private val KEY_NTRIP_USERNAME     = stringPreferencesKey("ntrip_username")
        private val KEY_NTRIP_PASSWORD     = stringPreferencesKey("ntrip_password")
        private val KEY_NTRIP_GGA_INTERVAL = intPreferencesKey("ntrip_gga_interval")

        // ── Dự án đang hoạt động ────────────────────────────
        /**
         * ID dự án đang mở. -1 = chưa chọn dự án.
         */
        val KEY_ACTIVE_PROJECT_ID = intPreferencesKey("active_project_id")

        // ── Toạ độ / Múi chiếu VN-2000 ──────────────────────
        /**
         * Độ rộng múi: 3 hoặc 6.
         * Mặc định: 3 (dùng cho đo đạc địa chính, RTK).
         */
        private val KEY_ZONE_WIDTH = intPreferencesKey("coord_zone_width")

        /**
         * Ghi đè kinh tuyến trục (độ thập phân).
         * 0.0 = không ghi đè → tự động chọn theo GPS.
         */
        private val KEY_CM_OVERRIDE = doublePreferencesKey("coord_cm_override")

        /**
         * True nếu user đã bật ghi đè kinh tuyến trục.
         */
        private val KEY_CM_OVERRIDE_ENABLED = booleanPreferencesKey("coord_cm_override_enabled")

        // ── Hiệu chỉnh về mốc chuẩn (localization tịnh tiến ΔN/ΔE) ──
        private val KEY_CALIB_N       = doublePreferencesKey("coord_calib_n")
        private val KEY_CALIB_E       = doublePreferencesKey("coord_calib_e")
        private val KEY_CALIB_ENABLED = booleanPreferencesKey("coord_calib_enabled")
        private val KEY_ANTENNA_HEIGHT = doublePreferencesKey("coord_antenna_height")

        // ── Máy trạm (Base) ──────────────────────────────────
        private val KEY_BASE_MODE = intPreferencesKey("base_mode")           // 0=điểm đã biết,1=vị trí hiện tại,2=bình sai TB
        private val KEY_BASE_NAME = stringPreferencesKey("base_name")
        private val KEY_BASE_LAT  = doublePreferencesKey("base_lat")
        private val KEY_BASE_LON  = doublePreferencesKey("base_lon")
        private val KEY_BASE_H    = doublePreferencesKey("base_height")       // độ cao ellipsoid (m)
        private val KEY_BASE_ANT  = doublePreferencesKey("base_ant_height")   // chiều cao anten base (m)
        private val KEY_BASE_AVG  = intPreferencesKey("base_avg_seconds")

        // ── Thu thập điểm (Survey) ──────────────────────────
        /** Bật âm báo trạng thái fix (Single/Float/Fixed) khi đo. Mặc định: bật. */
        private val KEY_SURVEY_SOUND_ENABLED   = booleanPreferencesKey("survey_sound_enabled")

        /**
         * Chỉ cho lưu điểm khi đạt RTK FIXED.
         * Mặc định: false (cho lưu mọi trạng thái có tín hiệu).
         */
        private val KEY_SURVEY_REQUIRE_FIXED   = booleanPreferencesKey("survey_require_fixed")
    }

    // ── NTRIP Config ─────────────────────────────────────────

    /**
     * Flow<NtripConfig> — tự động cập nhật khi settings thay đổi.
     */
    val ntripConfigFlow: Flow<NtripConfig> = context.dataStore.data.map { prefs ->
        NtripConfig(
            host               = prefs[KEY_NTRIP_HOST]         ?: "",
            port               = prefs[KEY_NTRIP_PORT]         ?: 2101,
            mountPoint         = prefs[KEY_NTRIP_MOUNTPOINT]   ?: "",
            username           = prefs[KEY_NTRIP_USERNAME]     ?: "",
            password           = secureStringCipher.decrypt(prefs[KEY_NTRIP_PASSWORD] ?: ""),
            ggaIntervalSeconds = prefs[KEY_NTRIP_GGA_INTERVAL] ?: 5
        )
    }

    /** Lưu cấu hình NTRIP mới. */
    suspend fun saveNtripConfig(config: NtripConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NTRIP_HOST]         = config.host
            prefs[KEY_NTRIP_PORT]         = config.port
            prefs[KEY_NTRIP_MOUNTPOINT]   = config.mountPoint
            prefs[KEY_NTRIP_USERNAME]     = config.username
            prefs[KEY_NTRIP_PASSWORD]     = secureStringCipher.encrypt(config.password)
            prefs[KEY_NTRIP_GGA_INTERVAL] = config.ggaIntervalSeconds
        }
    }

    // ── Active Project ────────────────────────────────────────

    /** Flow ID dự án đang mở (-1 = chưa chọn) */
    val activeProjectIdFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_PROJECT_ID] ?: -1
    }

    suspend fun setActiveProject(projectId: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PROJECT_ID] = projectId
        }
    }

    suspend fun clearActiveProject() {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PROJECT_ID] = -1
        }
    }

    // ── Coordinate Settings ───────────────────────────────────

    /**
     * Flow cài đặt toạ độ VN-2000.
     * Emit CoordSettings mỗi khi user thay đổi múi.
     */
    val coordSettingsFlow: Flow<CoordSettings> = context.dataStore.data.map { prefs ->
        CoordSettings(
            zoneWidthDeg           = prefs[KEY_ZONE_WIDTH]            ?: 3,
            centralMeridianOverride = if (prefs[KEY_CM_OVERRIDE_ENABLED] == true)
                prefs[KEY_CM_OVERRIDE]
            else null,
            calibN       = prefs[KEY_CALIB_N] ?: 0.0,
            calibE       = prefs[KEY_CALIB_E] ?: 0.0,
            calibEnabled = prefs[KEY_CALIB_ENABLED] ?: false,
            antennaHeight = prefs[KEY_ANTENNA_HEIGHT] ?: 0.0
        )
    }

    /** Lưu cài đặt múi chiếu. */
    suspend fun saveCoordSettings(settings: CoordSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ZONE_WIDTH]            = settings.zoneWidthDeg
            prefs[KEY_CM_OVERRIDE_ENABLED]   = settings.centralMeridianOverride != null
            prefs[KEY_CM_OVERRIDE]           = settings.centralMeridianOverride ?: 0.0
        }
    }

    /** Lưu tham số hiệu chỉnh về mốc (tịnh tiến ΔN/ΔE) + bật/tắt. */
    suspend fun saveCalibration(deltaN: Double, deltaE: Double, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CALIB_N]       = deltaN
            prefs[KEY_CALIB_E]       = deltaE
            prefs[KEY_CALIB_ENABLED] = enabled
        }
    }

    /** Lưu chiều cao anten (mét) — trừ khỏi cao độ đo để về mốc mặt đất. */
    suspend fun saveAntennaHeight(meters: Double) {
        context.dataStore.edit { it[KEY_ANTENNA_HEIGHT] = meters }
    }

    // ── Máy trạm (Base) ───────────────────────────────────────
    val baseConfigFlow: Flow<BaseConfig> = context.dataStore.data.map { prefs ->
        BaseConfig(
            mode          = prefs[KEY_BASE_MODE] ?: 0,
            name          = prefs[KEY_BASE_NAME] ?: "BASE",
            lat           = prefs[KEY_BASE_LAT]  ?: 0.0,
            lon           = prefs[KEY_BASE_LON]  ?: 0.0,
            ellHeight     = prefs[KEY_BASE_H]    ?: 0.0,
            antennaHeight = prefs[KEY_BASE_ANT]  ?: 0.0,
            avgSeconds    = prefs[KEY_BASE_AVG]  ?: 60
        )
    }

    suspend fun saveBaseConfig(c: BaseConfig) {
        context.dataStore.edit { p ->
            p[KEY_BASE_MODE] = c.mode
            p[KEY_BASE_NAME] = c.name
            p[KEY_BASE_LAT]  = c.lat
            p[KEY_BASE_LON]  = c.lon
            p[KEY_BASE_H]    = c.ellHeight
            p[KEY_BASE_ANT]  = c.antennaHeight
            p[KEY_BASE_AVG]  = c.avgSeconds
        }
    }

    // ── Survey Settings ───────────────────────────────────────

    /** Cài đặt thu thập điểm — âm báo fix + ràng buộc lưu điểm */
    val surveySettingsFlow: Flow<SurveySettings> = context.dataStore.data.map { prefs ->
        SurveySettings(
            soundEnabled  = prefs[KEY_SURVEY_SOUND_ENABLED] ?: true,
            requireFixed  = prefs[KEY_SURVEY_REQUIRE_FIXED] ?: false
        )
    }

    suspend fun setSurveySoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SURVEY_SOUND_ENABLED] = enabled }
    }

    suspend fun setSurveyRequireFixed(required: Boolean) {
        context.dataStore.edit { it[KEY_SURVEY_REQUIRE_FIXED] = required }
    }

    // ── Data classes ─────────────────────────────────────────

    /**
     * Cài đặt màn hình thu thập điểm.
     * @param soundEnabled  Phát âm báo theo trạng thái fix khi đo
     * @param requireFixed  Chỉ cho lưu điểm khi đạt RTK FIXED
     */
    data class SurveySettings(
        val soundEnabled : Boolean = true,
        val requireFixed : Boolean = false
    )

    /**
     * Cài đặt múi chiếu toạ độ.
     *
     * @param zoneWidthDeg              3 hoặc 6
     * @param centralMeridianOverride   null = tự động, Double = ghi đè (vd: 105.0)
     */
    data class CoordSettings(
        val zoneWidthDeg            : Int    = 3,
        val centralMeridianOverride  : Double? = null,
        /** Hiệu chỉnh tịnh tiến về mốc chuẩn (mét) */
        val calibN       : Double  = 0.0,
        val calibE       : Double  = 0.0,
        val calibEnabled : Boolean = false,
        /** Chiều cao anten (mét) — trừ khỏi cao độ đo (tâm pha anten) để về mặt đất */
        val antennaHeight : Double = 0.0
    )

    /**
     * Cấu hình Máy trạm (Base).
     * @param mode 0=điểm đã biết, 1=vị trí hiện tại, 2=bình sai trung bình
     * @param lat/lon/ellHeight  Toạ độ base (WGS-84, độ cao ellipsoid)
     */
    data class BaseConfig(
        val mode          : Int    = 0,
        val name          : String = "BASE",
        val lat           : Double = 0.0,
        val lon           : Double = 0.0,
        val ellHeight     : Double = 0.0,
        val antennaHeight : Double = 0.0,
        val avgSeconds    : Int    = 60
    )
}
