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
import com.hien.rtkmultidevice.core.network.WifiInfoHelper
import com.hien.rtkmultidevice.data.datastore.AppSettings
import kotlinx.coroutines.flow.first
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
    private val appSettings           : AppSettings,
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

    // ── Kết nối nhanh qua WiFi (không cần nhập IP/port) ──────
    /** Tên máy thu = tên WiFi đang nối (VD "GNSS-3366525"). null nếu không ở WiFi máy thu. */
    private val _wifiDeviceName = MutableStateFlow<String?>(null)
    val wifiDeviceName: StateFlow<String?> = _wifiDeviceName.asStateFlow()

    /** IP hiện tại của điện thoại trong mạng WiFi (máy thu cần IP này cho RTK Client). */
    private val _phoneIp = MutableStateFlow<String?>(null)
    val phoneIp: StateFlow<String?> = _phoneIp.asStateFlow()

    /** Cảnh báo khi IP điện thoại ĐỔI so với lần trước → phải sửa lại trên máy thu. */
    private val _phoneIpWarning = MutableStateFlow<String?>(null)
    val phoneIpWarning: StateFlow<String?> = _phoneIpWarning.asStateFlow()

    // ── Quét mạng tìm máy thu (máy nối chung router, VD T30) ──
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<WifiInfoHelper.FoundDevice>>(emptyList())
    val scanResults: StateFlow<List<WifiInfoHelper.FoundDevice>> = _scanResults.asStateFlow()

    /** Làm mới tên máy thu + IP điện thoại — gọi khi vào màn hình Kết nối. */
    fun refreshWifiDevice() {
        _wifiDeviceName.value = WifiInfoHelper.deviceNameFromWifi(context)
        val ip = WifiInfoHelper.phoneIp(context)
        _phoneIp.value = ip
        if (ip.isNullOrBlank()) return

        viewModelScope.launch {
            val last = appSettings.lastPhoneIpFlow.first()
            _phoneIpWarning.value = if (last.isNotBlank() && last != ip) {
                "Địa chỉ điện thoại đã đổi: $last → $ip.\n" +
                "Nếu máy thu lấy cải chính qua điện thoại, hãy sửa mục RTK Client trên máy thu thành $ip."
            } else null
            appSettings.saveLastPhoneIp(ip)
        }
    }

    fun clearPhoneIpWarning() { _phoneIpWarning.value = null }

    /**
     * Quét mạng tìm máy thu — dùng khi máy thu nối chung router
     * (gateway là router, không phải máy thu).
     */
    fun scanForDevices() {
        viewModelScope.launch {
            _isScanning.value = true
            _scanResults.value = emptyList()
            _connectingStep.value = "Đang quét mạng tìm máy thu..."
            _scanResults.value = WifiInfoHelper.scanLan(context)
            _connectingStep.value = ""
            _isScanning.value = false
            if (_scanResults.value.isEmpty()) {
                _connectionState.value = ConnectionState.Error(
                    "Không tìm thấy máy thu nào trong mạng.\n" +
                    "→ Kiểm tra máy đã bật và cùng mạng WiFi với điện thoại."
                )
            }
        }
    }

    /** Kết nối tới máy tìm được khi quét. */
    fun connectFound(device: WifiInfoHelper.FoundDevice) {
        _tcpHost.value = device.host
        _tcpPort.value = device.port.toString()
        connectTcp()
    }

    /**
     * KẾT NỐI NHANH: chỉ cần đang nối WiFi của máy thu.
     * Tự tìm địa chỉ máy (gateway) + tự dò cổng dữ liệu → không bắt người dùng nhập gì.
     * Cổng đã dùng thành công lần trước được thử đầu tiên (nhớ cho lần sau).
     */
    fun quickConnectWifi() {
        val name = _wifiDeviceName.value
        val host = WifiInfoHelper.gatewayIp(context)
        if (host.isNullOrBlank()) {
            _connectionState.value = ConnectionState.Error(
                "Chưa nối WiFi của máy thu. Vào Cài đặt → WiFi, chọn mạng có tên máy (VD GNSS-3366525) rồi quay lại."
            )
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _connectionState.value = ConnectionState.Connecting
            _connectingStep.value = "Đang tìm ${name ?: "máy thu"}..."

            // Cổng đã lưu của chính máy này (nếu từng kết nối)
            val remembered = recentDevices.value
                .firstOrNull { it.type == DeviceInfo.ConnectionType.TCP_WIFI && it.name == name }
                ?.address?.substringAfter(':')?.toIntOrNull()

            var target = host
            var port = WifiInfoHelper.findOpenPort(context, host, remembered)

            // Gateway KHÔNG phải lúc nào cũng là máy thu:
            //   • Sinov M6 Pro là điểm phát WiFi  → máy ở 192.168.1.1 (gateway)
            //   • ComNav T30 phát WiFi nhưng máy ở 192.168.1.8, gateway lại là .1
            // Không thấy cổng nào mở ở gateway thì TỰ QUÉT MẠNG thay vì báo lỗi.
            if (port == null) {
                _connectingStep.value = "Đang quét mạng tìm ${name ?: "máy thu"}..."
                val found = WifiInfoHelper.scanLan(context, maxResults = 1).firstOrNull()
                if (found != null) { target = found.host; port = found.port }
            }

            if (port == null) {
                _isLoading.value = false
                _connectingStep.value = ""
                _connectionState.value = ConnectionState.Error(
                    "Tìm thấy ${name ?: "máy thu"} nhưng không có kênh dữ liệu nào đang mở.\n" +
                    "Trong trang cấu hình của máy, kiểm tra mục truyền dữ liệu " +
                    "(TCP Server / Data Transfer) đã bật và ghi lại số cổng."
                )
                return@launch
            }

            _tcpHost.value = target
            _tcpPort.value = port.toString()
            _isLoading.value = false      // connectTcp() sẽ tự bật lại
            connectTcp()
        }
    }

    // ────────────────────────────────────────────────────────
    // Init
    // ────────────────────────────────────────────────────────

    init {
        checkPermissions()
        refreshWifiDevice()
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
                    friendlyTcpError(connectResult.exceptionOrNull())
                )
                _isLoading.value = false
                _connectingStep.value = ""
                return@launch
            }

            _connectingStep.value = "Đang xác minh tín hiệu NMEA..."
            when (val verify = NmeaVerifier.verify(connection)) {
                is NmeaVerifier.VerifyResult.Success -> {
                    // Tên hiển thị = tên máy thu (tên WiFi), không dùng IP kỹ thuật
                    val friendlyName = WifiInfoHelper.deviceNameFromWifi(context)
                        ?: _wifiDeviceName.value
                        ?: "Máy thu WiFi"
                    val deviceInfo = DeviceInfo(
                        name    = friendlyName,
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

    /**
     * Đổi lỗi TCP kỹ thuật thành câu người đo hiểu được.
     * Tránh hiện "ECONNREFUSED", "failed to connect to /192.168.1.1:9901".
     */
    private fun friendlyTcpError(e: Throwable?): String {
        val msg = e?.message.orEmpty()
        val dev = _wifiDeviceName.value ?: "máy thu"
        return when {
            msg.contains("ECONNREFUSED", true) || msg.contains("refused", true) ->
                "Đã thấy $dev nhưng máy chưa mở kênh truyền dữ liệu.\n" +
                "→ Tắt/bật lại máy thu, hoặc bật mục truyền dữ liệu (TCP Server) trong trang cấu hình của máy."
            msg.contains("ETIMEDOUT", true) || msg.contains("timeout", true) ||
            msg.contains("after", true) && msg.contains("ms", true) ->
                "Không liên lạc được với $dev.\n→ Kiểm tra điện thoại còn nối đúng WiFi của máy thu không."
            msg.contains("ENETUNREACH", true) || msg.contains("unreachable", true) ->
                "Điện thoại chưa vào được mạng của $dev.\n→ Vào Cài đặt → WiFi và chọn lại mạng có tên máy."
            msg.contains("EHOSTUNREACH", true) ->
                "Không tìm thấy $dev trong mạng.\n→ Kiểm tra máy đã bật và đang phát WiFi."
            else -> "Không kết nối được $dev. ${if (msg.isBlank()) "" else "($msg)"}"
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
