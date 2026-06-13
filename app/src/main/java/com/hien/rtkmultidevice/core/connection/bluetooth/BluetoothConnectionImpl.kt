package com.hien.rtkmultidevice.core.connection.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.hien.rtkmultidevice.core.connection.DeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

/**
 * BluetoothConnectionImpl — Kết nối BT Classic qua SPP (Serial Port Profile).
 *
 * Điểm khác biệt so với code cũ:
 *   ✅ Dùng Coroutines thay vì raw thread{}
 *   ✅ Flow<String> phát NMEA liên tục (thay vì vòng while thủ công)
 *   ✅ Trả về Result<Unit> thay vì Boolean
 *   ✅ Xử lý lỗi rõ ràng, không nuốt exception
 *
 * UUID "00001101..." là chuẩn Bluetooth Serial Port Profile (SPP)
 * — mọi máy RTK dùng Bluetooth serial đều dùng UUID này.
 */
@SuppressLint("MissingPermission")
class BluetoothConnectionImpl(
    private val device: BluetoothDevice
) : DeviceConnection {

    private var socket: BluetoothSocket? = null

    companion object {
        private const val TAG = "BluetoothConnection"

        /** UUID chuẩn SPP — Serial Port Profile */
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // ── Tên các UUID BT phổ biến để nhận dạng nhanh ──────
        private val KNOWN_UUIDS = mapOf(
            "00001101-0000-1000-8000-00805f9b34fb" to "SPP (Serial Port)",
            "00001100-0000-1000-8000-00805f9b34fb" to "DUN (Dial-up Networking)",
            "00001103-0000-1000-8000-00805f9b34fb" to "DUN-GW",
            "0000110a-0000-1000-8000-00805f9b34fb" to "Audio Source (A2DP)",
            "0000111e-0000-1000-8000-00805f9b34fb" to "HFP (Handsfree)",
            "00001105-0000-1000-8000-00805f9b34fb" to "OBEX OPP",
            "00001106-0000-1000-8000-00805f9b34fb" to "OBEX FTP",
            "00001116-0000-1000-8000-00805f9b34fb" to "NAP (Network Access)",
            "00001132-0000-1000-8000-00805f9b34fb" to "Message Access (MAP)"
        )
    }

    /**
     * Thực hiện kết nối BT trên Dispatchers.IO (thread nền).
     * suspend → không block UI thread trong khi chờ kết nối.
     *
     * Chiến lược kết nối 2 bước:
     *   1. Thử Secure RFCOMM trước (chuẩn, yêu cầu pair đúng)
     *   2. Nếu lỗi → fallback sang Insecure RFCOMM
     *      (một số thiết bị như T31U04294 từ chối secure connection
     *       hoặc dùng firmware không chuẩn SPP, cần insecure socket)
     */
    override suspend fun connect(): Result<Unit> =
        withContext(Dispatchers.IO) {
            // ── Log toàn bộ UUID mà thiết bị quảng bá ──────────
            // Quan trọng: giúp phát hiện nếu thiết bị có UUID riêng
            // cho RTCM input (khác với SPP UUID dùng cho NMEA output)
            logDeviceUuids()

            // Thử Secure RFCOMM trước
            val secureResult = runCatching {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                    ?: throw IllegalStateException("Không thể tạo socket")
            }

            if (secureResult.isSuccess) {
                Log.d(TAG, "Kết nối Secure RFCOMM thành công → ${device.name} [${device.address}]")
                return@withContext secureResult
            }
            Log.w(TAG, "Secure RFCOMM thất bại (${secureResult.exceptionOrNull()?.message}) → thử Insecure...")

            // Fallback: Insecure RFCOMM (không yêu cầu xác thực BT layer)
            val insecureResult = runCatching {
                socket?.runCatching { close() }   // đóng socket cũ nếu có
                socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                    ?: throw IllegalStateException("Không thể tạo insecure socket")
            }

            if (insecureResult.isSuccess) {
                Log.d(TAG, "Kết nối Insecure RFCOMM thành công → ${device.name}")
            } else {
                Log.e(TAG, "Kết nối thất bại hoàn toàn: ${insecureResult.exceptionOrNull()?.message}")
            }
            insecureResult
        }

    /**
     * Log tất cả BT service UUID mà thiết bị quảng bá.
     *
     * Mục đích chẩn đoán:
     *   • Phát hiện UUID dành riêng cho RTCM/Correction input
     *     (một số máy RTK như CHCNav, ComNav dùng UUID proprietary
     *      khác với SPP tiêu chuẩn để nhận dữ liệu hiệu chỉnh)
     *   • So sánh UUID app đang dùng (SPP) với danh sách thực tế
     *
     * Kết quả hiển thị trong logcat với tag "BluetoothConnection".
     * UUID UNKNOWN trong log = UUID proprietary của hãng → cần điều tra thêm.
     */
    private fun logDeviceUuids() {
        val name    = device.name ?: "Unknown"
        val address = device.address ?: "Unknown"
        val uuids   = device.uuids

        if (uuids.isNullOrEmpty()) {
            Log.w(TAG, "[$name / $address] Không có cached UUID — thiết bị chưa được SDP query")
            Log.w(TAG, "  → Có thể gọi device.fetchUuidsWithSdp() để lấy danh sách đầy đủ")
            return
        }

        Log.d(TAG, "╔══ UUID của thiết bị: $name [$address] — ${uuids.size} service ══")
        uuids.forEachIndexed { index, parcel ->
            val uuid    = parcel.uuid.toString().lowercase()
            val label   = KNOWN_UUIDS[uuid] ?: "⚠ UNKNOWN / Proprietary"
            val isSpp   = uuid == SPP_UUID.toString().lowercase()
            val marker  = if (isSpp) " ← App đang dùng cái này" else ""
            Log.d(TAG, "║  [${index + 1}] $uuid  ($label)$marker")
        }
        Log.d(TAG, "╚══ Hết danh sách UUID ══")
        Log.d(TAG, "  App dùng SPP UUID: ${SPP_UUID.toString().lowercase()}")
        Log.d(TAG, "  Nếu có UUID UNKNOWN ở trên → đó có thể là cổng nhận RTCM riêng")
    }

    /**
     * nmeaFlow — Flow phát từng dòng NMEA từ input stream.
     *
     * Cách hoạt động:
     *   1. Mở BufferedReader từ socket input stream
     *   2. Đọc từng dòng, emit vào Flow
     *   3. Khi kết nối đứt hoặc exception → Flow tự kết thúc
     *   4. flowOn(IO) → chạy trên thread nền, không block UI
     */
    override fun nmeaFlow(): Flow<String> = flow {
        val inputStream = socket?.inputStream
            ?: throw IllegalStateException("Socket chưa kết nối")

        try {
            while (true) {
                val line = inputStream.readAsciiLine() ?: break  // null = stream đóng
                if (line.isNotBlank()) {
                    emit(line.trim())                  // Phát dòng NMEA lên trên
                }
            }
        } catch (e: Exception) {
            // Kết nối bị ngắt — Flow kết thúc tự nhiên
            throw e
        }
    }.flowOn(Dispatchers.IO)  // Chạy trên background thread

    /**
     * Gửi bytes (RTCM data) đến thiết bị GNSS.
     * withContext(IO) → không block UI thread.
     */
    override suspend fun sendBytes(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val os = socket?.outputStream
                ?: throw IllegalStateException("Socket chưa kết nối — outputStream null")
            os.write(data)
            os.flush()
        }
    }

    /** Đóng socket và giải phóng tài nguyên */
    override fun disconnect() {
        runCatching { socket?.close() }
        socket = null
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
