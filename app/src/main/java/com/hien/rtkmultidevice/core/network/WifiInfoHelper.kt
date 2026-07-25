package com.hien.rtkmultidevice.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WifiInfoHelper — Giúp kết nối máy thu RTK qua WiFi mà KHÔNG cần nhập IP/port.
 *
 * Ý tưởng:
 *   • Máy thu RTK phát WiFi có tên trùng tên máy (VD "GNSS-3366525", "T31U04294").
 *     → Lấy SSID làm TÊN THIẾT BỊ hiển thị cho người dùng.
 *   • Địa chỉ máy thu = gateway của mạng WiFi đó (thường 192.168.1.1 / 192.168.10.1).
 *     → Không bắt người dùng gõ IP.
 *   • Cổng dữ liệu khác nhau tuỳ hãng (9901, 2000, 6000...).
 *     → Tự dò cổng nào đang mở.
 *
 * Nhờ vậy người đo chỉ cần: nối WiFi của máy → mở app → nhấn Kết nối.
 */
object WifiInfoHelper {

    private const val TAG = "WifiInfoHelper"

    /** Các cổng dữ liệu TCP phổ biến của máy thu RTK (thứ tự ưu tiên khi dò). */
    val COMMON_PORTS = listOf(9901, 2000, 6000, 8000, 9000, 8080, 1958)

    /**
     * Tên WiFi đang kết nối = tên máy thu RTK.
     * Trả null nếu không nối WiFi hoặc không đọc được tên.
     * (Cần quyền vị trí — app đã có ACCESS_FINE_LOCATION.)
     */
    fun deviceNameFromWifi(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null

        @Suppress("DEPRECATION")
        val raw = context.applicationContext
            .getSystemService(WifiManager::class.java)
            ?.connectionInfo?.ssid ?: return null

        val ssid = raw.trim().removeSurrounding("\"")
        if (ssid.isBlank() || ssid.contains("unknown", true)) null else ssid
    }.getOrNull()

    /** IP máy thu = gateway của WiFi hiện tại (cũng là địa chỉ trang web cấu hình). */
    fun gatewayIp(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp: LinkProperties? = cm?.getLinkProperties(cm.activeNetwork)
        lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress
    }.getOrNull()

    /** IP của điện thoại trên WiFi — máy thu cần IP này để trỏ RTK Client về app. */
    @Suppress("DEPRECATION")
    fun phoneIp(context: Context): String? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.connectionInfo?.ipAddress
            ?.takeIf { it != 0 }
            ?.let { android.text.format.Formatter.formatIpAddress(it) }
    }.getOrNull()

    /** Mạng WiFi hiện tại — dùng để ép socket đi qua WiFi (không qua 4G). */
    private fun wifiNetwork(context: Context): Network? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }.getOrNull()

    /**
     * Thử mở nhanh 1 cổng (không đọc dữ liệu) để biết cổng có dịch vụ hay không.
     * @return true nếu bắt tay TCP thành công.
     */
    private fun probe(context: Context, host: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            val net = wifiNetwork(context)
            val socket = if (net != null) {
                // Ép qua WiFi — tránh việc app đang bind 4G làm socket đi sai đường
                net.socketFactory.createSocket() as Socket
            } else Socket()
            socket.use {
                it.connect(InetSocketAddress(host, port), timeoutMs)
                it.isConnected
            }
        }.getOrDefault(false)

    /**
     * Dò cổng dữ liệu đang mở trên máy thu.
     *
     * @param preferred cổng đã dùng thành công lần trước (thử đầu tiên).
     * @return cổng mở đầu tiên tìm được, hoặc null nếu không cổng nào mở.
     */
    suspend fun findOpenPort(
        context   : Context,
        host      : String,
        preferred : Int? = null,
        timeoutMs : Int = 1200
    ): Int? = withContext(Dispatchers.IO) {
        val ports = buildList {
            preferred?.let { add(it) }
            addAll(COMMON_PORTS.filter { it != preferred })
        }
        for (p in ports) {
            if (probe(context, host, p, timeoutMs)) {
                Log.d(TAG, "Tìm thấy cổng mở: $host:$p")
                return@withContext p
            }
        }
        Log.w(TAG, "Không cổng nào mở trên $host (đã thử ${ports.size} cổng)")
        null
    }
}
