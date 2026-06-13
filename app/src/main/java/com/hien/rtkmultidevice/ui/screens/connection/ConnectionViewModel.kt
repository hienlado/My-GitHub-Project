package com.hien.rtkmultidevice.ui.screens.connection

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.connection.ConnectionManager
import com.hien.rtkmultidevice.core.connection.ConnectionState
import com.hien.rtkmultidevice.core.connection.bluetooth.BluetoothConnectionImpl
import com.hien.rtkmultidevice.core.connection.bluetooth.BluetoothDeviceSource
import com.hien.rtkmultidevice.core.connection.tcp.TcpConnectionImpl
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.core.gnss.NmeaVerifier
import com.hien.rtkmultidevice.core.permission.BluetoothPermissionState
import com.hien.rtkmultidevice.core.permission.PermissionManager
import com.hien.rtkmultidevice.domain.model.DeviceInfo
import com.hien.rtkmultidevice.domain.repository.IDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ConnectionViewModel — Phase 2 update.
 *
 * Bổ sung so với Phase 1:
 *   ✅ Kiểm tra & yêu cầu Bluetooth permissions
 *   ✅ Lịch sử thiết bị từ Room DB (tự cập nhật qua Flow)
 *   ✅ Tách nhóm thiết bị: RTK (nhận diện theo tên) vs Others
 *   ✅ Xác minh NMEA sau khi kết nối (NmeaVerifier)
 *   ✅ Lưu thiết bị vào DB sau khi kết nối thành công
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val bluetoothDeviceSource : BluetoothDeviceSource,
    private val connectionManager     : ConnectionManager,
    private val gnssDataManager       : GnssDataManager,
    private val permissionManager     : PermissionManager,
    private val deviceRepository      : IDeviceRepository,
    @ApplicationContext private val context: Context          // inject để buộc TCP socket đi qua WiFi
) : ViewModel() {

    // ── Permission State ─────────────────────────────────────
    private val _permissionState = MutableStateFlow<BluetoothPermissionState>(
        BluetoothPermissionState.AllDenied
    )
    val permissionState: StateFlow<BluetoothPermissionState> =
        _permissionState.asStateFlow()

    // ── Thiết bị BT đã ghép đôi ─────────────────────────────
    private val _rtkDevices   = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val _otherDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val rtkDevices   : StateFlow<List<DeviceInfo>> = _rtkDevices.asStateFlow()
    val otherDevices : StateFlow<List<DeviceInfo>> = _otherDevices.asStateFlow()

    // ── Lịch sử thiết bị từ Room DB ─────────────────────────
    val recentDevices: StateFlow<List<DeviceInfo>> = deviceRepository
        .getRecentDevices()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ── Connection State ─────────────────────────────────────
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ── UI States ────────────────────────────────────────────
    private val _isLoading    = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Bước kết nối chi tiết để user biết đang làm gì.
     * Quan trọng khi NMEA verify mất vài giây.
     */
    private val _connectingStep = MutableStateFlow("")
    val connectingStep: StateFlow<String> = _connectingStep.asStateFlow()

    // ── TCP Input ────────────────────────────────────────────
    private val _tcpHost = MutableStateFlow("")
    val tcpHost: StateFlow<String> = _tcpHost.asStateFlow()

    private val _tcpPort = MutableStateFlow("2000")
    val tcpPort: StateFlow<String> = _tcpPort.asStateFlow()

    // ────────────────────────────────────────────────────────
    // Init
    // ────────────────────────────────────────────────────────

    init {
        checkPermissions()
    }

    // ────────────────────────────────────────────────────────
    // Permission
    // ────────────────────────────────────────────────────────

    /** Kiểm tra permission hiện tại — gọi khi vào màn hình hoặc sau khi grant */
    fun checkPermissions() {
        _permissionState.value = permissionManager.getPermissionState()
        if (_permissionState.value is BluetoothPermissionState.Granted) {
            loadPairedDevices()
        }
    }

    /** Lấy danh sách permission chưa được cấp để request */
    fun getMissingPermissions(): Array<String> =
        permissionManager.getMissingPermissions()

    // ────────────────────────────────────────────────────────
    // Load thiết bị BT
    // ────────────────────────────────────────────────────────

    /**
     * Load và phân loại thiết bị BT.
     * RTK devices: chứa keyword trong danh sách nhận biết
     * Others: thiết bị BT thông thường khác
     */
    @SuppressLint("MissingPermission")
    fun loadPairedDevices() {
        val all = bluetoothDeviceSource.getPairedDevices()
        _rtkDevices.value = all.filter { device ->
            BluetoothDeviceSource.RTK_DEVICE_KEYWORDS.any { keyword ->
                device.name.contains(keyword, ignoreCase = true)
            }
        }
        _otherDevices.value = all.filterNot { device ->
            BluetoothDeviceSource.RTK_DEVICE_KEYWORDS.any { keyword ->
                device.name.contains(keyword, ignoreCase = true)
            }
        }
    }

    // ────────────────────────────────────────────────────────
    // Kết nối
    // ────────────────────────────────────────────────────────

    fun onTcpHostChanged(host: String) { _tcpHost.value = host }
    fun onTcpPortChanged(port: String) { _tcpPort.value = port }

    /**
     * Kết nối Bluetooth với xác minh NMEA 3 bước:
     *   Bước 1: Tạo socket BT
     *   Bước 2: Xác minh NMEA (NmeaVerifier)
     *   Bước 3: Lưu lịch sử & bắt đầu xử lý GNSS
     */
    @SuppressLint("MissingPermission")
    fun connectBluetooth(deviceInfo: DeviceInfo) {
        viewModelScope.launch {
            _isLoading.value = true
            _connectingStep.value = "Đang kết nối Bluetooth..."
            _connectionState.value = ConnectionState.Connecting

            val btDevice = bluetoothDeviceSource.getDeviceByAddress(deviceInfo.address)
            if (btDevice == null) {
                _connectionState.value = ConnectionState.Error("Không tìm thấy thiết bị")
                _isLoading.value = false
                return@launch
            }

            val connection = BluetoothConnectionImpl(btDevice)
            val connectResult = connection.connect()

            if (connectResult.isFailure) {
                _connectionState.value = ConnectionState.Error(
                    connectResult.exceptionOrNull()?.message ?: "Kết nối BT thất bại"
                )
                _isLoading.value = false
                return@launch
            }

            // Bước 2: Xác minh NMEA
            _connectingStep.value = "Đang xác minh tín hiệu NMEA..."
            when (val verify = NmeaVerifier.verify(connection)) {
                is NmeaVerifier.VerifyResult.Success -> {
                    onConnectionSuccess(connection, deviceInfo)
                }
                is NmeaVerifier.VerifyResult.Timeout -> {
                    connection.disconnect()
                    _connectionState.value = ConnectionState.Error(verify.message)
                }
                is NmeaVerifier.VerifyResult.Error -> {
                    connection.disconnect()
                    _connectionState.value = ConnectionState.Error(verify.message)
                }
            }

            _isLoading.value = false
            _connectingStep.value = ""
        }
    }

    /** Kết nối TCP/WiFi với xác minh NMEA */
    fun connectTcp() {
        val host = _tcpHost.value.trim()
        val port = _tcpPort.value.toIntOrNull() ?: 0

        if (host.isBlank() || port <= 0) {
            _connectionState.value = ConnectionState.Error("Nhập đúng Host và Port")
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _connectingStep.value = "Đang kết nối TCP $host:$port..."
            _connectionState.value = ConnectionState.Connecting

            val connection = TcpConnectionImpl(host, port, context)
            val connectResult = connection.connect()

            if (connectResult.isFailure) {
                _connectionState.value = ConnectionState.Error(
                    connectResult.exceptionOrNull()?.message ?: "Kết nối TCP thất bại"
                )
                _isLoading.value = false
                return@launch
            }

            _connectingStep.value = "Đang xác minh tín hiệu NMEA..."
            when (val verify = NmeaVerifier.verify(connection)) {
                is NmeaVerifier.VerifyResult.Success -> {
                    val deviceInfo = DeviceInfo(
                        name    = "TCP $host",
                        address = "$host:$port",
                        type    = DeviceInfo.ConnectionType.TCP_WIFI
                    )
                    onConnectionSuccess(connection, deviceInfo)
                }
                is NmeaVerifier.VerifyResult.Timeout -> {
                    connection.disconnect()
                    _connectionState.value = ConnectionState.Error(verify.message)
                }
                is NmeaVerifier.VerifyResult.Error -> {
                    connection.disconnect()
                    _connectionState.value = ConnectionState.Error(verify.message)
                }
            }

            _isLoading.value = false
            _connectingStep.value = ""
        }
    }

    /**
     * Xử lý sau khi kết nối + NMEA verify thành công.
     * Dùng chung cho cả BT và TCP.
     */
    private suspend fun onConnectionSuccess(
        connection : com.hien.rtkmultidevice.core.connection.DeviceConnection,
        deviceInfo : DeviceInfo
    ) {
        val connectedState = ConnectionState.Connected(
            deviceName    = deviceInfo.name,
            deviceAddress = deviceInfo.address
        )
        connectionManager.setConnection(connection, connectedState)
        _connectionState.value = connectedState
        gnssDataManager.startNmeaProcessing()

        // Lưu vào lịch sử
        deviceRepository.saveDevice(deviceInfo)
    }

    /** Kết nối lại thiết bị từ lịch sử */
    fun reconnectDevice(deviceInfo: DeviceInfo) {
        when (deviceInfo.type) {
            DeviceInfo.ConnectionType.BLUETOOTH -> connectBluetooth(deviceInfo)
            DeviceInfo.ConnectionType.TCP_WIFI  -> {
                val parts = deviceInfo.address.split(":")
                if (parts.size == 2) {
                    _tcpHost.value = parts[0]
                    _tcpPort.value = parts[1]
                    connectTcp()
                }
            }
        }
    }

    /** Xoá thiết bị khỏi lịch sử */
    fun deleteFromHistory(address: String) {
        viewModelScope.launch {
            deviceRepository.deleteDevice(address)
        }
    }

    fun checkExistingConnection() {
        _connectionState.value = connectionManager.connectionState.value
    }
}
