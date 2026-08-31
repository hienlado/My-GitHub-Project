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

        // ── Ghi nhớ để CẢNH BÁO khi có thay đổi ──────────────
        /** IP điện thoại lần gần nhất (máy thu trỏ RTK Client về IP này) */
        private val KEY_LAST_PHONE_IP  = stringPreferencesKey("last_phone_ip")

        // ── Thông tin đơn vị đo đạc (in trên Biên bản bàn giao mốc giới) ──
        private val KEY_RPT_TEN      = stringPreferencesKey("rpt_don_vi_ten")
        private val KEY_RPT_DAIDIEN  = stringPreferencesKey("rpt_dai_dien")
        private val KEY_RPT_CHUCVU   = stringPreferencesKey("rpt_chuc_vu")
        private val KEY_RPT_DIACHI   = stringPreferencesKey("rpt_dia_chi")
        private val KEY_RPT_VPDD     = stringPreferencesKey("rpt_vpdd")
        private val KEY_RPT_NOICAP   = stringPreferencesKey("rpt_noi_cap_gcn")

        // ── Lịch sử tìm kiếm (mỗi loại giữ 10 mục gần nhất) ──
        private val KEY_RECENT_SHEETS = stringPreferencesKey("recent_sheets")
        private val KEY_RECENT_OWNERS = stringPreferencesKey("recent_owners")
        /** Mountpoint + tên đăng nhập + mật khẩu của lần NTRIP chạy được gần nhất */
        private val KEY_OK_MOUNT       = stringPreferencesKey("ntrip_ok_mount")
        private val KEY_OK_USER        = stringPreferencesKey("ntrip_ok_user")
        private val KEY_OK_PASS        = stringPreferencesKey("ntrip_ok_pass")

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
        private val KEY_BASE_DEVICE = stringPreferencesKey("base_device")   // COMNAV_T30 / STEC / GENERIC
        private val KEY_BASE_DL     = intPreferencesKey("base_datalink")     // 0=NTRIP Server,1=Radio,2=Ngoài
        private val KEY_BASE_OUTPORT= stringPreferencesKey("base_out_port")  // COM2...
        private val KEY_BASE_NHOST  = stringPreferencesKey("base_ntrip_host")
        private val KEY_BASE_NPORT  = intPreferencesKey("base_ntrip_port")
        private val KEY_BASE_NMOUNT = stringPreferencesKey("base_ntrip_mount")
        private val KEY_BASE_NPASS  = stringPreferencesKey("base_ntrip_pass")
        private val KEY_BASE_RPROTO = stringPreferencesKey("base_radio_proto")
        private val KEY_BASE_RFREQ  = stringPreferencesKey("base_radio_freq")
        private val KEY_BASE_RBAUD  = intPreferencesKey("base_radio_baud")

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
            password           = (prefs[KEY_NTRIP_PASSWORD] ?: "").let { luu ->
                secureStringCipher.decryptOrNull(luu) ?: run {
                    // Có bản mã trong máy nhưng KHÔNG giải được -> khoá Keystore
                    // đã bị tạo lại. Trả rỗng lặng lẽ thì caster trả 401 và
                    // người đo ngồi nhìn SINGLE mà không hiểu vì sao.
                    android.util.Log.e(
                        "NtripClient",
                        "🔴 MẬT KHẨU NTRIP KHÔNG GIẢI MÃ ĐƯỢC (khoá Keystore đã đổi — " +
                        "thường do gỡ/cài lại app hoặc xoá dữ liệu app). " +
                        "Caster sẽ trả 401 Unauthorized. " +
                        "KHẮC PHỤC: vào Thiết bị ▸ NTRIP nhập lại mật khẩu rồi Lưu."
                    )
                    ""
                }
            },
            ggaIntervalSeconds = prefs[KEY_NTRIP_GGA_INTERVAL] ?: 5
        )
    }

    /** Lưu cấu hình NTRIP mới. */
    // ── Ghi nhớ IP điện thoại (cho cảnh báo đổi IP) ─────────
    val lastPhoneIpFlow: Flow<String> = context.dataStore.data.map { it[KEY_LAST_PHONE_IP] ?: "" }

    // ── Thông tin đơn vị đo đạc cho biên bản ────────────────
    /** Nhập MỘT LẦN trong Cài đặt, mọi biên bản sau tự điền sẵn. */
    data class ReportSettings(
        val donViTen     : String = "",
        val donViDaiDien : String = "",
        val donViChucVu  : String = "",
        val donViDiaChi  : String = "",
        val donViVpdd    : String = "",
        val noiCapGcn    : String = "Văn phòng đăng ký đất đai tỉnh Bà Rịa - Vũng Tàu"
    )

    val reportSettingsFlow: Flow<ReportSettings> = context.dataStore.data.map { p ->
        ReportSettings(
            donViTen     = p[KEY_RPT_TEN] ?: "",
            donViDaiDien = p[KEY_RPT_DAIDIEN] ?: "",
            donViChucVu  = p[KEY_RPT_CHUCVU] ?: "",
            donViDiaChi  = p[KEY_RPT_DIACHI] ?: "",
            donViVpdd    = p[KEY_RPT_VPDD] ?: "",
            noiCapGcn    = p[KEY_RPT_NOICAP]
                ?: "Văn phòng đăng ký đất đai tỉnh Bà Rịa - Vũng Tàu"
        )
    }

    suspend fun saveReportSettings(r: ReportSettings) {
        context.dataStore.edit { p ->
            p[KEY_RPT_TEN]     = r.donViTen
            p[KEY_RPT_DAIDIEN] = r.donViDaiDien
            p[KEY_RPT_CHUCVU]  = r.donViChucVu
            p[KEY_RPT_DIACHI]  = r.donViDiaChi
            p[KEY_RPT_VPDD]    = r.donViVpdd
            p[KEY_RPT_NOICAP]  = r.noiCapGcn
        }
    }

    // ── Lịch sử tìm kiếm ────────────────────────────────────
    /** Tối đa 10 mục, mới nhất lên đầu, không trùng lặp. */
    private fun pushRecent(cur: String, item: String, max: Int = 10): String {
        val v = item.trim()
        if (v.isBlank()) return cur
        val list = cur.split('\n').filter { it.isNotBlank() && it != v }
        return (listOf(v) + list).take(max).joinToString("\n")
    }

    /** Tờ bản đồ đã mở gần đây — lưu dạng "slug|122/90". */
    val recentSheetsFlow: Flow<List<String>> = context.dataStore.data.map {
        (it[KEY_RECENT_SHEETS] ?: "").split('\n').filter { s -> s.isNotBlank() }
    }

    suspend fun addRecentSheet(entry: String) {
        context.dataStore.edit {
            it[KEY_RECENT_SHEETS] = pushRecent(it[KEY_RECENT_SHEETS] ?: "", entry)
        }
    }

    /** Tên chủ đã tìm gần đây. */
    val recentOwnersFlow: Flow<List<String>> = context.dataStore.data.map {
        (it[KEY_RECENT_OWNERS] ?: "").split('\n').filter { s -> s.isNotBlank() }
    }

    suspend fun addRecentOwner(query: String) {
        context.dataStore.edit {
            it[KEY_RECENT_OWNERS] = pushRecent(it[KEY_RECENT_OWNERS] ?: "", query)
        }
    }

    suspend fun saveLastPhoneIp(ip: String) {
        context.dataStore.edit { it[KEY_LAST_PHONE_IP] = ip }
    }

    /** Thông tin NTRIP của lần kết nối THÀNH CÔNG gần nhất (mount, user, pass). */
    val lastOkNtripFlow: Flow<Triple<String, String, String>> = context.dataStore.data.map {
        Triple(
            it[KEY_OK_MOUNT] ?: "",
            it[KEY_OK_USER] ?: "",
            secureStringCipher.decrypt(it[KEY_OK_PASS] ?: "")
        )
    }

    suspend fun saveLastOkNtrip(mount: String, user: String, pass: String) {
        context.dataStore.edit {
            it[KEY_OK_MOUNT] = mount
            it[KEY_OK_USER]  = user
            it[KEY_OK_PASS]  = secureStringCipher.encrypt(pass)
        }
    }

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
            avgSeconds    = prefs[KEY_BASE_AVG]  ?: 60,
            deviceType    = prefs[KEY_BASE_DEVICE] ?: "COMNAV_T30",
            datalinkType  = prefs[KEY_BASE_DL]     ?: 0,
            outPort       = prefs[KEY_BASE_OUTPORT]?: "COM2",
            ntripHost     = prefs[KEY_BASE_NHOST]  ?: "",
            ntripPort     = prefs[KEY_BASE_NPORT]  ?: 2101,
            ntripMount    = prefs[KEY_BASE_NMOUNT] ?: "",
            ntripPassword = prefs[KEY_BASE_NPASS]  ?: "",
            radioProtocol = prefs[KEY_BASE_RPROTO] ?: "TransparentEOT",
            radioFreq     = prefs[KEY_BASE_RFREQ]  ?: "",
            radioBaud     = prefs[KEY_BASE_RBAUD]  ?: 9600
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
            p[KEY_BASE_DEVICE] = c.deviceType
            p[KEY_BASE_DL]      = c.datalinkType
            p[KEY_BASE_OUTPORT] = c.outPort
            p[KEY_BASE_NHOST]   = c.ntripHost
            p[KEY_BASE_NPORT]   = c.ntripPort
            p[KEY_BASE_NMOUNT]  = c.ntripMount
            p[KEY_BASE_NPASS]   = c.ntripPassword
            p[KEY_BASE_RPROTO]  = c.radioProtocol
            p[KEY_BASE_RFREQ]   = c.radioFreq
            p[KEY_BASE_RBAUD]   = c.radioBaud
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
        val avgSeconds    : Int    = 60,
        /** Loại máy làm base: COMNAV_T30 / STEC / GENERIC (để hướng dẫn/lệnh theo thiết bị) */
        val deviceType    : String = "COMNAV_T30",
        // ── Datalink: cách base phát cải chính ──
        /** 0=NTRIP Server, 1=Radio UHF, 2=Ngoài/khác */
        val datalinkType  : Int    = 0,
        /** Cổng máy phát RTCM (radio/serial), vd COM2 — dùng trong lệnh LOG của ComNav */
        val outPort       : String = "COM2",
        // NTRIP Server (base đẩy RTCM lên caster)
        val ntripHost     : String = "",
        val ntripPort     : Int    = 2101,
        val ntripMount    : String = "",
        val ntripPassword : String = "",
        // Radio UHF (base & rover PHẢI trùng 3 thông số này)
        val radioProtocol : String = "TransparentEOT",
        val radioFreq     : String = "",      // tần số/kênh (MHz)
        val radioBaud     : Int    = 9600     // air baud
    )
}
