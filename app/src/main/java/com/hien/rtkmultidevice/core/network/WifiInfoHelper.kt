package com.hien.rtkmultidevice.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
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

    /**
     * Các cổng dữ liệu TCP phổ biến của máy thu RTK (thứ tự ưu tiên khi dò).
     *
     *  9901  — Sinov M6 Pro (TCP Server)
     * 12345  — ComNav T30 (TCP1/WEBNTRIP1, luồng NMEA)
     * 12346  — ComNav T30 (TCP2/WEBNTRIP2, luồng RTD)
     *  2000/6000/8000/9000/8080/1958 — các hãng khác
     */
    val COMMON_PORTS = listOf(9901, 12345, 2000, 6000, 8000, 9000, 12346, 8080, 1958)

    // ══════════════════════════════════════════════════════════
    // HỒ SƠ MÁY THU — dùng thông tin ĐÃ BIẾT thay vì dò mò
    // ══════════════════════════════════════════════════════════

    /**
     * Mỗi dòng máy có địa chỉ + cổng cố định. Nhận dạng qua TÊN WIFI của máy
     * rồi thử đúng địa chỉ/cổng của nó ⇒ kết nối trong 1–2 lần thử thay vì
     * dò 9 cổng × nhiều địa chỉ.
     *
     * @param hosts    "" = dùng gateway của mạng WiFi hiện tại
     */
    data class Profile(
        val label     : String,
        val ssidRegex : Regex,
        val hosts     : List<String>,
        val ports     : List<Int>
    )

    val PROFILES = listOf(
        // ComNav T30: WiFi "T30-T31Uxxxxx"; máy ở .8 (KHÔNG phải gateway), NMEA cổng 12345
        Profile("ComNav T30", Regex("^T30[-_]", RegexOption.IGNORE_CASE),
            hosts = listOf("192.168.1.8", ""), ports = listOf(12345, 12346)),
        // Sinov M6 Pro: WiFi "GNSS-3366525"; máy CHÍNH LÀ điểm phát → gateway, cổng 9901
        Profile("Sinov M6 Pro", Regex("^GNSS[-_]", RegexOption.IGNORE_CASE),
            hosts = listOf("", "192.168.1.1"), ports = listOf(9901, 2000)),
    )

    fun profileFor(ssid: String?): Profile? =
        ssid?.let { s -> PROFILES.firstOrNull { it.ssidRegex.containsMatchIn(s) } }

    /**
     * Tìm địa chỉ + cổng của máy thu theo HỒ SƠ (nhanh, chính xác).
     * @return (host, port) hoặc null nếu hồ sơ không khớp/không mở cổng.
     */
    suspend fun findByProfile(
        context: Context, ssid: String?, gateway: String?
    ): Pair<String, Int>? = withContext(Dispatchers.IO) {
        val p = profileFor(ssid) ?: return@withContext null
        val hosts = p.hosts.map { if (it.isBlank()) gateway else it }
            .filterNotNull().filter { it.isNotBlank() }.distinct()
        for (h in hosts) for (port in p.ports) {
            if (probe(context, h, port, 1200)) {
                Log.d(TAG, "Khớp hồ sơ ${p.label}: $h:$port")
                return@withContext h to port
            }
        }
        null
    }

    /**
     * Tên WiFi đang kết nối = tên máy thu RTK.
     * Trả null nếu không nối WiFi hoặc không đọc được tên.
     * (Cần quyền vị trí — app đã có ACCESS_FINE_LOCATION.)
     */
    fun deviceNameFromWifi(context: Context): String? = runCatching {
        // Dùng wifiNetwork() chứ KHÔNG dùng activeNetwork: khi NTRIP chạy, app bind
        // process sang 4G nên activeNetwork là cellular → sẽ tưởng nhầm là không có WiFi.
        if (wifiNetwork(context) == null) return null

        @Suppress("DEPRECATION")
        val raw = context.applicationContext
            .getSystemService(WifiManager::class.java)
            ?.connectionInfo?.ssid ?: return null

        val ssid = raw.trim().removeSurrounding("\"")
        if (ssid.isBlank() || ssid.contains("unknown", true)) null else ssid
    }.getOrNull()

    /**
     * IP máy thu = gateway của mạng WIFI (cũng là địa chỉ trang web cấu hình).
     * Lấy theo wifiNetwork() để không bị nhầm sang mạng 4G khi app đang bind cellular.
     */
    fun gatewayIp(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = wifiNetwork(context) ?: return null
        val lp: LinkProperties? = cm.getLinkProperties(net)
        lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress
            // Một số máy thu không khai default route → lấy địa chỉ .1 cùng dải
            ?: phoneIp(context)?.substringBeforeLast('.')?.let { "$it.1" }
    }.getOrNull()

    /** IP của điện thoại trên WiFi — máy thu cần IP này để trỏ RTK Client về app. */
    @Suppress("DEPRECATION")
    fun phoneIp(context: Context): String? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.connectionInfo?.ipAddress
            ?.takeIf { it != 0 }
            ?.let { android.text.format.Formatter.formatIpAddress(it) }
    }.getOrNull()

    /**
     * Mạng WiFi hiện tại — dùng để ÉP socket đi qua WiFi (không qua 4G).
     *
     * BẮT BUỘC dùng khi nói chuyện với máy thu: khi NTRIP đang chạy, app đã
     * bindProcessToNetwork(cellular) nên mọi socket mặc định đi ra 4G và
     * KHÔNG thể tới địa chỉ LAN (192.168.x.x) của máy thu.
     */
    fun wifiNetwork(context: Context): Network? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val wifis = cm.allNetworks.filter { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@filter false
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        if (wifis.size <= 1) return@runCatching wifis.firstOrNull()

        // ⚠ `allNetworks` trả về CẢ mạng WiFi CŨ đang rời đi, không chỉ mạng đang
        //   dùng. Bản trước lấy `firstOrNull` — vớ phải cái đã chết thì socket
        //   không báo lỗi ngay mà TREO tới lúc hết giờ: trang cấu hình máy thu
        //   nạp được nửa chừng rồi đứng, đúng cái cảm giác "mạng yếu".
        //   Xảy ra thường nhất ngay sau khi đổi/nối lại WiFi của máy thu.
        //
        //   Mạng còn sống thì có ĐỊA CHỈ IPv4 trên link. Lấy nó làm mốc phân biệt.
        //   Không dùng `cm.activeNetwork`: khi NTRIP đang chạy, app đã
        //   bindProcessToNetwork(cellular) nên activeNetwork trả về 4G, không
        //   phải WiFi.
        wifis.firstOrNull { n ->
            cm.getLinkProperties(n)?.linkAddresses?.any {
                it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
            } == true
        } ?: wifis.first()
    }.getOrNull()

    /** Thử 1 địa chỉ:cổng cụ thể — dùng để xác nhận lại máy đã từng kết nối. */
    suspend fun probeHost(
        context: Context, host: String, port: Int, timeoutMs: Int = 1200
    ): Boolean = withContext(Dispatchers.IO) { probe(context, host, port, timeoutMs) }

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

    /** Một máy thu tìm được khi quét mạng. */
    data class FoundDevice(val host: String, val port: Int)

    /**
     * QUÉT MẠNG tìm máy thu RTK.
     *
     * Cần khi máy thu KHÔNG tự phát WiFi mà nối chung router
     * (VD ComNav T30 ở 192.168.1.8) — lúc đó gateway là router, không phải máy thu.
     *
     * Cách làm: lấy dải mạng của điện thoại (VD 192.168.1.x), thử song song
     * .1 → .254 với các cổng dữ liệu phổ biến. Chạy song song nên chỉ vài giây.
     *
     * @param maxResults dừng sớm khi đã đủ số máy cần tìm.
     */
    suspend fun scanLan(
        context    : Context,
        timeoutMs  : Int = 350,
        maxResults : Int = 5
    ): List<FoundDevice> = withContext(Dispatchers.IO) {
        val myIp = phoneIp(context) ?: return@withContext emptyList()
        val prefix = myIp.substringBeforeLast('.', "")
        if (prefix.isBlank()) return@withContext emptyList()

        val found = java.util.concurrent.CopyOnWriteArrayList<FoundDevice>()
        // Ưu tiên các địa chỉ hay gặp trước, rồi mới quét phần còn lại
        val priority = listOf(1, 8, 2, 100, 10, 200)
        val hosts = (priority + (1..254).filterNot { it in priority }).map { "$prefix.$it" }

        coroutineScope {
            val sem = kotlinx.coroutines.sync.Semaphore(48)   // giới hạn socket đồng thời
            hosts.map { h ->
                async {
                    if (found.size >= maxResults) return@async
                    sem.withPermit {
                        if (h == myIp) return@withPermit
                        for (p in COMMON_PORTS) {
                            if (found.size >= maxResults) return@withPermit
                            if (probe(context, h, p, timeoutMs)) {
                                Log.d(TAG, "Quét thấy máy: $h:$p")
                                found.add(FoundDevice(h, p))
                                return@withPermit          // 1 máy chỉ lấy 1 cổng
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        found.toList()
    }
}
