package com.hien.rtkmultidevice.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CellularNetworkHelper — Dùng 4G/5G song song khi WiFi RTK không có Internet.
 *
 * VẤN ĐỀ (hành vi chuẩn của Android):
 *   Khi điện thoại nối WiFi hotspot của đầu thu RTK (không có internet),
 *   Android route TOÀN BỘ traffic của mọi app qua WiFi — kể cả khi 4G/5G
 *   đang bật. Vì vậy mọi app đều báo "Không có Internet", bản đồ nền không
 *   tải được, NTRIP không kết nối được.
 *
 * GIẢI PHÁP (cách các field software thương mại làm):
 *   1. requestNetwork(TRANSPORT_CELLULAR) — yêu cầu Android giữ mạng di động
 *      hoạt động SONG SONG với WiFi.
 *   2. bindProcessToNetwork(cellular) — mọi socket MỚI của app (tile bản đồ,
 *      NTRIP, DNS) đi qua 4G/5G.
 *
 * AN TOÀN với kết nối đầu thu:
 *   • TcpConnectionImpl đã tạo socket tới rover qua wifiNetwork.socketFactory
 *     (bind tường minh vào WiFi) → KHÔNG bị ảnh hưởng bởi bindProcessToNetwork.
 *   • Bluetooth SPP không liên quan đến network stack → không ảnh hưởng.
 *   • NtripProxyServer là ServerSocket lắng nghe — vẫn nhận kết nối từ WiFi.
 *
 * Yêu cầu quyền: INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE (đã có).
 */
object CellularNetworkHelper {

    private const val TAG = "CellularNetHelper"

    @Volatile private var cellularNetwork: Network? = null
    @Volatile private var processBound     = false
    @Volatile private var callbackRegistered = false
    private var cmRef: ConnectivityManager? = null

    /** Callback persistent — giữ đăng ký để Android duy trì cellular luôn bật */
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Cellular available: $network")
            cellularNetwork = network
        }
        override fun onLost(network: Network) {
            if (cellularNetwork == network) {
                Log.d(TAG, "Cellular lost")
                cellularNetwork = null
                if (processBound) {
                    runCatching { cmRef?.bindProcessToNetwork(null) }
                    processBound = false
                }
            }
        }
    }

    /**
     * Đảm bảo app có Internet để tải bản đồ nền + NTRIP.
     *
     * @return null      = đã có Internet sẵn (không cần làm gì / đã bind từ trước)
     *         String    = thông báo cho user (đã chuyển 4G thành công, hoặc lỗi)
     */
    suspend fun ensureInternet(context: Context): String? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cmRef = cm

        // 1. Mạng hiện tại đã có Internet thật (validated)? → không cần làm gì
        val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val activeHasInternet =
            activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (activeHasInternet) return null

        // 2. Đã bind cellular từ trước và cellular còn sống? → im lặng
        if (processBound && cellularNetwork != null) return null

        // 3. Yêu cầu mạng cellular (đăng ký 1 lần, giữ mãi)
        registerRequestOnce(cm)

        // 4. Chờ cellular sẵn sàng (tối đa 8 giây)
        val network = withTimeoutOrNull(8_000L) {
            while (cellularNetwork == null) delay(250)
            cellularNetwork
        } ?: return "⚠ Không lấy được mạng 4G/5G — kiểm tra SIM, Dữ liệu di động (đang ở vùng không sóng?)."

        // 5. Bind toàn bộ traffic internet của app qua cellular
        val ok = runCatching { cm.bindProcessToNetwork(network) }.getOrDefault(false)
        if (!ok) return "⚠ Không chuyển được app sang mạng di động."
        processBound = true
        Log.i(TAG, "Đã bind process → cellular (WiFi RTK không có internet)")
        return "✓ WiFi RTK không có Internet — app đã tự chuyển sang 4G/5G để tải bản đồ nền & NTRIP. Kết nối đầu thu không bị ảnh hưởng."
    }

    private fun registerRequestOnce(cm: ConnectivityManager) {
        if (callbackRegistered) return
        synchronized(this) {
            if (callbackRegistered) return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                cm.requestNetwork(request, callback)
                callbackRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "requestNetwork lỗi: ${e.message}")
            }
        }
    }
}
