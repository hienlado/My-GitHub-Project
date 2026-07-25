package com.hien.rtkmultidevice.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * ReceiverWebControl — Gọi thẳng lệnh của TRANG WEB máy thu Sinov/CHC.
 *
 * Các endpoint dưới đây được BẮT THẬT từ trang web của máy (DevTools → Network):
 *
 *   Reboot Receiver:
 *     GET https://192.168.1.1/reboot_system.cmd?urlStringId=admin&_=<timestamp>
 *   Turn Off Receiver:
 *     GET https://192.168.1.1/power_off_set.cmd?urlStringId=admin&_=<timestamp>
 *
 * Ghi chú kỹ thuật rút ra từ bản bắt được:
 *   • Giao thức là HTTPS (không phải HTTP).
 *   • Method GET, không có body.
 *   • KHÔNG có Cookie / Authorization → máy chỉ dựa vào tham số urlStringId=<user>.
 *     Nghĩa là app gọi được ngay, không cần đăng nhập trước.
 *   • Tham số "_" chỉ là cache-buster của jQuery → dùng System.currentTimeMillis().
 *   • Header X-Requested-With + Referer nên gửi kèm cho giống trang web thật.
 */
object ReceiverWebControl {

    private const val TAG = "ReceiverWebControl"
    private const val TIMEOUT_MS = 8_000

    /**
     * Mô tả một lệnh web.
     * @param path đường dẫn sau host, bắt đầu bằng "/" (chưa gồm urlStringId và "_")
     */
    data class WebCommand(val path: String, val method: String = "GET")

    /** Khởi động lại máy thu — máy boot lại, mất kết nối ~30 giây. */
    val REBOOT = WebCommand("/reboot_system.cmd")

    /** TẮT NGUỒN máy thu — phải bấm nút nguồn vật lý để bật lại. */
    val POWER_OFF = WebCommand("/power_off_set.cmd")

    /** Trạng thái nguồn/pin — trang Activity Status tự gọi lệnh này theo chu kỳ. */
    val POWER_STATUS = WebCommand("/power_status_get.cmd")

    /**
     * SSL cho thiết bị LAN dùng chứng thư tự ký.
     *
     * LƯU Ý: chỉ bỏ kiểm tra chứng thư cho kết nối TRỰC TIẾP tới máy thu trong
     * mạng riêng của máy (192.168.x.x). Không dùng SSLContext này cho bất kỳ
     * kết nối Internet nào khác.
     */
    private val insecureSslContext: SSLContext by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
    }

    /**
     * Gửi lệnh xuống máy thu.
     *
     * @param host địa chỉ máy thu (thường = gateway WiFi, VD "192.168.1.1")
     * @param user tài khoản đang đăng nhập trang web (mặc định "admin")
     */
    suspend fun send(
        context : Context,
        host    : String,
        cmd     : WebCommand,
        user    : String = "admin"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ts  = System.currentTimeMillis()
            val url = URL("https://$host${cmd.path}?urlStringId=$user&_=$ts")

            // QUAN TRỌNG: ép request đi qua WiFi.
            // Khi NTRIP chạy, app đã bind process sang 4G → kết nối mặc định
            // sẽ KHÔNG tới được địa chỉ LAN của máy thu (lỗi "failed to connect").
            val wifi = WifiInfoHelper.wifiNetwork(context)
            val raw  = wifi?.openConnection(url) ?: url.openConnection()

            val conn = (raw as HttpURLConnection).apply {
                if (this is HttpsURLConnection) {
                    // Máy thu dùng chứng thư tự ký cho IP nội bộ → bỏ kiểm tra
                    sslSocketFactory = insecureSslContext.socketFactory
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
                requestMethod  = cmd.method
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Referer", "https://$host/pc/WebForm/GnssSet/GnssReset.html")
                setRequestProperty("Cache-Control", "no-cache")
            }

            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            conn.disconnect()

            Log.d(TAG, "${cmd.method} ${cmd.path} → HTTP $code  ${text.take(120)}")
            if (code !in 200..299) throw Exception("Máy trả về HTTP $code")
            text
        }
    }

    /** Tắt nguồn máy thu qua WiFi. */
    suspend fun powerOff(context: Context, host: String, user: String = "admin") =
        send(context, host, POWER_OFF, user)

    /** Khởi động lại máy thu qua WiFi. */
    suspend fun reboot(context: Context, host: String, user: String = "admin") =
        send(context, host, REBOOT, user)

    /**
     * Đọc DUNG LƯỢNG PIN máy thu (%).
     *
     * Máy trả JSON nhưng chưa rõ tên trường, nên parser dò theo 2 tầng:
     *   1. Duyệt đệ quy mọi khoá JSON, lấy khoá có tên gợi ý pin
     *      (battery / power / capacity / soc / level / percent / elec / remain).
     *   2. Không thấy thì bắt số 0–100 đầu tiên kèm dấu % trong chuỗi trả về.
     *
     * @return phần trăm pin (0–100), hoặc null nếu không đọc được.
     */
    suspend fun fetchBattery(context: Context, host: String, user: String = "admin"): Int? {
        val body = send(context, host, POWER_STATUS, user).getOrNull() ?: return null
        Log.d(TAG, "power_status_get raw: ${body.take(400)}")   // để đối chiếu tên trường thật
        return parseBattery(body)
    }

    /**
     * Tách % pin từ chuỗi JSON trả về.
     *
     * Định dạng thật của máy Sinov M6 Pro (đã bắt được):
     *   {"volt_bat1":"69.0", "awk":"rsps", "volt_bat2":"69.0", "power_mod":"inter"}
     *
     * Máy có HAI khay pin (bat1/bat2). Dù tên trường là "volt_", giá trị KHÔNG phải
     * vôn (69 V là bất khả thi với pin Li-ion 7.4 V) mà là PHẦN TRĂM — khớp với số %
     * hiển thị trên trang Activity Status.
     *
     * Lấy giá trị LỚN HƠN trong hai viên: đó là viên còn dùng được lâu nhất,
     * phản ánh đúng "còn đo được bao lâu".
     */
    fun parseBattery(body: String): Int? {
        // ── Tầng 0: đúng schema của máy ──
        runCatching {
            val o = org.json.JSONObject(body)
            val b1 = o.optString("volt_bat1").toDoubleOrNull()
            val b2 = o.optString("volt_bat2").toDoubleOrNull()
            val best = listOfNotNull(b1, b2).filter { it in 0.0..100.0 }.maxOrNull()
            if (best != null) return best.toInt()
        }

        val keyHints = listOf(
            "battery", "batt", "bat", "power", "capacity", "soc",
            "level", "percent", "elec", "remain", "quantity"
        )

        // ── Tầng 1: duyệt JSON theo khoá (máy khác schema) ──
        runCatching {
            val root = org.json.JSONObject(body)
            findByKey(root, keyHints)?.let { return it }
        }

        // ── Tầng 2: bắt "85%" hoặc "battery":85 trong chuỗi thô ──
        Regex("""(\d{1,3})\s*%""").find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..100 }?.let { return it }
        Regex("""(?i)"[^"]*(?:batt|power|capacity|soc)[^"]*"\s*:\s*"?(\d{1,3})""")
            .find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..100 }?.let { return it }

        return null
    }

    /** Duyệt đệ quy JSON, trả giá trị 0–100 của khoá khớp gợi ý. */
    private fun findByKey(obj: org.json.JSONObject, hints: List<String>): Int? {
        val keys = obj.keys()
        var nested: Int? = null
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            when (v) {
                is org.json.JSONObject -> findByKey(v, hints)?.let { nested = it }
                is org.json.JSONArray  -> for (i in 0 until v.length()) {
                    (v.opt(i) as? org.json.JSONObject)?.let { o -> findByKey(o, hints)?.let { nested = it } }
                }
                else -> {
                    val kl = k.lowercase()
                    if (hints.any { kl.contains(it) }) {
                        val n = when (v) {
                            is Int    -> v
                            is Double -> v.toInt()
                            is String -> v.trim().removeSuffix("%").trim().toDoubleOrNull()?.toInt()
                            else      -> null
                        }
                        if (n != null && n in 0..100) return n
                    }
                }
            }
        }
        return nested
    }
}
