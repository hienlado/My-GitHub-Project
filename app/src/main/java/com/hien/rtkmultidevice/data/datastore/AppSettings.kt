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
            else null
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

    // ── Data classes ─────────────────────────────────────────

    /**
     * Cài đặt múi chiếu toạ độ.
     *
     * @param zoneWidthDeg              3 hoặc 6
     * @param centralMeridianOverride   null = tự động, Double = ghi đè (vd: 105.0)
     */
    data class CoordSettings(
        val zoneWidthDeg            : Int    = 3,
        val centralMeridianOverride  : Double? = null
    )
}
