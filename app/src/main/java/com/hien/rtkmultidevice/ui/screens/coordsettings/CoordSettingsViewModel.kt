package com.hien.rtkmultidevice.ui.screens.coordsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone
import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone.ZoneInfo
import com.hien.rtkmultidevice.data.datastore.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoordSettingsViewModel @Inject constructor(
    private val appSettings: AppSettings
) : ViewModel() {

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
                if (settings.centralMeridianOverride != null) {
                    _selectedZone.value =
                        if (settings.zoneWidthDeg == 3)
                            Vn2000Zone.findZone3Deg(settings.centralMeridianOverride)
                        else
                            Vn2000Zone.findZone6Deg(settings.centralMeridianOverride)
                }
                return@collect
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
