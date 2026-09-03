package com.hien.rtkmultidevice.core.gnss

import android.content.Context
import android.util.Log
import com.hien.rtkmultidevice.core.connection.ConnectionManager
import com.hien.rtkmultidevice.core.coordinate.Vn2000Converter
import com.hien.rtkmultidevice.core.gnss.nmea.GgaData
import com.hien.rtkmultidevice.core.gnss.nmea.GsaData
import com.hien.rtkmultidevice.core.gnss.nmea.NmeaParser
import com.hien.rtkmultidevice.core.gnss.ntrip.NtripClient
import com.hien.rtkmultidevice.core.gnss.ntrip.NtripConfig
import com.hien.rtkmultidevice.core.gnss.ntrip.NtripProxyServer
import com.hien.rtkmultidevice.core.network.ReceiverWebControl
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
import com.hien.rtkmultidevice.core.network.WifiInfoHelper
import com.hien.rtkmultidevice.data.datastore.AppSettings
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.NtripState
import com.hien.rtkmultidevice.domain.model.SatelliteInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.net.ConnectivityManager
import android.net.LinkProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GnssDataManager — Singleton điều phối toàn bộ luồng dữ liệu GNSS.
 *
 * Trách nhiệm:
 *   1. Subscribe vào NMEA Flow từ ConnectionManager
 *   2. Parse từng câu NMEA → cập nhật GnssStatus
 *   3. Tích lũy nhiều câu GSV → danh sách vệ tinh hoàn chỉnh
 *   4. Xử lý RMC (speed/course/date) và VTG (speed chính xác hơn)
 *   5. Quản lý NtripClient (kết nối, nhận RTCM, gửi GGA định kỳ)
 *   6. Forward RTCM đến thiết bị qua ConnectionManager
 *
 * Đây là "trung tâm xử lý dữ liệu" của app — giống như bộ xử lý
 * tín hiệu trong máy RTK: nhận raw data, tính toán, phân phối kết quả.
 *
 * @Singleton → chỉ một instance, inject vào nhiều ViewModel
 */
@Singleton
class GnssDataManager @Inject constructor(
    private val connectionManager  : ConnectionManager,
    private val appSettings        : AppSettings,
    @ApplicationContext private val context: Context   // để NtripClient dùng cellular socket
) {
    companion object {
        private const val TAG = "GnssDataManager"
        /** Quá thời gian này (ms) không nhận được câu NMEA nào → coi như mất tín hiệu. */
        private const val NMEA_TIMEOUT_MS = 4000L
    }
    // ── Scope riêng cho GnssDataManager ─────────────────────
    // SupervisorJob: nếu một job con fail, không ảnh hưởng job khác
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── State Flows ──────────────────────────────────────────
    private val _gnssStatus = MutableStateFlow<GnssStatus>(GnssStatus.NoFix)
    val gnssStatus: StateFlow<GnssStatus> = _gnssStatus.asStateFlow()

    private val _latestGga = MutableStateFlow<GgaData?>(null)
    val latestGga: StateFlow<GgaData?> = _latestGga.asStateFlow()

    private val _latestGsa = MutableStateFlow<GsaData?>(null)
    val latestGsa: StateFlow<GsaData?> = _latestGsa.asStateFlow()

    private val _ntripState = MutableStateFlow<NtripState>(NtripState.Disconnected)
    val ntripState: StateFlow<NtripState> = _ntripState.asStateFlow()

    // ── GSV Accumulation Buffer ──────────────────────────────
    // Key: Constellation → Map<PRN, SatelliteInfo>
    // Tích lũy dữ liệu vệ tinh theo từng chu kỳ GSV (có thể nhiều câu)
    private val gsvBuffer = mutableMapOf<SatelliteInfo.Constellation, MutableMap<Int, SatelliteInfo>>()

    // Theo dõi message đang đọc của mỗi constellation
    // Key: Constellation, Value: số câu đã nhận trong chu kỳ hiện tại
    private val gsvReceivedCount = mutableMapOf<SatelliteInfo.Constellation, Int>()
    private val gsvTotalExpected = mutableMapOf<SatelliteInfo.Constellation, Int>()

    // ── GSA — Satellite IDs đang được dùng ──────────────────
    // Lưu tạm PRN đang active để đánh dấu isUsed trong SatelliteInfo
    private val usedPrnSet = mutableSetOf<Int>()

    // ── Raw GGA string để gửi NTRIP ─────────────────────────
    // Lưu câu GGA gốc (chưa parse) để gửi định kỳ đến NTRIP caster
    private var lastRawGga: String? = null

    // ── Cài đặt toạ độ (cập nhật từ AppSettings Flow) ───────
    private var coordSettings = AppSettings.CoordSettings()

    // ── Jobs quản lý coroutines ──────────────────────────────
    private var nmeaJob: Job? = null
    private var ntripJob: Job? = null
    private var ntripProxyJob: Job? = null
    private var ggaSenderJob: Job? = null
    private var watchdogJob: Job? = null
    private var batteryJob: Job? = null

    // ── Dự phòng: GPS điện thoại khi chưa có máy thu RTK ──
    private val phoneGps by lazy { PhoneGpsFallback(context) }
    private var phoneGpsJob: Job? = null

    /** true khi vị trí đang lấy từ chip GPS điện thoại (không phải máy thu). */
    private val _usingPhoneGps = MutableStateFlow(false)
    val usingPhoneGps: StateFlow<Boolean> = _usingPhoneGps.asStateFlow()

    /** Chu kỳ đọc pin máy thu (ms). Pin đổi chậm nên không cần đọc dày. */
    private val BATTERY_POLL_MS = 60_000L
    /** Thời điểm nhận câu NMEA gần nhất — cho watchdog phát hiện mất tín hiệu. */
    @Volatile private var lastNmeaTimeMs: Long = 0L
    private var ntripClient: NtripClient? = null
    private var ntripProxyServer: NtripProxyServer? = null
    private var rtcmBytesForwarded: Long = 0L
    private var rtcmPacketsForwarded: Int = 0
    private var rtcmForwardError: String? = null

    // ────────────────────────────────────────────────────────
    // NMEA Processing
    // ────────────────────────────────────────────────────────

    /**
     * Bắt đầu lắng nghe NMEA từ kết nối đang hoạt động.
     * Đồng thời subscribe cài đặt toạ độ để dùng múi chiếu mới nhất.
     * Gọi khi kết nối BT/TCP thành công.
     */
    fun startNmeaProcessing() {
        nmeaJob?.cancel()
        nmeaJob = managerScope.launch {
            // Subscribe cài đặt toạ độ — cập nhật ngay khi user thay đổi
            launch {
                appSettings.coordSettingsFlow.collect { settings ->
                    coordSettings = settings
                }
            }

            val connection = connectionManager.getActiveConnection() ?: return@launch

            // Watchdog: nếu quá NMEA_TIMEOUT_MS không nhận được câu NMEA nào
            // (đầu thu tắt nguồn / mất kết nối) → tự đặt NO FIX, tránh "đóng băng"
            // trạng thái Fixed cũ làm user tưởng vẫn còn RTK.
            lastNmeaTimeMs = System.currentTimeMillis()
            startNmeaWatchdog()
            startBatteryPolling()

            connection.nmeaFlow().collect { sentence ->
                lastNmeaTimeMs = System.currentTimeMillis()
                // Có dữ liệu máy thu → nhường quyền, tắt GPS điện thoại
                if (phoneGps.running) {
                    stopPhoneGpsFallback()
                    _gnssStatus.value = _gnssStatus.value.copy(isPhoneGps = false)
                }
                processNmeaSentence(sentence)
            }
        }
    }

    /**
     * Đọc dung lượng pin máy thu định kỳ (xem BATTERY_POLL_MS — mặc định 60 giây).
     *
     * Dùng chính lệnh của trang web máy (power_status_get.cmd) qua WiFi.
     * Pin đổi chậm nên không cần poll dày — tránh hao pin điện thoại.
     * Chỉ chạy khi đang trong mạng WiFi của máy thu; nối Bluetooth thì bỏ qua.
     */
    private fun startBatteryPolling() {
        batteryJob?.cancel()
        batteryJob = managerScope.launch {
            while (true) {
                val host = WifiInfoHelper.gatewayIp(context)
                if (host != null) {
                    val pct = runCatching {
                        ReceiverWebControl.fetchBattery(context, host)
                    }.getOrNull()
                    if (pct != null && pct != _gnssStatus.value.batteryPercent) {
                        _gnssStatus.value = _gnssStatus.value.copy(batteryPercent = pct)
                        Log.d(TAG, "Pin máy thu: $pct%")
                    }
                }
                delay(BATTERY_POLL_MS)
            }
        }
    }

    /**
     * Bật GPS ĐIỆN THOẠI làm nguồn vị trí dự phòng.
     *
     * Gọi khi app chạy offline / chưa nối máy thu: bản đồ vẫn biết đang ở đâu,
     * tra được "tôi đang ở thửa nào". Độ chính xác vài mét — CHỈ để định vị,
     * KHÔNG dùng để đo. Có tín hiệu RTK là tự nhường chỗ ngay.
     */
    fun startPhoneGpsFallback() {
        if (phoneGps.running) return
        // Nạp cài đặt toạ độ ĐỘC LẬP với kết nối máy thu — trước đây coordSettings
        // chỉ được nạp trong startNmeaProcessing(), chưa nối máy thì vẫn là mặc định.
        managerScope.launch {
            appSettings.coordSettingsFlow.collect { coordSettings = it }
        }
        phoneGps.start { lat, lon, alt, acc ->
            // Có NMEA thật trong 5 giây gần đây → bỏ qua, máy thu ưu tiên tuyệt đối
            if (System.currentTimeMillis() - lastNmeaTimeMs < 5_000L) return@start

            val settings = coordSettings
            val vn = if (settings.zoneWidthDeg == 6)
                Vn2000Converter.convert6Deg(lat, lon, alt, settings.centralMeridianOverride)
            else
                Vn2000Converter.convert(lat, lon, alt, settings.centralMeridianOverride)

            _usingPhoneGps.value = true
            _gnssStatus.value = _gnssStatus.value.copy(
                latitude = lat, longitude = lon, altitude = alt,
                // fixQuality=1 để phần định vị/bản đồ coi là "có vị trí",
                // nhưng isPhoneGps=true để hiển thị "GPS ĐT" chứ KHÔNG phải SINGLE.
                fixQuality = 1,
                isPhoneGps = true,
                satelliteCount = 0,
                hAccuracy = acc.toDouble(),     // độ chính xác do Android báo
                vAccuracy = acc.toDouble() * 1.5,
                vn2000 = vn
            )
        }
    }

    fun stopPhoneGpsFallback() {
        phoneGps.stop()
        _usingPhoneGps.value = false
    }

    /** Giám sát luồng NMEA — mất tín hiệu quá lâu thì hạ về NO FIX. */
    private fun startNmeaWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = managerScope.launch {
            while (true) {
                delay(1000L)
                val silentMs = System.currentTimeMillis() - lastNmeaTimeMs
                // ĐANG dùng GPS điện thoại thì KHÔNG hạ về NO FIX —
                // trước đây watchdog xoá sạch vị trí dự phòng mỗi giây.
                if (phoneGps.running) continue
                if (silentMs > NMEA_TIMEOUT_MS && _gnssStatus.value.fixQuality != 0) {
                    Log.w(TAG, "⚠ Mất tín hiệu NMEA ${silentMs}ms → đặt NO FIX (đầu thu tắt/ngắt?)")
                    _gnssStatus.value = _gnssStatus.value.copy(
                        fixQuality     = 0,
                        satelliteCount = 0
                    )
                }
            }
        }
    }

    /**
     * Xử lý từng dòng NMEA.
     *
     * Thứ tự ưu tiên cập nhật GnssStatus:
     *   GGA → vị trí + fix quality (quan trọng nhất)
     *   GSA → DOP + danh sách PRN đang dùng
     *   GSV → danh sách vệ tinh nhìn thấy (cập nhật sau chu kỳ hoàn chỉnh)
     *   RMC → speed + course + date
     *   VTG → speed + course chính xác hơn (ưu tiên hơn RMC)
     */
    private fun processNmeaSentence(sentence: String) {
        // Log câu NMEA proprietary ($P...) — quan trọng để hiểu giao thức thiết bị
        if (sentence.startsWith("\$P")) {
            Log.d(TAG, "NMEA proprietary từ thiết bị: $sentence")
        }
        // Log GGA raw để phân tích dual-GGA pattern
        if (sentence.contains("GGA")) {
            Log.d(TAG, "NMEA GGA raw: $sentence")
        }
        when {
            // ── GGA: Vị trí + Fix quality ──────────────────
            sentence.contains("GGA") -> {
                NmeaParser.parseGga(sentence)?.let { gga ->
                    // ── Lọc "null GGA" mà nhiều thiết bị phát xen kẽ với GGA thật ──
                    // Nhận biết: fix=0, sats≤1, hdop>10 — không phải mất lock thật
                    // Nếu bỏ qua, UI sẽ ổn định thay vì nhấp nháy SINGLE↔NO FIX
                    if (gga.fixQuality == 0 && gga.satelliteCount <= 1 && gga.hdop > 10.0) {
                        Log.d(TAG, "GGA null/placeholder bị bỏ qua (sats=${gga.satelliteCount} hdop=${gga.hdop}) → giữ trạng thái hiện tại")
                        return@let
                    }

                    // 🔴 BỎ GGA CŨ TỒN ĐỌNG TRONG ĐỆM — trả giá 03/09.
                    //
                    // Lúc vừa mở kết nối, đệm Bluetooth có thể còn nguyên một
                    // xâu câu NMEA cũ. Log máy STEC: 12 câu giờ 00:56:33–00:56:43
                    // đổ về trong 450 ms rồi mới nhảy sang 01:26:11 — tức app đã
                    // đổi ra VN-2000 và hiển thị 12 vị trí CŨ 30 PHÚT như đang
                    // sống. Bấm "Đo điểm" đúng lúc đó là ghi nhầm toạ độ cũ.
                    //
                    // Dấu hiệu nhận biết chắc chắn: GIỜ ĐI LÙI. Máy thu chạy
                    // bình thường không bao giờ gửi giờ nhỏ hơn câu trước.
                    // Chừa 86.000 s cho trường hợp qua nửa đêm (giờ quay vòng).
                    val tMoi = giayUtc(gga.utcTime)
                    val tCu  = giayUtc(_latestGga.value?.utcTime ?: "")
                    if (tMoi >= 0 && tCu >= 0 && tMoi < tCu - 2 && (tCu - tMoi) < 86_000) {
                        Log.w(TAG, "Bỏ GGA tồn đọng: giờ ${gga.utcTime} đi LÙI so với " +
                                   "${_latestGga.value?.utcTime} (${"%.0f".format(tCu - tMoi)} s)")
                        return@let
                    }

                    lastRawGga = sentence          // lưu để gửi NTRIP
                    _latestGga.value = gga

                    // Log khi fixQuality thay đổi — quan trọng để xác nhận RTK Fixed
                    val prevQuality = _gnssStatus.value.fixQuality
                    if (gga.fixQuality != prevQuality) {
                        val label = when (gga.fixQuality) {
                            0 -> "NO FIX"
                            1 -> "SINGLE (GPS)"
                            2 -> "DGPS"
                            4 -> "RTK FIXED ✅"
                            5 -> "RTK FLOAT 🔶"
                            6 -> "Dead Reckoning"
                            else -> "fixQuality=${gga.fixQuality}"
                        }
                        Log.d(TAG, "══ fixQuality thay đổi: $prevQuality → ${gga.fixQuality}  ($label)  sats=${gga.satelliteCount}  hdop=${gga.hdop} ══")
                    }

                    // Trừ CHIỀU CAO ANTEN -> cao độ mặt đất. Chỉ ảnh hưởng CAO ĐỘ (h),
                    // gần như KHÔNG ảnh hưởng toạ độ ngang N/E (sai lệch < 1 mm).
                    val groundAlt = gga.altitude - coordSettings.antennaHeight

                    // Tính VN-2000 ngay khi có vị trí mới
                    val vn2000 = if (gga.fixQuality > 0) {
                        val settings = coordSettings
                        val result = if (settings.zoneWidthDeg == 6)
                            Vn2000Converter.convert6Deg(
                                lat                    = gga.latitude,
                                lon                    = gga.longitude,
                                h                      = groundAlt,
                                centralMeridianOverride = settings.centralMeridianOverride
                            )
                        else
                            Vn2000Converter.convert(
                                lat                    = gga.latitude,
                                lon                    = gga.longitude,
                                h                      = groundAlt,
                                centralMeridianOverride = settings.centralMeridianOverride
                            )
                        // Hiệu chỉnh về mốc chuẩn (tịnh tiến ΔN/ΔE) nếu được bật
                        val adjusted = if (result != null && settings.calibEnabled)
                            result.copy(
                                northing = result.northing + settings.calibN,
                                easting  = result.easting  + settings.calibE
                            )
                        else result
                        // LOG để debug Stakeout distance
                        Log.d(TAG, "VN2000: lat=${gga.latitude} lon=${gga.longitude} " +
                              "zone=${settings.zoneWidthDeg}° CM=${settings.centralMeridianOverride} " +
                              "calib=${settings.calibEnabled}(ΔN=${settings.calibN},ΔE=${settings.calibE}) " +
                              "→ N=${adjusted?.northing} E=${adjusted?.easting}")
                        adjusted
                    } else null

                    // ── ĐỒNG BỘ vị trí hiển thị với VN-2000 ĐÃ HIỆU CHỈNH ──
                    // Trước đây lat/lon giữ nguyên giá trị THÔ trong khi vn2000 đã cộng ΔN/ΔE,
                    // nên trên bản đồ con trỏ máy thu lệch so với LỚP VECTOR đúng bằng lượng
                    // hiệu chỉnh — dẫn tới "bản đồ và điểm stakeout lệch nhau".
                    // Chuyển ngược VN-2000 đã hiệu chỉnh về lat/lon để MỌI thứ dùng chung
                    // một hệ quy chiếu: marker, lớp vector, khoảng cách stakeout.
                    var dispLat = gga.latitude
                    var dispLon = gga.longitude
                    if (vn2000 != null && coordSettings.calibEnabled &&
                        (coordSettings.calibN != 0.0 || coordSettings.calibE != 0.0)
                    ) {
                        val cm = coordSettings.centralMeridianOverride
                            ?: VectorLayerImporter.DEFAULT_CM
                        VectorLayerImporter.inverseVn2000(vn2000.northing, vn2000.easting, cm)
                            ?.let { g -> dispLat = g.latitude; dispLon = g.longitude }
                    }

                    // Giữ nguyên speed/course/date/satellites từ state cũ
                    _gnssStatus.value = _gnssStatus.value.copy(
                        latitude        = dispLat,
                        longitude       = dispLon,
                        altitude        = groundAlt,
                        fixQuality      = gga.fixQuality,
                        satelliteCount  = gga.satelliteCount,
                        hdop            = gga.hdop,
                        utcTime         = GnssStatus.formatTime(gga.utcTime),
                        geoidSeparation = gga.geoidSeparation,
                        vn2000          = vn2000
                    )
                }
            }

            // ── GSA: DOP + danh sách vệ tinh đang dùng ─────
            sentence.contains("GSA") -> {
                NmeaParser.parseGsa(sentence)?.let { gsa ->
                    _latestGsa.value = gsa
                    // Cập nhật usedPrnSet để đánh dấu isUsed trong GSV
                    usedPrnSet.addAll(gsa.satelliteIds)
                    // Cập nhật PDOP/VDOP vào GnssStatus (VDOP dùng để ước lượng V)
                    _gnssStatus.value = _gnssStatus.value.copy(
                        pdop = gsa.pdop,
                        vdop = gsa.vdop
                    )
                }
            }

            // ── GST: sai số thực tế H/V do máy thu ước lượng ─
            sentence.contains("GST") -> {
                NmeaParser.parseGst(sentence)?.let { gst ->
                    _gnssStatus.value = _gnssStatus.value.copy(
                        hAccuracy = gst.horizontalAccuracy,
                        vAccuracy = gst.verticalAccuracy
                    )
                }
            }

            // ── GSV: Vệ tinh nhìn thấy (tích lũy nhiều câu) ─
            sentence.contains("GSV") -> {
                NmeaParser.parseGsv(sentence)?.let { gsv ->
                    val constellation = gsv.constellation

                    // Khởi tạo buffer nếu đây là câu đầu tiên của chu kỳ
                    if (gsv.messageNumber == 1) {
                        gsvBuffer[constellation] = mutableMapOf()
                        gsvReceivedCount[constellation] = 0
                        gsvTotalExpected[constellation] = gsv.totalMessages
                    }

                    // Tích lũy vệ tinh vào buffer (key = PRN)
                    gsv.satellites.forEach { sat ->
                        gsvBuffer[constellation]?.put(
                            sat.prn,
                            sat.copy(isUsed = sat.prn in usedPrnSet)
                        )
                    }
                    gsvReceivedCount[constellation] =
                        (gsvReceivedCount[constellation] ?: 0) + 1

                    // Nếu đây là câu cuối → cập nhật danh sách vệ tinh
                    if (gsv.isLastMessage) {
                        publishSatelliteList()
                    }
                }
            }

            // ── RMC: Speed + Course + Date ──────────────────
            sentence.contains("RMC") -> {
                NmeaParser.parseRmc(sentence)?.let { rmc ->
                    if (!rmc.isValid) return@let      // bỏ qua nếu status = 'V'
                    _gnssStatus.value = _gnssStatus.value.copy(
                        speedKmh    = rmc.speedKmh,
                        courseTrue  = rmc.courseTrue,
                        date        = rmc.dateFormatted
                    )
                }
            }

            // ── VTG: Speed chính xác hơn (ưu tiên hơn RMC) ─
            sentence.contains("VTG") -> {
                NmeaParser.parseVtg(sentence)?.let { vtg ->
                    if (vtg.mode == 'N') return@let  // mode='N' = not valid
                    _gnssStatus.value = _gnssStatus.value.copy(
                        speedKmh   = vtg.speedKmh,
                        courseTrue = vtg.courseTrue
                    )
                }
            }
        }
    }

    /**
     * Gộp tất cả vệ tinh từ buffer và cập nhật vào GnssStatus.
     * Được gọi sau khi nhận đủ tất cả câu GSV của một constellation.
     *
     * Vì nhiều constellation có thể gửi GSV độc lập,
     * ta gộp toàn bộ buffer thành một list duy nhất.
     */
    private fun publishSatelliteList() {
        val allSatellites = gsvBuffer.values
            .flatMap { it.values }
            .sortedWith(
                compareBy(
                    { it.constellation.ordinal },  // nhóm theo constellation
                    { it.prn }                     // sắp xếp theo PRN
                )
            )

        _gnssStatus.value = _gnssStatus.value.copy(satellites = allSatellites)
    }

    // ────────────────────────────────────────────────────────
    // NTRIP Management
    // ────────────────────────────────────────────────────────

    /**
     * Bắt đầu kết nối NTRIP và nhận RTCM.
     * Sau khi kết nối thành công, bắt đầu gửi GGA định kỳ.
     *
     * @param config Cấu hình NTRIP từ DataStore
     */
    fun startNtrip(config: NtripConfig) {
        if (!config.isValid()) {
            _ntripState.value = NtripState.Error("Cấu hình NTRIP không hợp lệ")
            return
        }

        ntripJob?.cancel()
        ntripJob = managerScope.launch {
            ntripClient?.disconnect()
            ntripClient = NtripClient(config, context)

            // Truyền GGA vào HTTP request header "Ntrip-GGA:"
            // VRS caster cần vị trí rover ngay từ đầu để tạo correction phù hợp.
            // Nếu chưa có GGA (rover chưa có fix), connect() vẫn hoạt động —
            // caster sẽ dùng vị trí mặc định hoặc chờ GGA gửi định kỳ sau đó.
            val rtcmStream = ntripClient?.connect(initialGga = lastRawGga)

            if (rtcmStream == null) {
                _ntripState.value = ntripClient?.state?.value
                    ?: NtripState.Error("Không thể kết nối NTRIP")
                return@launch
            }

            // Cập nhật state từ NtripClient
            launch {
                ntripClient?.state?.collect { state ->
                    _ntripState.value = withForwardStats(state)
                }
            }

            // Bắt đầu gửi GGA định kỳ (theo config.ggaIntervalSeconds)
            startPeriodicGgaSender(config.ggaIntervalSeconds)

            // Đọc RTCM và forward đến thiết bị GNSS
            ntripClient?.readRtcm { rtcmBytes ->
                val connection = connectionManager.getActiveConnection()
                if (connection == null) {
                    // Log lần đầu để tránh spam
                    if (rtcmPacketsForwarded == 0 && rtcmForwardError == null) {
                        Log.e(TAG, "⚠ RTCM không thể forward: chưa có kết nối thiết bị GNSS!")
                    }
                    rtcmForwardError = "Không có kết nối thiết bị GNSS để gửi RTCM"
                    _ntripState.value = withForwardStats(ntripClient?.state?.value ?: _ntripState.value)
                    return@readRtcm
                }

                runCatching {
                    connection.sendBytes(rtcmBytes)
                }.onSuccess {
                    rtcmBytesForwarded += rtcmBytes.size
                    rtcmPacketsForwarded++
                    rtcmForwardError = null
                    // Gói RTCM ĐẦU TIÊN về được máy = cấu hình NTRIP này CHẠY ĐƯỢC.
                    // Ghi lại làm mốc để cảnh báo nếu sau này mountpoint/đăng nhập bị đổi.
                    if (rtcmPacketsForwarded == 1) {
                        managerScope.launch {
                            runCatching {
                                appSettings.saveLastOkNtrip(
                                    config.mountPoint, config.username, config.password
                                )
                            }
                        }
                    }
                    // Log định kỳ: gói đầu tiên + mỗi 50 gói tiếp
                    if (rtcmPacketsForwarded == 1 || rtcmPacketsForwarded % 50 == 0) {
                        Log.d(TAG, "RTCM → thiết bị ✓  gói #$rtcmPacketsForwarded  ${rtcmBytes.size}B  tổng=$rtcmBytesForwarded B")
                    }
                    _ntripState.value = withForwardStats(ntripClient?.state?.value ?: _ntripState.value)
                }.onFailure { error ->
                    Log.e(TAG, "RTCM forward THẤT BẠI: ${error.message}  (gói #${rtcmPacketsForwarded + 1})")
                    rtcmForwardError = error.message ?: "Không gửi được RTCM về thiết bị"
                    _ntripState.value = withForwardStats(ntripClient?.state?.value ?: _ntripState.value)
                }
            }
        }
    }

    /**
     * Gửi câu GGA định kỳ đến NTRIP Caster.
     *
     * NTRIP Caster cần GGA để biết vị trí hiện tại của rover,
     * từ đó chọn base station phù hợp (nearest base).
     *
     * Quy tắc gửi GGA:
     *   • GGA NGAY sau khi kết nối thành công (không delay trước)
     *   • Chỉ gửi khi fixQuality > 0 (rover đang có vị trí hợp lệ)
     *   • GGA với fixQuality=0 không có tọa độ thực → VRS caster có thể
     *     ngừng gửi RTCM hoặc gửi correction sai vị trí
     *   • Sau đó gửi định kỳ theo interval (thường 5 giây)
     *
     * @param intervalSeconds Khoảng thời gian gửi định kỳ (mặc định 5 giây)
     */
    private fun startPeriodicGgaSender(intervalSeconds: Int = 5) {
        ggaSenderJob?.cancel()
        ggaSenderJob = managerScope.launch {
            // Gửi GGA ngay lập tức nếu đã có fix hợp lệ
            sendGgaIfValid()
            // Sau đó gửi định kỳ theo interval
            while (isActive) {
                delay(intervalSeconds * 1000L)
                sendGgaIfValid()
            }
        }
    }

    /**
     * Gửi GGA chỉ khi rover đang có fix thực sự (fixQuality > 0).
     *
     * fixQuality = 0 → No fix (không có tọa độ) → KHÔNG gửi
     * fixQuality = 1 → GPS fix thường
     * fixQuality = 2 → DGPS fix
     * fixQuality = 4 → RTK Fixed
     * fixQuality = 5 → RTK Float
     *
     * GGA với fixQuality=0 chứa tọa độ 0,0 (rỗng) — nếu gửi cho VRS caster,
     * caster có thể tạo correction tại vị trí sai, dẫn đến rover không bao giờ
     * đạt RTK Fixed dù đang nhận đủ RTCM.
     */
    private suspend fun sendGgaIfValid() {
        val gga = lastRawGga ?: return
        val fixQuality = _latestGga.value?.fixQuality ?: 0
        if (fixQuality > 0) {
            ntripClient?.sendGga(gga)
        }
    }

    // ────────────────────────────────────────────────────────
    // NTRIP Proxy (cho thiết bị không có SIM — Sinov M6 Pro)
    // ────────────────────────────────────────────────────────

    /**
     * Khởi động NTRIP Proxy Server trên điện thoại.
     *
     * Thiết bị Sinov/CHCNav cấu hình RTK Client để kết nối vào proxy này.
     * Proxy sẽ fetch RTCM từ caster thật qua 4G và forward cho device.
     *
     * Sau khi gọi hàm này:
     *   1. Đọc [ntripProxyServer?.getPhoneWifiIp()] → IP của điện thoại trên mạng WiFi Sinov
     *   2. Vào web interface Sinov → IO Settings → Row 1 "RTK Client" → Detail:
     *        Protocol: NTRIP v1
     *        Host: [IP điện thoại]
     *        Port: [proxyPort, mặc định 2101]
     *        Mountpoint: [mountpoint của config]
     *        (Username/Password: để trống — proxy tự thêm credentials khi kết nối caster)
     *   3. Click Connect
     *
     * @param config Cấu hình NTRIP caster thật (giống NtripConfig dùng cho NtripClient)
     * @param proxyPort Port proxy lắng nghe (mặc định 2101)
     */
    fun startNtripProxy(config: NtripConfig, proxyPort: Int = 2101) {
        if (!config.isValid()) {
            _ntripState.value = NtripState.Error("Cấu hình NTRIP không hợp lệ")
            return
        }

        // 🔴 CHẶN CHẾ ĐỘ SAI — đã trả giá 03/09 với máy STEC nối Bluetooth.
        //
        // Proxy chỉ có nghĩa khi máy thu TỰ NỐI VÀO điện thoại qua WiFi (Sinov,
        // CHCNav): điện thoại mở ServerSocket, máy thu đóng vai RTK Client.
        // Nối bằng Bluetooth thì máy thu không có đường nào nối ngược lại, và
        // điện thoại cũng không có IP WiFi — proxy bind vào 0.0.0.0 rồi ngồi
        // chờ một kết nối không bao giờ tới. Máy đứng SINGLE, log trông vẫn
        // "bình thường": "NTRIP Proxy lắng nghe tại 0.0.0.0:2101".
        //
        // Đường đúng cho Bluetooth là startNtrip(): app tự lấy RTCM qua 4G rồi
        // GHI THẲNG xuống kết nối bằng connection.sendBytes().
        val ipWifi = ntripProxyServer?.getPhoneWifiIp()
            ?: com.hien.rtkmultidevice.core.network.WifiInfoHelper.phoneIp(context)
        // "0.0.0.0" = WiFi tắt (formatIpAddress(0)); "???" = không đọc được
        // WifiManager. Cả hai đều không phải IP nối được.
        if (ipWifi.isNullOrBlank() || ipWifi.startsWith("0.") || ipWifi == "???") {
            _ntripState.value = NtripState.Error(
                "Chế độ Proxy cần điện thoại nối WiFi CỦA MÁY THU.\n" +
                "Đang nối Bluetooth (hoặc chưa có IP WiFi) — máy thu không thể " +
                "nối ngược vào điện thoại.\n\n" +
                "→ Dùng nút \"Bắt đầu NTRIP\" thay cho \"Proxy\": app lấy cải chính " +
                "qua 4G rồi gửi thẳng xuống máy qua chính đường đang kết nối."
            )
            Log.e(TAG, "Từ chối khởi động Proxy: không có IP WiFi (ip=$ipWifi). " +
                       "Bluetooth phải dùng startNtrip().")
            return
        }

        ntripProxyJob?.cancel()
        ntripProxyServer?.stop()
        ntripProxyServer = NtripProxyServer(config, context, proxyPort)

        ntripProxyJob = managerScope.launch {
            _ntripState.value = NtripState.Connecting
            Log.d("GnssDataManager", "NTRIP Proxy khởi động tại ${ntripProxyServer?.getPhoneWifiIp()}:$proxyPort")
            Log.d("GnssDataManager", "Cấu hình Sinov RTK Client → Host=${ntripProxyServer?.getPhoneWifiIp()} Port=$proxyPort Mountpoint=${config.normalizedMountPoint}")
            ntripProxyServer?.start {
                // ServerSocket đã bind → báo UI proxy đang lắng nghe
                _ntripState.value = NtripState.ProxyListening(
                    phoneIp   = ntripProxyServer?.getPhoneWifiIp() ?: "???",
                    port      = proxyPort,
                    gatewayIp = getGatewayIp()
                )
            }
        }
    }

    /**
     * "hhmmss.ss" -> số giây trong ngày. Trả -1 nếu không đọc được.
     *
     * Dùng để phát hiện GGA tồn đọng (giờ đi lùi). Không dùng đồng hồ điện
     * thoại làm mốc: điện thoại và máy thu có thể lệch giờ, mà cái cần so là
     * giờ GIỮA CÁC CÂU với nhau.
     */
    private fun giayUtc(s: String): Double {
        if (s.length < 6) return -1.0
        return runCatching {
            s.substring(0, 2).toInt() * 3600.0 +
            s.substring(2, 4).toInt() * 60.0 +
            s.substring(4).toDouble()
        }.getOrDefault(-1.0)
    }

    /** Lấy IP gateway của mạng WiFi hiện tại (= địa chỉ web interface thiết bị GNSS) */
    private fun getGatewayIp(): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp: LinkProperties? = cm?.getLinkProperties(cm.activeNetwork)
        lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress
    }.getOrNull() ?: "192.168.1.1"

    /** Lấy IP điện thoại trên WiFi Sinov để user cấu hình RTK Client */
    fun getProxyPhoneIp(): String = ntripProxyServer?.getPhoneWifiIp()
        ?: context.getSystemService(android.net.wifi.WifiManager::class.java)
            ?.let { android.text.format.Formatter.formatIpAddress(it.connectionInfo.ipAddress) }
        ?: "???"

    fun getProxyPort(): Int = ntripProxyServer?.proxyPort ?: 2101

    fun stopNtripProxy() {
        ntripProxyJob?.cancel()
        ntripProxyServer?.stop()
        ntripProxyServer = null
        _ntripState.value = NtripState.Disconnected
        Log.d("GnssDataManager", "NTRIP Proxy đã dừng")
    }

    /** Dừng NTRIP */
    fun stopNtrip() {
        ggaSenderJob?.cancel()
        ntripJob?.cancel()
        ntripClient?.disconnect()
        ntripClient = null
        rtcmBytesForwarded = 0L
        rtcmPacketsForwarded = 0
        rtcmForwardError = null
        _ntripState.value = NtripState.Disconnected
    }

    /**
     * Dừng tất cả khi ngắt kết nối thiết bị.
     * Reset về trạng thái ban đầu.
     */
    fun stopAll() {
        nmeaJob?.cancel()
        watchdogJob?.cancel()
        stopNtrip()
        stopNtripProxy()
        // Reset data
        _gnssStatus.value = GnssStatus.NoFix
        _latestGga.value = null
        _latestGsa.value = null
        lastRawGga = null
        // Xóa buffer vệ tinh
        gsvBuffer.clear()
        gsvReceivedCount.clear()
        gsvTotalExpected.clear()
        usedPrnSet.clear()
    }

    private fun withForwardStats(state: NtripState): NtripState =
        if (state is NtripState.Connected) {
            state.copy(
                bytesForwarded = rtcmBytesForwarded,
                packetsForwarded = rtcmPacketsForwarded,
                forwardError = rtcmForwardError
            )
        } else {
            state
        }
}
