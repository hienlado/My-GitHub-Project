package com.hien.rtkmultidevice.ui.screens.coordsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone
import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone.ZoneInfo
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.data.datastore.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoordSettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val gnssManager: GnssDataManager
) : ViewModel() {

    // ── Hiệu chỉnh về mốc chuẩn (tịnh tiến ΔN/ΔE) ──────────────
    private val _calibN = MutableStateFlow(0.0)
    val calibN: StateFlow<Double> = _calibN.asStateFlow()
    private val _calibE = MutableStateFlow(0.0)
    val calibE: StateFlow<Double> = _calibE.asStateFlow()
    private val _calibEnabled = MutableStateFlow(false)
    val calibEnabled: StateFlow<Boolean> = _calibEnabled.asStateFlow()
    private val _calibFeedback = MutableStateFlow<String?>(null)
    val calibFeedback: StateFlow<String?> = _calibFeedback.asStateFlow()

    // ── Chiều cao anten (mét) — trừ khỏi cao độ đo ─────────────
    private val _antennaHeight = MutableStateFlow(0.0)
    val antennaHeight: StateFlow<Double> = _antennaHeight.asStateFlow()
    fun setAntennaHeight(meters: Double) {
        _antennaHeight.value = meters
        viewModelScope.launch { appSettings.saveAntennaHeight(meters) }
    }

    /** Toạ độ VN-2000 đo được hiện tại (đã gồm hiệu chỉnh nếu đang bật). */
    fun currentMeasured(): Pair<Double, Double>? =
        gnssManager.gnssStatus.value.vn2000?.let { it.northing to it.easting }

    /**
     * Tính & lưu hiệu chỉnh từ một mốc đã biết toạ độ chuẩn (trueN, trueE).
     * Dùng vị trí ĐO hiện tại làm gốc; tự trừ hiệu chỉnh cũ (nếu đang bật)
     * để không cộng dồn.
     */
    fun computeCalibrationFromKnownMark(trueN: Double, trueE: Double) {
        val vn = gnssManager.gnssStatus.value.vn2000
        if (vn == null) { _calibFeedback.value = "Chưa có vị trí đo (cần Fixed) để hiệu chỉnh"; return }
        val rawN = vn.northing - (if (_calibEnabled.value) _calibN.value else 0.0)
        val rawE = vn.easting  - (if (_calibEnabled.value) _calibE.value else 0.0)
        val dN = trueN - rawN
        val dE = trueE - rawE
        viewModelScope.launch {
            appSettings.saveCalibration(dN, dE, true)
            _calibFeedback.value = "Đã hiệu chỉnh: ΔN=%.3f m, ΔE=%.3f m".format(dN, dE)
        }
    }

    fun setCalibEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.saveCalibration(_calibN.value, _calibE.value, enabled) }
    }

    fun clearCalibration() {
        viewModelScope.launch {
            appSettings.saveCalibration(0.0, 0.0, false)
            _calibFeedback.value = "Đã xoá hiệu chỉnh"
        }
    }

    fun clearCalibFeedback() { _calibFeedback.value = null }

    private val _zoneWidth = MutableStateFlow(3)
    val zoneWidth: StateFlow<Int> = _zoneWidth.asStateFlow()

    private val _overrideEnabled = MutableStateFlow(false)
    val overrideEnabled: StateFlow<Boolean> = _overrideEnabled.asStateFlow()

    /** ZoneInfo đang được chọn khi ghi đè */
    private val _selectedZone = MutableStateFlow(Vn2000Zone.ZONES_3DEG[4]) // 105°00'
    val selectedZone: StateFlow<ZoneInfo> = _selectedZone.asStateFlow()

    val zones3Deg: List<ZoneInfo> = Vn2000Zone.ZONES_3DEG
    val zones6Deg: List<ZoneInfo> = Vn2000Zone.ZONES_6DEG

    init { loadSettings() }

    private fun loadSettings() {
        viewModelScope.launch {
            appSettings.coordSettingsFlow.collect { settings ->
                _zoneWidth.value       = settings.zoneWidthDeg
                _overrideEnabled.value = settings.centralMeridianOverride != null
                _calibN.value          = settings.calibN
                _calibE.value          = settings.calibE
                _calibEnabled.value    = settings.calibEnabled
                _antennaHeight.value   = settings.antennaHeight
                if (settings.centralMeridianOverride != null) {
                    _selectedZone.value =
                        if (settings.zoneWidthDeg == 3)
                            Vn2000Zone.findZone3Deg(settings.centralMeridianOverride)
                        else
                            Vn2000Zone.findZone6Deg(settings.centralMeridianOverride)
                }
            }
        }
    }

    fun onZoneWidthChange(width: Int) {
        _zoneWidth.value = width
        // Reset về zone mặc định khi đổi múi
        _selectedZone.value = if (width == 3) Vn2000Zone.ZONES_3DEG[4]
                              else Vn2000Zone.ZONES_6DEG[1]
    }

    fun onOverrideEnabledChange(enabled: Boolean) { _overrideEnabled.value = enabled }

    fun onZoneSelected(zone: ZoneInfo) { _selectedZone.value = zone }

    fun saveSettings(onSaved: () -> Unit) {
        viewModelScope.launch {
            appSettings.saveCoordSettings(
                AppSettings.CoordSettings(
                    zoneWidthDeg            = _zoneWidth.value,
                    centralMeridianOverride = if (_overrideEnabled.value)
                        _selectedZone.value.centralMeridian
                    else null
                )
            )
            onSaved()
        }
    }
}
