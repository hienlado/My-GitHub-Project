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
}
