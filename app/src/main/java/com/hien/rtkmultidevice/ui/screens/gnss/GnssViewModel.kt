package com.hien.rtkmultidevice.ui.screens.gnss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.connection.ConnectionManager
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.data.datastore.AppSettings
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.NtripState
import com.hien.rtkmultidevice.domain.model.SatelliteInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * GnssViewModel — Quản lý dữ liệu hiển thị cho GnssScreen.
 *
 * Lưu ý kiến trúc:
 *   - ViewModel KHÔNG thực hiện I/O trực tiếp
 *   - ViewModel LẮNG NGHE StateFlow từ GnssDataManager
 *   - ViewModel CHUYỂN TIẾP dữ liệu cho UI
 *
 * stateIn() chuyển Flow thành StateFlow:
 *   - SharingStarted.WhileSubscribed(5000): giữ active 5 giây
 *     sau khi subscriber cuối rời đi (tránh restart khi xoay màn hình)
 */
@HiltViewModel
class GnssViewModel @Inject constructor(
    private val gnssDataManager  : GnssDataManager,
    private val connectionManager: ConnectionManager,
    private val appSettings      : AppSettings
) : ViewModel() {

    // ── GNSS Status (tổng hợp tất cả dữ liệu NMEA) ──────────
    val gnssStatus: StateFlow<GnssStatus> = gnssDataManager.gnssStatus
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = GnssStatus.NoFix
        )

    // ── Danh sách vệ tinh (từ GSV, được cập nhật mỗi chu kỳ) ─
    val satellites: StateFlow<List<SatelliteInfo>> = gnssDataManager.gnssStatus
        .map { it.satellites }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ── Vận tốc và hướng di chuyển ───────────────────────────
    val speedKmh: StateFlow<Double> = gnssDataManager.gnssStatus
        .map { it.speedKmh }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0.0
        )

    val courseTrue: StateFlow<Double> = gnssDataManager.gnssStatus
        .map { it.courseTrue }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0.0
        )

    // ── NTRIP State ──────────────────────────────────────────
    val ntripState: StateFlow<NtripState> = gnssDataManager.ntripState
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = NtripState.Disconnected
        )

    // ── Connection State ─────────────────────────────────────
    val connectionState = connectionManager.connectionState
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = connectionManager.connectionState.value
        )

    // ── Active Project ────────────────────────────────────────
    /** ID dự án đang mở (-1 = chưa chọn dự án) */
    val activeProjectId: StateFlow<Int> = appSettings.activeProjectIdFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = -1
        )

    // ────────────────────────────────────────────────────────
    // Actions
    // ────────────────────────────────────────────────────────

    /**
     * Khởi động NTRIP với cấu hình từ DataStore.
     * Gọi khi user nhấn "Kết nối NTRIP".
     */
    fun startNtrip() {
        viewModelScope.launch {
            gnssDataManager.startNtrip(appSettings.ntripConfigFlow.first())
        }
    }

    /** Dừng NTRIP */
    fun stopNtrip() {
        gnssDataManager.stopNtrip()
    }

    /**
     * Khởi động NTRIP Proxy — dành cho thiết bị không có SIM (Sinov M6 Pro, CHCNav).
     *
     * Proxy lắng nghe TCP trên phone, thiết bị kết nối vào proxy qua WiFi.
     * Proxy fetch RTCM từ caster thật qua 4G và forward cho thiết bị.
     *
     * Sau khi gọi hàm này, đọc [proxyPhoneIp] và [proxyPort] để cấu hình Sinov RTK Client.
     */
    fun startNtripProxy() {
        viewModelScope.launch {
            gnssDataManager.startNtripProxy(appSettings.ntripConfigFlow.first())
        }
    }

    fun stopNtripProxy() {
        gnssDataManager.stopNtripProxy()
    }

    /** IP điện thoại trên WiFi Sinov — hiển thị để user cấu hình RTK Client */
    fun getProxyPhoneIp(): String = gnssDataManager.getProxyPhoneIp()
    fun getProxyPort(): Int = gnssDataManager.getProxyPort()

    /** Ngắt kết nối thiết bị + reset toàn bộ GNSS state */
    fun disconnect() {
        gnssDataManager.stopAll()
        connectionManager.disconnect()
    }
}
