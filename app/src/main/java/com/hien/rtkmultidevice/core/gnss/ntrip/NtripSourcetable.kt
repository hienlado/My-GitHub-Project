package com.hien.rtkmultidevice.core.gnss.ntrip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

// ═══════════════════════════════════════════════════════════
// NtripMountpointEntry — Thông tin một mountpoint trong sourcetable
// ═══════════════════════════════════════════════════════════

/**
 * Một dòng STR trong NTRIP sourcetable.
 *
 * Định dạng STR:
 *   STR;mountpoint;identifier;format;formatDetails;carrier;navSystem;
 *       network;country;lat;lon;nmea;solution;generator;comprEncr;
 *       authentication;fee;bitrate;misc
 *
 * Ví dụ dòng có VRS + datum 1021-1027:
 *   STR;VRS_VN2000;VN2000 VRS;RTCM 3.2;1006(10),1021(1),1022(1),1023(1),
 *       1024(1),1025(1),1075(1),1085(1),1095(1),1115(1);2;GPS+GLO+GAL+BDS;...
 */
data class NtripMountpointEntry(
    val mountpoint    : String,
    val identifier    : String,
    val format        : String,          // "RTCM 3.2", "CMR+", v.v.
    val formatDetails : String,          // "1006(10),1075(1),..." — danh sách msg type
    val navSystem     : String,          // "GPS+GLO+GAL+BDS"
    val country       : String,
    val latitude      : Double,
    val longitude     : Double,
    val solution      : Int,             // 0=unknown, 1=single-base, 2=network/VRS
    val bitrate       : Int
) {
    // ── Helper: danh sách message type dưới dạng Set<Int> ──

    /** Danh sách RTCM3 message types mà mountpoint này cung cấp. */
    val messageTypes: Set<Int> by lazy {
        val regex = Regex("""(\d+)(?:\(\d+\))?""")
        regex.findAll(formatDetails)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()
    }

    /** Có cung cấp bộ Datum Transformation (1021-1027) không? */
    val hasDatumTransformation: Boolean
        get() = messageTypes.any { it in 1021..1027 }

    /** Có cung cấp MSM corrections không? (cần để RTK) */
    val hasMsmCorrections: Boolean
        get() = messageTypes.any { it in 1071..1127 }

    /** Có phải VRS (Virtual Reference Station) không? */
    val isVrs: Boolean
        get() = solution == 2

    /**
     * Label hiển thị trong UI:
     *   [VRS] mountpoint — identifier
     *   ✓ Datum 1021-1027 | GPS+GLO+GAL+BDS
     */
    val displayLabel: String
        get() = buildString {
            if (isVrs) append("[VRS] ")
            append(mountpoint)
            if (identifier.isNotBlank() && identifier != mountpoint) {
                append("  —  $identifier")
            }
        }

    val displaySubtitle: String
        get() = buildString {
            append(format)
            if (hasDatumTransformation) append("  ✓ Datum 1021-1027")
            if (hasMsmCorrections) append("  ✓ MSM")
            if (navSystem.isNotBlank()) append("  |  $navSystem")
        }
}

// ═══════════════════════════════════════════════════════════
// NtripSourcetableFetcher — Tải và parse sourcetable
// ═══════════════════════════════════════════════════════════

/**
 * Tải NTRIP sourcetable từ caster và parse thành danh sách mountpoint.
 *
 * NTRIP sourcetable request:
 *   GET / HTTP/1.0\r\n
 *   Host: host:port\r\n
 *   User-Agent: NTRIP ...\r\n
 *   Authorization: Basic ...\r\n
 *   \r\n
 *
 * Response:
 *   SOURCETABLE 200 OK\r\n
 *   ...\r\n
 *   \r\n
 *   CAS;...\n
 *   NET;...\n
 *   STR;mountpoint;...\n   ← các dòng này chứa mountpoint info
 *   ENDSOURCETABLE\n
 */
object NtripSourcetableFetcher {

    private const val TAG = "NtripSourcetable"
    private const val TIMEOUT_MS = 10_000

    /**
     * Tạo socket tới caster, ƯU TIÊN mạng di động (giống NtripClient.createCellularSocket).
     *
     * Lý do: khi điện thoại nối WiFi hotspot của đầu thu RTK (không có internet),
     * Android route mọi socket mặc định qua WiFi → connect timeout 10s.
     * requestNetwork(TRANSPORT_CELLULAR) + socketFactory ép đi qua 4G/5G.
     *
     * Fallback: không có cellular → dùng socket mặc định (WiFi/Ethernet).
     */
    private suspend fun createSocketPreferCellular(
        appContext: Context?, host: String, port: Int
    ): Socket {
        val cm = appContext?.getSystemService(ConnectivityManager::class.java)
            ?: return socketDefault(host, port)

        // Nếu mạng đang dùng đã có internet thật (validated) → dùng luôn, khỏi ép cellular
        val activeOk = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (activeOk) return socketDefault(host, port)

        val cellular: Network? = withTimeoutOrNull(TIMEOUT_MS.toLong()) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) }
                    override fun onUnavailable() { if (cont.isActive) cont.resume(null) }
                }
                runCatching { cm.requestNetwork(request, cb, TIMEOUT_MS) }
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        }

        return if (cellular != null) {
            Log.d(TAG, "Sourcetable qua cellular (4G/5G) — bỏ qua WiFi không internet")
            runCatching { cellular.socketFactory.createSocket(host, port) as Socket }
                .getOrElse {
                    Log.w(TAG, "cellular socketFactory lỗi (${it.message}) → socket mặc định")
                    socketDefault(host, port)
                }
        } else {
            Log.d(TAG, "Không có cellular → socket mặc định")
            socketDefault(host, port)
        }
    }

    private fun socketDefault(host: String, port: Int): Socket =
        Socket().apply { connect(java.net.InetSocketAddress(host, port), TIMEOUT_MS) }

    /**
     * Tải sourcetable và trả về danh sách mountpoint.
     * Mountpoint có Datum Transformation (1021-1027) sẽ được đặt đầu danh sách.
     *
     * @return Result.success(list) hoặc Result.failure(exception)
     */
    suspend fun fetch(
        host     : String,
        port     : Int,
        username : String = "",
        password : String = "",
        /**
         * Context để ưu tiên mạng di động (4G/5G).
         * BẮT BUỘC khi điện thoại đang nối WiFi hotspot của đầu thu (không có internet) —
         * nếu null, socket đi qua WiFi và sẽ TIMEOUT giống NtripClient trước đây.
         */
        appContext: Context? = null
    ): Result<List<NtripMountpointEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = createSocketPreferCellular(appContext, host.trim(), port).apply {
                soTimeout = TIMEOUT_MS
            }

            socket.use { s ->
                // Gửi request lấy sourcetable (GET /)
                val request = buildString {
                    append("GET / HTTP/1.0\r\n")
                    append("Host: $host:$port\r\n")
                    append("User-Agent: NTRIP RTKFieldSoftware/1.0\r\n")
                    append("Accept: */*\r\n")
                    if (username.isNotBlank() || password.isNotBlank()) {
                        val credentials = Base64.encodeToString(
                            "$username:$password".toByteArray(StandardCharsets.ISO_8859_1),
                            Base64.NO_WRAP
                        )
                        append("Authorization: Basic $credentials\r\n")
                    }
                    append("\r\n")
                }

                s.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                s.outputStream.flush()

                // Đọc toàn bộ response
                val response = s.inputStream.bufferedReader(Charsets.UTF_8).readText()
                Log.d(TAG, "Sourcetable response: ${response.take(200)}...")

                // Kiểm tra response hợp lệ
                val firstLine = response.lines().firstOrNull()?.trim() ?: ""
                if (!firstLine.contains("200")) {
                    throw Exception("Sourcetable từ chối: $firstLine")
                }

                // Parse các dòng STR
                val entries = response.lines()
                    .filter { it.startsWith("STR;") }
                    .mapNotNull { parseStrLine(it) }

                Log.d(TAG, "Tìm thấy ${entries.size} mountpoint")

                // Sắp xếp: VRS+Datum transformation trước, rồi VRS, rồi phần còn lại
                entries.sortedWith(
                    compareByDescending<NtripMountpointEntry> { it.hasDatumTransformation }
                        .thenByDescending { it.isVrs }
                        .thenByDescending { it.hasMsmCorrections }
                        .thenBy { it.mountpoint }
                )
            }
        }.onFailure { e ->
            Log.e(TAG, "Lỗi tải sourcetable: ${e.message}")
        }
    }

    /**
     * Parse một dòng STR trong sourcetable.
     * STR;mountpoint;identifier;format;formatDetails;carrier;navSystem;
     *     network;country;lat;lon;nmea;solution;generator;comprEncr;
     *     authentication;fee;bitrate;misc
     * Index:   0         1          2       3      4           5      6
     *          7        8      9   10    11    12     13         14
     *          15         16    17      18
     */
    private fun parseStrLine(line: String): NtripMountpointEntry? {
        return runCatching {
            val f = line.split(";")
            if (f.size < 18) return null    // dòng không đủ fields

            NtripMountpointEntry(
                mountpoint    = f[1].trim(),
                identifier    = f.getOrElse(2) { "" }.trim(),
                format        = f.getOrElse(3) { "" }.trim(),
                formatDetails = f.getOrElse(4) { "" }.trim(),
                navSystem     = f.getOrElse(6) { "" }.trim(),
                country       = f.getOrElse(8) { "" }.trim(),
                latitude      = f.getOrElse(9)  { "0" }.toDoubleOrNull() ?: 0.0,
                longitude     = f.getOrElse(10) { "0" }.toDoubleOrNull() ?: 0.0,
                solution      = f.getOrElse(12) { "0" }.toIntOrNull() ?: 0,
                bitrate       = f.getOrElse(17) { "0" }.toIntOrNull() ?: 0
            )
        }.getOrNull()
    }
}
