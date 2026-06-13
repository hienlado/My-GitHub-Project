package com.hien.rtkmultidevice.core.gnss.ntrip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import android.util.Log
import com.hien.rtkmultidevice.domain.model.NtripState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

/**
 * NtripClient — Kết nối đến NTRIP Caster để nhận dữ liệu hiệu chỉnh RTCM.
 *
 * Quy trình NTRIP v1 (tiêu chuẩn):
 *   1. Kết nối TCP đến Caster (host:port)
 *   2. Gửi HTTP/1.0 GET request với thông tin xác thực + Ntrip-GGA (nếu có)
 *   3. Nhận "ICY 200 OK" hoặc "HTTP/1.x 200 OK" xác nhận kết nối
 *   4. Nhận stream RTCM 3.x liên tục (binary, không có wrapper)
 *   5. Gửi GGA định kỳ qua cùng TCP socket để caster biết vị trí rover
 *
 * Các điểm đã fix so với phiên bản cũ:
 *   ✅ KHÔNG gửi "Ntrip-Version: 2.0" — tránh caster trả về chunked encoding
 *   ✅ KHÔNG gửi "Connection: close" — giữ kết nối TCP mãi mãi
 *   ✅ Gửi "Ntrip-GGA:" trong HTTP header nếu có vị trí → VRS caster cần điều này
 *   ✅ Đọc và log TOÀN BỘ response header (không chỉ dòng đầu)
 *   ✅ Phát hiện chunked encoding → strip chunk size lines trước khi forward RTCM
 *   ✅ Log cảnh báo nếu byte đầu không phải 0xD3 (RTCM3 sync byte)
 *   ✅ readRtcm và readChunkedRtcm tách biệt để code rõ ràng
 */
/**
 * @param context Application context — dùng để buộc socket NTRIP đi qua mạng di động (4G/5G).
 *
 * Vấn đề: khi điện thoại kết nối WiFi hotspot RTK (không có internet) + Data 4G cùng lúc,
 * socket mặc định có thể bị route qua WiFi → NTRIP timeout vì WiFi hotspot không có internet.
 *
 * Giải pháp: dùng ConnectivityManager lấy cellular network, tạo socket qua cellular.
 * Fallback về Socket() thông thường nếu không có 4G.
 */
class NtripClient(config: NtripConfig, private val context: Context? = null) {
    private val config = config.normalized()

    companion object {
        private const val TAG = "NtripClient"
        private const val BUFFER_SIZE = 4096
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val RTCM3_SYNC_BYTE = 0xD3
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var isChunkedResponse = false

    private var rtcmBytesReceived: Long = 0L
    private var rtcmPacketsReceived: Int = 0
    private var ggaSentCount: Int = 0

    // ── RTCM3 Frame Assembler buffer ────────────────────────
    // Tích lũy raw bytes giữa các lần read để reconstruct frame hoàn chỉnh
    private val rtcmAccBuffer = java.io.ByteArrayOutputStream()
    private var rtcmDiscardedBytes = 0   // bytes bị bỏ qua (không phải RTCM3)

    // ── Trạng thái kết nối NTRIP ────────────────────────────
    private val _state = MutableStateFlow<NtripState>(NtripState.Disconnected)
    val state: StateFlow<NtripState> = _state.asStateFlow()

    /**
     * Kết nối đến NTRIP Caster theo chuẩn NTRIP v1.
     *
     * @param initialGga Câu GGA hiện tại của rover (tùy chọn).
     *   Nếu có, sẽ được gửi trong header "Ntrip-GGA:" của HTTP request.
     *   VRS caster (Virtual Reference Station) CẦN thông tin này để tạo
     *   trạm base ảo gần rover. Không có GGA → caster có thể không gửi RTCM.
     *
     * @return InputStream RTCM nếu kết nối thành công, null nếu thất bại.
     */
    suspend fun connect(initialGga: String? = null): InputStream? = withContext(Dispatchers.IO) {
        try {
            _state.value = NtripState.Connecting

            // ── Bước 1: Tạo TCP socket qua mạng di động ────────
            // requestNetwork() chủ động yêu cầu Android giữ cellular sống,
            // sau đó tạo socket qua cellular socketFactory trong 1 bước (tránh EPERM).
            val s = createCellularSocket(config.host, config.port)
            socket = s

            // ── Bước 2: Tạo HTTP GET request NTRIP v1 ──────────
            //
            // QUAN TRỌNG — lý do chọn HTTP/1.0 + không có Ntrip-Version:2.0:
            //   • HTTP/1.0 không hỗ trợ chunked transfer encoding
            //     → caster sẽ gửi raw RTCM bytes, không có chunk wrapper
            //   • Thêm "Ntrip-Version: Ntrip/2.0" có thể khiến một số caster
            //     trả về HTTP/1.1 với chunked encoding, làm hỏng RTCM stream
            //   • NTRIP v1 là protocol phổ biến nhất và được hỗ trợ rộng rãi
            //
            // "Ntrip-GGA:" trong header:
            //   • VRS (Virtual Reference Station) caster CẦN vị trí rover
            //     ngay từ HTTP request để tính toán correction cho vị trí đó
            //   • Nếu không có GGA header, VRS caster có thể không gửi RTCM
            //     cho đến khi nhận được GGA qua socket (sau khi kết nối)
            //   • Gửi GGA trong header = rover nhận RTCM ngay lập tức
            val request = buildString {
                append("GET /${config.normalizedMountPoint} HTTP/1.0\r\n")
                append("Host: ${config.host}:${config.port}\r\n")
                append("User-Agent: NTRIP RTKFieldSoftware/1.0\r\n")
                append("Accept: */*\r\n")
                basicAuthHeader()?.let { append("Authorization: Basic $it\r\n") }
                // Gửi GGA ngay trong HTTP header nếu có vị trí hợp lệ
                initialGga?.trim()?.takeIf { it.isNotEmpty() }?.let { gga ->
                    append("Ntrip-GGA: $gga\r\n")
                    Log.d(TAG, "Gửi Ntrip-GGA header: $gga")
                }
                // KHÔNG thêm "Connection: close" — giữ kết nối TCP để nhận RTCM liên tục
                append("\r\n")
            }

            // ── Bước 3: Gửi HTTP request ───────────────────────
            s.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            s.outputStream.flush()
            Log.d(TAG, "NTRIP request gửi đến ${config.host}:${config.port}/${config.normalizedMountPoint}")

            // ── Bước 4: Đọc và kiểm tra response header ────────
            val input = s.inputStream
            val fullHeader = readFullResponseHeader(input)
            Log.d(TAG, "NTRIP response header:\n$fullHeader")

            val firstLine = fullHeader.lines().firstOrNull()?.trim() ?: ""
            if (!isSuccessResponse(firstLine)) {
                val errorMsg = fullHeader.ifEmpty { firstLine }
                _state.value = NtripState.Error("Caster từ chối: $firstLine")
                s.close()
                return@withContext null
            }

            // Phát hiện chunked encoding (phòng ngừa — không nên xảy ra với HTTP/1.0)
            isChunkedResponse = fullHeader.lowercase().contains("transfer-encoding: chunked")
            if (isChunkedResponse) {
                Log.w(TAG, "Cảnh báo: Caster dùng chunked transfer encoding — sẽ tự động strip chunk headers")
            }

            inputStream = input
            _state.value = NtripState.Connected(config.normalizedMountPoint)
            Log.d(TAG, "NTRIP kết nối thành công: ${config.host}:${config.port}/${config.normalizedMountPoint}")
            input

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kết nối NTRIP: ${e.message}")
            _state.value = NtripState.Error(e.message ?: "Lỗi không xác định")
            null
        }
    }

    /**
     * Đọc RTCM bytes từ stream và forward đến thiết bị GNSS.
     * Tự động xử lý chunked encoding nếu caster dùng HTTP/1.1.
     *
     * @param onRtcmReceived Callback nhận RTCM data để gửi đến thiết bị GNSS
     */
    suspend fun readRtcm(
        onRtcmReceived: suspend (ByteArray) -> Unit
    ) = withContext(Dispatchers.IO) {
        val input = inputStream ?: return@withContext

        try {
            if (isChunkedResponse) {
                readChunkedRtcm(input, onRtcmReceived)
            } else {
                readRawRtcm(input, onRtcmReceived)
            }
        } catch (e: Exception) {
            Log.w(TAG, "RTCM stream kết thúc: ${e.message}")
            _state.value = NtripState.Disconnected
        }
    }

    /**
     * Đọc raw RTCM stream và reconstruct RTCM3 frames hoàn chỉnh.
     *
     * Vấn đề phổ biến với NTRIP streams:
     *   • Caster có thể prepend N bytes "lạ" trước mỗi chunk RTCM3
     *   • Stream có thể bắt đầu giữa chừng một RTCM3 frame
     *   • Một số caster trộn lẫn format (ví dụ: RTCM2 message + RTCM3 message)
     *
     * Giải pháp — RTCM3 Frame Assembler:
     *   1. Tích lũy raw bytes vào buffer
     *   2. Quét tìm sync byte 0xD3 hợp lệ (reserved bits = 0)
     *   3. Đọc header để lấy msgLength (10-bit)
     *   4. Chờ đủ bytes cho frame hoàn chỉnh: 3 (header) + msgLength + 3 (CRC)
     *   5. Forward frame hoàn chỉnh đến thiết bị GNSS
     *   6. Bytes không thuộc RTCM3 → discard + log
     */
    private suspend fun readRawRtcm(
        input: InputStream,
        onRtcmReceived: suspend (ByteArray) -> Unit
    ) {
        val readBuf = ByteArray(BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(readBuf)
            if (bytesRead <= 0) break

            // Log raw preview lần đầu để debug format
            if (rtcmBytesReceived == 0L && rtcmDiscardedBytes == 0) {
                val preview = readBuf.take(bytesRead.coerceAtMost(20))
                    .joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Log.d(TAG, "RTCM stream bắt đầu — $bytesRead bytes — preview: $preview")
            }

            // Thêm vào accumulation buffer
            rtcmAccBuffer.write(readBuf, 0, bytesRead)

            // Trích xuất và forward các RTCM3 frame hoàn chỉnh
            val frames = extractRtcm3Frames()
            for (frame in frames) {
                rtcmBytesReceived += frame.size
                rtcmPacketsReceived++
                onRtcmReceived(frame)
                updateConnectedState()
            }
        }
    }

    /**
     * Quét accumulation buffer để tìm và trích xuất các RTCM3 frame hợp lệ.
     *
     * RTCM3 frame structure:
     *   Byte 0    : 0xD3 (preamble)
     *   Byte 1    : [reserved:6 bits = 000000][length bits 9-8 : 2 bits]
     *   Byte 2    : [length bits 7-0 : 8 bits]
     *   Bytes 3…N : message data (N = msgLength bytes)
     *   Bytes N+1…N+3 : CRC-24Q (3 bytes)
     *
     * Kiểm tra hợp lệ: (byte1 AND 0xFC) == 0  →  reserved bits = 0
     * Giới hạn: msgLength ≤ 1023 (10-bit field)
     *
     * @return Danh sách các RTCM3 frame hoàn chỉnh đã tìm thấy.
     */
    private fun extractRtcm3Frames(): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val data = rtcmAccBuffer.toByteArray()
        rtcmAccBuffer.reset()

        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF

            // ── Bước 1: Tìm sync byte 0xD3 ──────────────────
            if (b != RTCM3_SYNC_BYTE) {
                rtcmDiscardedBytes++
                i++
                continue
            }

            // ── Bước 2: Cần ít nhất 3 byte header ───────────
            if (i + 3 > data.size) break   // chờ thêm data

            val byte1 = data[i + 1].toInt() and 0xFF
            val byte2 = data[i + 2].toInt() and 0xFF

            // ── Bước 3: Validate reserved bits (bit 7-2 của byte1 = 0) ──
            // Đây là dấu hiệu phân biệt 0xD3 thực (RTCM3 preamble)
            // với byte 0xD3 xuất hiện ngẫu nhiên trong data payload
            if ((byte1 and 0xFC) != 0) {
                // 0xD3 này là data byte, không phải preamble
                rtcmDiscardedBytes++
                i++
                continue
            }

            // ── Bước 4: Đọc msgLength (10-bit) ───────────────
            val msgLength = ((byte1 and 0x03) shl 8) or byte2
            if (msgLength > 1023) {   // không thể > 10-bit max
                rtcmDiscardedBytes++
                i++
                continue
            }

            val frameSize = 3 + msgLength + 3   // header + data + CRC

            // ── Bước 5: Chờ đủ bytes cho frame hoàn chỉnh ───
            if (i + frameSize > data.size) break   // chờ thêm data

            // ── Bước 6: Frame hoàn chỉnh → extract ──────────
            val frame = data.copyOfRange(i, i + frameSize)
            frames.add(frame)

            // Log message type (12-bit, bits 7-0 của byte3 + bits 7-4 của byte4)
            if (msgLength >= 2) {
                val msgType = ((data[i + 3].toInt() and 0xFF) shl 4) or
                              ((data[i + 4].toInt() and 0xFF) ushr 4)
                Log.v(TAG, "RTCM3 ✓ type=$msgType  length=$msgLength bytes")
            }

            i += frameSize
        }

        // Giữ lại bytes chưa xử lý (partial frame) cho lần đọc tiếp theo
        if (i < data.size) {
            rtcmAccBuffer.write(data, i, data.size - i)
        }

        // Log tổng kết sau mỗi lô
        if (rtcmDiscardedBytes > 0 && frames.isNotEmpty()) {
            Log.w(TAG, "Frame assembler: ${frames.size} frame hợp lệ, $rtcmDiscardedBytes bytes bị bỏ qua")
        } else if (rtcmDiscardedBytes > 50 && frames.isEmpty()) {
            Log.w(TAG, "⚠ Không tìm thấy RTCM3 frame! $rtcmDiscardedBytes bytes bị discard.")
            Log.w(TAG, "⚠ Caster có thể đang dùng RTCM2.3, CMR, hoặc định dạng khác — kiểm tra sourcetable mountpoint.")
        }

        return frames
    }

    /**
     * Đọc RTCM từ HTTP chunked transfer encoding stream.
     *
     * Cấu trúc chunked:
     *   <hex_size>\r\n
     *   <chunk_data_bytes>\r\n
     *   ...
     *   0\r\n\r\n   ← chunk cuối (kết thúc)
     *
     * Cần strip các dòng hex_size trước khi forward RTCM đến thiết bị GNSS,
     * vì thiết bị GNSS không hiểu HTTP chunked encoding.
     */
    private suspend fun readChunkedRtcm(
        input: InputStream,
        onRtcmReceived: suspend (ByteArray) -> Unit
    ) {
        while (true) {
            // Đọc dòng hex size của chunk (ví dụ: "1A3\r\n")
            val sizeLine = readAsciiLine(input) ?: break
            val chunkSize = sizeLine.trim().toLongOrNull(16) ?: break
            if (chunkSize == 0L) {
                Log.d(TAG, "Chunked stream kết thúc (last chunk size = 0)")
                break
            }

            // Đọc đúng chunkSize bytes của RTCM data
            val chunkData = ByteArray(chunkSize.toInt())
            var totalRead = 0
            while (totalRead < chunkSize) {
                val read = input.read(chunkData, totalRead, (chunkSize - totalRead).toInt())
                if (read < 0) return
                totalRead += read
            }

            // Skip trailing "\r\n" sau chunk data
            input.read()  // '\r'
            input.read()  // '\n'

            rtcmBytesReceived += chunkSize
            rtcmPacketsReceived++

            onRtcmReceived(chunkData)
            updateConnectedState()
        }
    }

    /**
     * Gửi câu GGA đến Caster để cập nhật vị trí rover.
     *
     * Gửi định kỳ (thường mỗi 5 giây) để:
     *   • Caster biết rover đang di chuyển → chọn base gần nhất
     *   • VRS caster cập nhật trạm base ảo theo vị trí mới
     *   • Giữ TCP connection alive
     *
     * Chỉ gọi khi rover đã có fix (fixQuality > 0), tức là GGA có vị trí thực.
     * Gửi GGA fixQuality=0 có thể làm VRS caster không gửi RTCM.
     */
    suspend fun sendGga(ggaSentence: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val line = ggaSentence.trim() + "\r\n"
            socket?.outputStream?.write(line.toByteArray(Charsets.US_ASCII))
            socket?.outputStream?.flush()
            ggaSentCount++
            updateConnectedState()
            Log.v(TAG, "GGA gửi #$ggaSentCount: ${ggaSentence.take(60)}")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Lỗi gửi GGA: ${e.message}")
            false
        }
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        inputStream = null
        isChunkedResponse = false
        _state.value = NtripState.Disconnected
        rtcmBytesReceived = 0L
        rtcmPacketsReceived = 0
        ggaSentCount = 0
        rtcmAccBuffer.reset()
        rtcmDiscardedBytes = 0
    }

    // ────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────

    /**
     * Đọc TOÀN BỘ HTTP response header cho đến khi gặp dòng trống.
     *
     * NTRIP v1 caster thường trả về:
     *   "ICY 200 OK\r\n\r\n"  (không có headers phụ)
     * NTRIP v2 / HTTP caster trả về:
     *   "HTTP/1.1 200 OK\r\nContent-Type: ...\r\nTransfer-Encoding: chunked\r\n\r\n"
     *
     * Trả về toàn bộ header string để log và phát hiện chunked encoding.
     */
    private fun readFullResponseHeader(input: InputStream): String {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
            // Kết thúc header khi gặp blank line: \r\n\r\n hoặc \n\n
            if (prev == '\n'.code && b == '\n'.code) break
            if (sb.endsWith("\r\n\r\n")) break
            prev = b
        }
        return sb.toString().trimEnd()
    }

    /**
     * Đọc một dòng ASCII từ InputStream (dừng tại '\n').
     * Dùng trong chunked encoding để đọc hex size line.
     * @return Nội dung dòng (không bao gồm '\n'), null nếu stream kết thúc.
     */
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun isSuccessResponse(firstLine: String): Boolean {
        val line = firstLine.trim()
        return line == "ICY 200 OK" ||
            Regex("""^HTTP/1\.[01]\s+200(\s|$)""").containsMatchIn(line)
    }

    /**
     * Tạo Socket kết nối đến NTRIP caster qua mạng di động (cellular).
     *
     * Bối cảnh: khi điện thoại kết nối đồng thời WiFi hotspot RTK (không có internet)
     * và Data 4G, Android route socket mặc định qua WiFi → NTRIP timeout/EPERM.
     *
     * Giải pháp 2 tầng:
     *   Tầng 1 — requestNetwork(): chủ động YÊU CẦU Android giữ cellular sống và
     *             cung cấp Network object. Đây là cách chính xác trên Android 13+
     *             (chỉ query allNetworks không đủ vì cellular có thể bị suspend).
     *   Tầng 2 — createSocket(host, port): tạo + connect trong 1 bước qua cellular
     *             socketFactory → tránh lỗi EPERM khi bind socket 2 lần.
     *
     * Fallback: nếu không có 4G hoặc timeout 12s, dùng socket mặc định (WiFi-only).
     */
    private suspend fun createCellularSocket(host: String, port: Int): Socket {
        val cm = context?.getSystemService(ConnectivityManager::class.java)
            ?: return socketDefault(host, port)

        // Bước 1: requestNetwork — yêu cầu Android cung cấp cellular network
        // Khác với allNetworks (passive query), requestNetwork() BẮT BUỘC OS
        // duy trì kết nối cellular ngay cả khi WiFi đang active và không có internet.
        val cellularNetwork: Network? = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }

                cm.requestNetwork(request, callback, CONNECT_TIMEOUT_MS)
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
            }
        }

        return if (cellularNetwork != null) {
            Log.d(TAG, "NTRIP socket qua cellular network (4G/5G) — tránh WiFi hotspot RTK")
            // createSocket(host, port) tạo + kết nối trong 1 bước — tránh EPERM Android 13
            runCatching {
                cellularNetwork.socketFactory.createSocket(host, port) as Socket
            }.getOrElse { e ->
                Log.w(TAG, "cellular socketFactory thất bại (${e.message}) → fallback socket mặc định")
                socketDefault(host, port)
            }
        } else {
            Log.d(TAG, "Không tìm được cellular network → dùng socket mặc định")
            socketDefault(host, port)
        }
    }

    private fun socketDefault(host: String, port: Int): Socket =
        Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

    private fun basicAuthHeader(): String? {
        if (config.username.isBlank() && config.password.isBlank()) return null
        return Base64.encodeToString(
            "${config.username}:${config.password}".toByteArray(StandardCharsets.ISO_8859_1),
            Base64.NO_WRAP
        )
    }

    private fun updateConnectedState() {
        _state.value = NtripState.Connected(
            mountPoint      = config.normalizedMountPoint,
            bytesReceived   = rtcmBytesReceived,
            packetsReceived = rtcmPacketsReceived,
            ggaSentCount    = ggaSentCount
        )
    }
}
