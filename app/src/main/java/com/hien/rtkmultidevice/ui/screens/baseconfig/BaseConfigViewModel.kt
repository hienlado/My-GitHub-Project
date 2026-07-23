package com.hien.rtkmultidevice.ui.screens.baseconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.data.datastore.AppSettings
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val gnssManager: GnssDataManager
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

    /** WGS-84 hiện lưu -> VN-2000 (N,E) để hiển thị. null nếu chưa có toạ độ. */
    fun toVn2000(): Pair<Double, Double>? {
        val c = _config.value
        if (c.lat == 0.0 && c.lon == 0.0) return null
        return VectorLayerImporter.wgs84ToVn2000(c.lat, c.lon, VectorLayerImporter.DEFAULT_CM)
    }
}
