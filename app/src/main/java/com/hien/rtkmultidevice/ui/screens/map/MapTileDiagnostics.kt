package com.hien.rtkmultidevice.ui.screens.map

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL

/**
 * MapTileDiagnostics — Chẩn đoán tại sao bản đồ nền không hiển thị.
 *
 * Tải thử MỘT tile (zoom 6, khu vực Việt Nam) bằng đúng User-Agent mà
 * osmdroid sẽ dùng. Trả về:
 *   • null            → server OK (HTTP 200) — nếu bản đồ vẫn trắng thì lỗi nằm ở cache/render
 *   • chuỗi thông báo → nguyên nhân cụ thể (403 = bị chặn UA, timeout = không có internet…)
 *
 * Kết quả hiện qua snackbar giúp xác định lỗi ngay ngoài hiện trường.
 */
object MapTileDiagnostics {

    /** Tile mẫu: z=6, x=51, y=29 ≈ khu vực Việt Nam */
    private const val Z = 6; private const val X = 51; private const val Y = 29

    suspend fun probe(tileSource: MapTileSource): String? = withContext(Dispatchers.IO) {
        val url = try {
            tileSource.toOsmdroidSource()
                .getTileURLString(MapTileIndex.getTileIndex(Z, X, Y))
        } catch (e: Exception) {
            return@withContext "Không tạo được URL tile: ${e.message}"
        }

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", MapTileSource.userAgentFor(tileSource))
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            val code = conn.responseCode
            val size = try { conn.inputStream.use { it.readBytes().size } } catch (_: Exception) { 0 }
            conn.disconnect()
            Log.d("MapTileDiag", "${tileSource.label}: HTTP $code, $size bytes — $url")

            when {
                code == 200 && size > 0 -> null   // OK
                code == 403 -> "⚠ ${tileSource.label}: server từ chối (HTTP 403 — UA bị chặn). Thử đổi nguồn bản đồ khác."
                code == 429 -> "⚠ ${tileSource.label}: bị giới hạn tải (HTTP 429). Thử lại sau hoặc đổi nguồn."
                else        -> "⚠ ${tileSource.label}: HTTP $code — thử đổi nguồn bản đồ."
            }
        } catch (e: java.net.UnknownHostException) {
            "⚠ Không phân giải được tên miền — thiết bị KHÔNG có Internet (WiFi RTK không có mạng?). Bật dữ liệu di động."
        } catch (e: java.net.SocketTimeoutException) {
            "⚠ Hết thời gian chờ tải tile — mạng yếu hoặc WiFi không có Internet."
        } catch (e: Exception) {
            Log.e("MapTileDiag", "probe lỗi", e)
            "⚠ Lỗi tải tile: ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }
}
