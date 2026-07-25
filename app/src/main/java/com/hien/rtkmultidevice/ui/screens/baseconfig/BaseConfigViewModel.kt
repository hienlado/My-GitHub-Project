package com.hien.rtkmultidevice.ui.screens.baseconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.connection.ConnectionManager
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
    private val connectionManager: ConnectionManager
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
     * TẮT NGUỒN máy thu qua lệnh (thay cho việc phải bấm nút vật lý).
     * Gửi lần lượt các biến thể lệnh; máy hiểu lệnh nào sẽ tắt theo lệnh đó.
     */
    fun powerOffDevice() {
        val device = BaseDevice.from(_config.value.deviceType)
        val cmds = device.powerOffCommands()
        if (cmds.isEmpty()) {
            _feedback.value = "${device.displayName} không hỗ trợ tắt bằng lệnh — dùng nút nguồn trên máy"
            return
        }
        val conn = connectionManager.getActiveConnection()
        if (conn == null) {
            _feedback.value = "Chưa kết nối máy thu"
            return
        }
        viewModelScope.launch {
            runCatching {
                for (c in cmds) {
                    conn.sendBytes((c + "\r\n").toByteArray(Charsets.US_ASCII))
                    delay(200)
                }
            }.onSuccess {
                _feedback.value = "Đã gửi lệnh tắt máy — kiểm tra đèn báo trên máy thu"
            }.onFailure {
                // Mất kết nối ngay sau khi gửi thường là dấu hiệu máy ĐÃ tắt
                _feedback.value = "Đã gửi lệnh tắt (kết nối ngắt — nhiều khả năng máy đã tắt)"
            }
        }
    }

    /** WGS-84 hiện lưu -> VN-2000 (N,E) để hiển thị. null nếu chưa có toạ độ. */
    fun toVn2000(): Pair<Double, Double>? {
        val c = _config.value
        if (c.lat == 0.0 && c.lon == 0.0) return null
        return VectorLayerImporter.wgs84ToVn2000(c.lat, c.lon, VectorLayerImporter.DEFAULT_CM)
    }
}
