package com.hien.rtkmultidevice.ui.screens.baseconfig

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.connection.ConnectionManager
import com.hien.rtkmultidevice.core.network.ReceiverWebControl
import com.hien.rtkmultidevice.core.network.WifiInfoHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.data.datastore.AppSettings
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BaseConfigViewModel — cấu hình Máy trạm (Base).
 * Lưu định nghĩa base (chế độ vị trí + toạ độ WGS-84 + chiều cao anten) để tham chiếu và
 * hiển thị toạ độ (WGS-84/VN-2000) người dùng nhập vào web cấu hình của máy thu.
 */
@HiltViewModel
class BaseConfigViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val gnssManager: GnssDataManager,
    private val connectionManager: ConnectionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val gnss: StateFlow<GnssStatus> = gnssManager.gnssStatus

    private val _config = MutableStateFlow(AppSettings.BaseConfig())
    val config: StateFlow<AppSettings.BaseConfig> = _config.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()
    fun clearFeedback() { _feedback.value = null }

    init {
        viewModelScope.launch { appSettings.baseConfigFlow.collect { _config.value = it } }
    }

    /** Cập nhật cục bộ (chưa lưu). */
    fun update(c: AppSettings.BaseConfig) { _config.value = c }

    /** Lưu vĩnh viễn. */
    fun save() {
        viewModelScope.launch {
            appSettings.saveBaseConfig(_config.value)
            _feedback.value = "Đã lưu cấu hình Base"
        }
    }

    /** Lấy vị trí hiện tại (WGS-84) làm base — cần đang có fix. */
    fun captureCurrent() {
        val g = gnssManager.gnssStatus.value
        if (g.fixQuality <= 0) { _feedback.value = "Chưa có định vị (cần Fixed/Single) để lấy vị trí"; return }
        _config.value = _config.value.copy(lat = g.latitude, lon = g.longitude, ellHeight = g.altitude)
        _feedback.value = "Đã lấy vị trí hiện tại (${g.fixLabel})"
    }

    /** Nhập theo VN-2000 (N,E) + độ cao ellipsoid h -> chuyển WGS-84 để lưu. */
    fun setFromVn2000(n: Double, e: Double, h: Double, cm: Double = VectorLayerImporter.DEFAULT_CM) {
        val geo = VectorLayerImporter.inverseVn2000(n, e, cm)
        if (geo == null) { _feedback.value = "Toạ độ VN-2000 không hợp lệ"; return }
        _config.value = _config.value.copy(lat = geo.latitude, lon = geo.longitude, ellHeight = h)
        _feedback.value = "Đã đặt toạ độ base từ VN-2000"
    }

    /** Chuỗi lệnh sẽ gửi (để hiển thị/preview). Rỗng nếu thiết bị không dùng lệnh. */
    fun previewCommands(): List<String> = BaseDevice.from(_config.value.deviceType).commands(_config.value)

    /**
     * Gửi chuỗi lệnh cấu hình xuống thiết bị đang kết nối (Bluetooth/TCP).
     * Mỗi lệnh 1 dòng, kết thúc CR/LF, cách nhau 150ms để máy kịp xử lý.
     * CẢNH BÁO: lệnh có SAVECONFIG sẽ ghi vào máy — chỉ gửi khi chắc chắn.
     */
    fun sendCommandsToDevice() {
        val cfg = _config.value
        val device = BaseDevice.from(cfg.deviceType)
        val cmds = device.commands(cfg)
        if (cmds.isEmpty()) {
            _feedback.value = "Thiết bị ${device.displayName} không dùng lệnh — cấu hình theo hướng dẫn"
            return
        }
        val conn = connectionManager.getActiveConnection()
        if (conn == null) {
            _feedback.value = "Chưa kết nối thiết bị — vào Kết nối để nối máy trước"
            return
        }
        viewModelScope.launch {
            var ok = 0
            try {
                for (c in cmds) {
                    conn.sendBytes((c + "\r\n").toByteArray(Charsets.US_ASCII))
                    ok++
                    delay(150)
                }
                _feedback.value = "Đã gửi $ok/${cmds.size} lệnh xuống ${device.displayName}"
            } catch (t: Throwable) {
                _feedback.value = "Lỗi gửi lệnh sau $ok/${cmds.size} dòng: ${t.message}"
            }
        }
    }

    /**
     * KHỞI ĐỘNG LẠI máy thu bằng lệnh RESET — thay cho việc phải bấm nút nguồn.
     * Hữu ích khi máy kẹt (VD cổng TCP treo, không nhận kết nối mới).
     */
    fun restartDevice() {
        val device = BaseDevice.from(_config.value.deviceType)
        sendRaw(device.restartCommands(),
            emptyMsg = "${device.displayName} không hỗ trợ khởi động lại bằng lệnh — dùng nút nguồn hoặc trang web của máy",
            okMsg    = "Đã gửi lệnh khởi động lại — máy sẽ mất kết nối ~30 giây rồi tự hoạt động lại",
            failMsg  = "Đã gửi lệnh (kết nối ngắt — máy đang khởi động lại)")
    }

    /**
     * Đặt lại bộ lọc RTK — dùng khi rover kẹt FLOAT mãi không lên FIXED.
     * Không khởi động lại máy, không mất kết nối.
     */
    fun resetRtkFilter() {
        val device = BaseDevice.from(_config.value.deviceType)
        sendRaw(device.rtkResetCommands(),
            emptyMsg = "${device.displayName} không hỗ trợ đặt lại RTK bằng lệnh",
            okMsg    = "Đã đặt lại tính toán RTK — chờ máy dò lại lời giải",
            failMsg  = "Không gửi được lệnh đặt lại RTK")
    }

    // ── Điều khiển qua WiFi (dùng chính lệnh của trang web máy thu) ──
    /** true nếu điện thoại đang trong mạng WiFi của máy thu (có thể gọi lệnh web). */
    val webControlAvailable: Boolean get() = WifiInfoHelper.gatewayIp(context) != null

    /** TẮT NGUỒN máy thu qua WiFi — tương đương nút Turn Off Receiver trên trang web. */
    fun powerOffViaWeb() = webCall("tắt nguồn") { host -> ReceiverWebControl.powerOff(host) }

    /** KHỞI ĐỘNG LẠI máy thu qua WiFi — tương đương nút Reboot Receiver. */
    fun rebootViaWeb() = webCall("khởi động lại") { host -> ReceiverWebControl.reboot(host) }

    private fun webCall(action: String, block: suspend (String) -> Result<String>) {
        val host = WifiInfoHelper.gatewayIp(context)
        if (host == null) {
            _feedback.value = "Chưa nối WiFi của máy thu — không gửi được lệnh $action"
            return
        }
        viewModelScope.launch {
            _feedback.value = "Đang gửi lệnh $action..."
            block(host)
                .onSuccess { _feedback.value = "Đã gửi lệnh $action tới máy thu" }
                .onFailure { e ->
                    // Máy tắt/khởi động lại thường cắt kết nối ngay → coi là thành công
                    val msg = e.message.orEmpty()
                    _feedback.value = when {
                        // Không nối được từ đầu → máy đang tắt hoặc đang khởi động lại
                        msg.contains("failed to connect", true) ||
                        msg.contains("ECONNREFUSED", true) ||
                        msg.contains("unreachable", true) ->
                            "Không liên lạc được với máy thu. Máy đang tắt hoặc đang khởi động lại? " +
                            "Bật máy, chờ nối lại WiFi rồi thử lại."
                        // Gửi được rồi mới đứt → lệnh đã tới máy
                        msg.contains("timeout", true) || msg.contains("reset", true) ||
                        msg.contains("closed", true) || msg.contains("EOF", true) ->
                            "Đã gửi lệnh $action (máy ngắt kết nối — nhiều khả năng đã nhận lệnh)"
                        else -> "Lỗi gửi lệnh $action: $msg"
                    }
                }
        }
    }

    /** Gửi một nhóm lệnh thô xuống máy, mỗi lệnh 1 dòng CR/LF. */
    private fun sendRaw(cmds: List<String>, emptyMsg: String, okMsg: String, failMsg: String) {
        if (cmds.isEmpty()) { _feedback.value = emptyMsg; return }
        val conn = connectionManager.getActiveConnection()
        if (conn == null) { _feedback.value = "Chưa kết nối máy thu"; return }
        viewModelScope.launch {
            runCatching {
                for (c in cmds) {
                    conn.sendBytes((c + "\r\n").toByteArray(Charsets.US_ASCII))
                    delay(200)
                }
            }.onSuccess { _feedback.value = okMsg }
                .onFailure { _feedback.value = failMsg }
        }
    }

    /** WGS-84 hiện lưu -> VN-2000 (N,E) để hiển thị. null nếu chưa có toạ độ. */
    fun toVn2000(): Pair<Double, Double>? {
        val c = _config.value
        if (c.lat == 0.0 && c.lon == 0.0) return null
        return VectorLayerImporter.wgs84ToVn2000(c.lat, c.lon, VectorLayerImporter.DEFAULT_CM)
    }
}
