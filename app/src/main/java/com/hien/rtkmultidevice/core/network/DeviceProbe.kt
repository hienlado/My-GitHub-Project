package com.hien.rtkmultidevice.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * DeviceProbe — DÒ và NHẬN DẠNG máy thu GNSS chưa có trong danh sách.
 *
 * Vì sao cần: mỗi hãng một địa chỉ, một cổng, một kiểu dữ liệu. Trước đây muốn
 * thêm máy mới phải mở tài liệu hãng rồi sửa mã nguồn. Bước này làm ngược lại —
 * **nghe máy nói gì rồi mới kết luận**, và ghi lại thành hồ sơ dùng cho lần sau.
 *
 * Nguyên tắc: CHỈ ĐỌC. Không gửi lệnh, không đổi cấu hình máy. Sai lầm khi dò
 * một máy đang đo ngoài thực địa thì đắt hơn nhiều so với việc dò chậm một chút.
 *
 * Ba việc:
 *   1. `nghe()`  — mở cổng, hứng vài giây dữ liệu thô, phân loại NMEA / RTCM3.
 *   2. `web()`   — GET trang cấu hình để lấy tiêu đề, đoán hãng.
 *   3. `quet()`  — quét gateway + LAN + dải cổng, trả về mọi ứng viên tìm được.
 */
object DeviceProbe {

    private const val TAG = "DeviceProbe"

    /** Cổng dữ liệu đáng thử, gộp cổng đã biết với vài cổng hay gặp khác. */
    val CONG_THU: List<Int> = (WifiInfoHelper.COMMON_PORTS +
        listOf(9902, 3000, 5017, 5018, 2101, 2947, 23, 8888)).distinct()

    /** Địa chỉ hay gặp khi máy TỰ PHÁT WiFi (máy chính là gateway hoặc cố định). */
    val HOST_THU: List<String> = listOf(
        "192.168.10.1",   // STEC
        "192.168.1.1", "192.168.1.8", "192.168.0.1", "192.168.4.1", "10.0.0.1"
    )

    enum class Loai { NMEA, RTCM3, HON_HOP, NHI_PHAN_LA, TRONG }

    /** Kết quả nghe một cổng. */
    data class KetQua(
        val host    : String,
        val port    : Int,
        val loai    : Loai,
        val soByte  : Int,
        val giay    : Double,
        /** "GGA" -> số câu nhận được */
        val cauNmea : Map<String, Int> = emptyMap(),
        /** "GN", "GP"… — mã talker của câu NMEA */
        val talker  : Set<String> = emptySet(),
        /** số hiệu bản tin RTCM3 -> số lần xuất hiện */
        val rtcm    : Map<Int, Int> = emptyMap(),
        /** tần suất câu vị trí (Hz), 0 nếu không đo được */
        val tanSo   : Double = 0.0,
        val mauChu  : String = "",
        val mauHex  : String = ""
    ) {
        val moTa: String get() = when (loai) {
            Loai.NMEA    -> "NMEA — ${cauNmea.keys.sorted().joinToString(",")}" +
                            (if (tanSo > 0) " · ${"%.1f".format(tanSo)} Hz" else "")
            Loai.RTCM3   -> "RTCM3 — bản tin ${rtcm.keys.sorted().joinToString(",")}"
            Loai.HON_HOP -> "NMEA + RTCM3 trên cùng cổng"
            Loai.NHI_PHAN_LA -> "Nhị phân lạ (chưa nhận dạng được)"
            Loai.TRONG   -> "Mở được cổng nhưng KHÔNG có dữ liệu"
        }
    }

    /**
     * Mở cổng và hứng dữ liệu thô trong `giay` giây rồi phân loại.
     *
     * Ép socket đi qua WiFi (`wifiNetwork`) như các phần khác của app: khi NTRIP
     * đang chạy, app đã bind tiến trình vào 4G, socket mặc định sẽ không tới
     * được máy thu trong mạng LAN.
     */
    suspend fun nghe(
        context: Context, host: String, port: Int, giay: Int = 4
    ): KetQua = withContext(Dispatchers.IO) {
        val batDau = System.currentTimeMillis()
        val buf = java.io.ByteArrayOutputStream()
        runCatching {
            val net = WifiInfoHelper.wifiNetwork(context)
            val s = (net?.socketFactory?.createSocket() as? Socket) ?: Socket()
            s.use { sk ->
                sk.connect(InetSocketAddress(host, port), 2000)
                sk.soTimeout = 1200
                val ins: InputStream = sk.getInputStream()
                val tmp = ByteArray(4096)
                val han = batDau + giay * 1000L
                while (System.currentTimeMillis() < han && buf.size() < 256 * 1024) {
                    val n = try { ins.read(tmp) } catch (e: java.net.SocketTimeoutException) { 0 }
                    if (n > 0) buf.write(tmp, 0, n) else if (n < 0) break
                }
            }
        }.onFailure { Log.w(TAG, "nghe $host:$port lỗi: ${it.message}") }

        val data = buf.toByteArray()
        val dt = (System.currentTimeMillis() - batDau) / 1000.0
        phanLoai(host, port, data, dt)
    }

    /** Phân loại luồng byte thô. Tách riêng để test được mà không cần thiết bị. */
    fun phanLoai(host: String, port: Int, data: ByteArray, giay: Double): KetQua {
        if (data.isEmpty())
            return KetQua(host, port, Loai.TRONG, 0, giay)

        // ── NMEA: câu ASCII bắt đầu '$' hoặc '!', kết thúc "*hh" ──
        val chu = String(data, Charsets.ISO_8859_1)
        val cau = HashMap<String, Int>()
        val talker = HashSet<String>()
        var soGga = 0
        Regex("[\\$!]([A-Z]{2})([A-Z]{3}),[^\\r\\n\\$!]*").findAll(chu).forEach { m ->
            val tk = m.groupValues[1]; val ty = m.groupValues[2]
            talker += tk
            cau[ty] = (cau[ty] ?: 0) + 1
            if (ty == "GGA" || ty == "RMC") soGga++
        }

        // ── RTCM3: 0xD3, 6 bit dành riêng + 10 bit độ dài, rồi 12 bit số hiệu ──
        val rtcm = HashMap<Int, Int>()
        var i = 0
        while (i + 5 < data.size) {
            if (data[i] == 0xD3.toByte()) {
                val len = ((data[i + 1].toInt() and 0x03) shl 8) or (data[i + 2].toInt() and 0xFF)
                if (len in 1..1023 && i + 3 + len + 3 <= data.size) {
                    val so = ((data[i + 3].toInt() and 0xFF) shl 4) or
                             ((data[i + 4].toInt() and 0xF0) shr 4)
                    if (so in 1000..1300) {          // dải số hiệu RTCM3 hợp lệ
                        rtcm[so] = (rtcm[so] ?: 0) + 1
                        i += 3 + len + 3
                        continue
                    }
                }
            }
            i++
        }

        val coNmea = cau.isNotEmpty()
        val coRtcm = rtcm.isNotEmpty()
        val loai = when {
            coNmea && coRtcm -> Loai.HON_HOP
            coNmea -> Loai.NMEA
            coRtcm -> Loai.RTCM3
            else   -> Loai.NHI_PHAN_LA
        }
        return KetQua(
            host = host, port = port, loai = loai, soByte = data.size, giay = giay,
            cauNmea = cau, talker = talker, rtcm = rtcm,
            tanSo = if (giay > 0.5) soGga / giay else 0.0,
            mauChu = chu.lineSequence().filter { it.isNotBlank() }.take(4)
                .joinToString("\n").take(300),
            mauHex = data.take(24).joinToString(" ") { "%02X".format(it) }
        )
    }

    // ══════════════════════════════════════════════════════════
    // TRANG CẤU HÌNH
    // ══════════════════════════════════════════════════════════

    data class Web(val host: String, val port: Int, val ma: Int,
                   val tieuDe: String, val server: String)

    /**
     * GET trang chủ để lấy tiêu đề + header Server — thường đủ đoán ra hãng.
     * STEC nhận ra ở đây: web http://192.168.10.1.
     */
    suspend fun web(context: Context, host: String, port: Int = 80): Web? =
        withContext(Dispatchers.IO) {
            runCatching {
                val net = WifiInfoHelper.wifiNetwork(context)
                val url = URL("http://$host:$port/")
                val c = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                c.connectTimeout = 2500; c.readTimeout = 2500
                c.instanceFollowRedirects = true
                val ma = c.responseCode
                val body = runCatching {
                    (if (ma < 400) c.inputStream else c.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                }.getOrDefault("")
                val td = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1)?.trim().orEmpty()
                c.disconnect()
                Web(host, port, ma, td, c.getHeaderField("Server").orEmpty())
            }.getOrNull()
        }

    // ══════════════════════════════════════════════════════════
    // QUÉT TỔNG
    // ══════════════════════════════════════════════════════════

    data class UngVien(
        val host   : String,
        val cong   : List<Int>,
        val web    : Web?  = null,
        val nghe   : KetQua? = null,
        val nguon  : String = ""        // "gateway" | "hồ sơ" | "quét LAN" | "cố định"
    )

    /**
     * Quét tìm mọi ứng viên: gateway hiện tại → các địa chỉ cố định hay gặp →
     * quét LAN. Với mỗi địa chỉ, thử dải cổng dữ liệu và cổng 80.
     *
     * KHÔNG tự kết nối, KHÔNG gửi gì. Chỉ trả danh sách để người dùng chọn.
     */
    suspend fun quet(
        context   : Context,
        gateway   : String? = null,
        timeoutMs : Int = 900,
        nghevGiay : Int = 3
    ): List<UngVien> = withContext(Dispatchers.IO) {
        val ds = LinkedHashMap<String, String>()          // host -> nguồn
        gateway?.takeIf { it.isNotBlank() }?.let { ds[it] = "gateway" }
        HOST_THU.forEach { ds.putIfAbsent(it, "cố định") }
        WifiInfoHelper.scanLan(context, timeoutMs = 300, maxResults = 6)
            .forEach { ds.putIfAbsent(it.host, "quét LAN") }

        val ra = ArrayList<UngVien>()
        for ((host, nguon) in ds) {
            val mo = CONG_THU.filter { WifiInfoHelper.probeHost(context, host, it, timeoutMs) }
            val w = withTimeoutOrNull(3000L) { web(context, host) }
            if (mo.isEmpty() && w == null) continue
            // Nghe cổng dữ liệu ĐẦU TIÊN mở được; các cổng còn lại chỉ liệt kê.
            val k = mo.firstOrNull()?.let { nghe(context, host, it, nghevGiay) }
            ra += UngVien(host, mo, w, k, nguon)
        }
        ra
    }
}
