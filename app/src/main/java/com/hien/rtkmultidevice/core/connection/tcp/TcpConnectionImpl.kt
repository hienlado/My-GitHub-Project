package com.hien.rtkmultidevice.core.connection.tcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hien.rtkmultidevice.core.connection.DeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.Socket

/**
 * TcpConnectionImpl — Kết nối WiFi/TCP đến thiết bị GNSS.
 *
 * Sử dụng khi thiết bị tạo WiFi hotspot và phát NMEA qua TCP:
 *   - ComNav T30  : IP 192.168.0.1, Port 9001 (COM1) hoặc 9002 (COM2)
 *   - Sinov/CHCNav: IP 192.168.1.1, Port 9901 (cần bật TCP Server trong IO Settings)
 *   - Emlid Reach  : IP 192.168.42.1, Port 9001
 *
 * Lưu ý: kênh TCP thường là BIDIRECTIONAL — nhận NMEA + gửi RTCM đều được.
 * Khác với BT SPP của một số thiết bị chỉ là output-only.
 *
 * Context được truyền vào để buộc Socket đi qua WiFi network —
 * tránh trường hợp Android tự chuyển sang 4G khi WiFi hotspot không có internet.
 */
class TcpConnectionImpl(
    private val host: String,
    private val port: Int,
    private val context: Context
) : DeviceConnection {

    private var socket: Socket? = null

    /**
     * Kết nối TCP trên Dispatchers.IO.
     *
     * Dùng createWifiSocket() thay vì Socket() trực tiếp để buộc kết nối
     * đi qua WiFi interface — fix lỗi ETIMEDOUT khi điện thoại có cả
     * WiFi (hotspot RTK) và Data 4G cùng lúc.
     */
    override suspend fun connect(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = createWifiSocket()
                s.soTimeout = 15_000  // 15 giây timeout đọc dữ liệu (NMEA ~1Hz, field use cần buffer)
                socket = s
            }
        }

    /**
     * Tạo Socket buộc qua WiFi network.
     *
     * Vấn đề: khi điện thoại kết nối cả WiFi hotspot RTK (không có internet)
     * và Data 4G, Android "Adaptive Connectivity" tự route traffic qua 4G.
     * Socket(host, port) sẽ đi qua 4G → 192.168.1.1 không tồn tại trên 4G
     * → ETIMEDOUT.
     *
     * Giải pháp: dùng ConnectivityManager để lấy WiFi Network object,
     * rồi tạo Socket qua network.socketFactory của WiFi network đó.
     *
     * Fallback về Socket() thông thường nếu không tìm được WiFi network
     * (thiết bị WiFi-only, hoặc đã tắt Data).
     */
    private fun createWifiSocket(): Socket {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifiNetwork = cm?.allNetworks?.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        return if (wifiNetwork != null) {
            wifiNetwork.socketFactory.createSocket(host, port) as Socket
        } else {
            // Fallback: WiFi-only hoặc không tìm được WiFi network
            Socket(host, port)
        }
    }

    /**
     * Flow NMEA qua TCP.
     *
     * Áp dụng stripBinaryPrefix() giống NmeaParser.extractNmea():
     * ComNav T30 (và một số thiết bị khác) có thể gửi binary proprietary data
     * ngay trước câu NMEA trên cùng một dòng TCP — strip phần trước '$' để đảm
     * bảo NmeaParser nhận đúng câu thuần NMEA.
     */
    override fun nmeaFlow(): Flow<String> = flow {
        val inputStream = socket?.inputStream
            ?: throw IllegalStateException("TCP socket chưa kết nối")

        try {
            while (true) {
                val line = inputStream.readAsciiLine() ?: break
                if (line.isNotBlank()) {
                    emit(stripBinaryPrefix(line))
                }
            }
        } catch (e: Exception) {
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /** Gửi RTCM bytes qua TCP */
    override suspend fun sendBytes(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val os = socket?.outputStream
                ?: throw IllegalStateException("TCP socket chưa kết nối")
            os.write(data)
            os.flush()
        }
    }

    override fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    /**
     * Strip binary garbage trước '$' trên cùng một dòng.
     *
     * ComNav T30 (và CHCNav) đôi khi gửi dữ liệu binary proprietary ngay trước
     * câu NMEA mà không có '\n' phân cách — ví dụ:
     *   [80 bytes binary]$GPGGA,152223.00,...*43
     *
     * Nếu không strip, split(",") sẽ bị lệch field index do binary có nhiều comma.
     * Giải pháp: tìm '$' đầu tiên, bỏ tất cả phía trước.
     *
     * Tương tự NmeaParser.extractNmea() nhưng ở tầng transport để
     * GnssDataManager nhận được dòng sạch ngay từ đầu.
     */
    private fun stripBinaryPrefix(raw: String): String {
        val start = raw.indexOf('$')
        return if (start > 0) raw.substring(start) else raw
    }

    private fun InputStream.readAsciiLine(): String? {
        val bytes = ArrayList<Byte>(96)
        while (true) {
            val value = read()
            if (value < 0) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            if (value == '\n'.code) {
                return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            }
            bytes.add(value.toByte())
        }
    }
}
