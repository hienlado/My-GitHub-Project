package com.hien.rtkmultidevice.ui.screens.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CloudMapLoader — Tải bản đồ địa chính (GeoJSON) từ server Cloud về app.
 *
 * Hợp đồng API (khớp Cloud Function mẫu):
 *   GET  <baseUrl>?layer=<tên_lớp>
 *   Header: X-API-Key: <khoá>
 *   Trả về: GeoJSON (FeatureCollection), toạ độ VN-2000 (N,E) hoặc WGS-84.
 *
 * App tự nhận diện hệ toạ độ (VectorLayerImporter.resolve) và chuyển VN-2000
 * sang lat/lon bằng CHÍNH bộ tham số của app để khớp vị trí RTK.
 *
 * Dùng HttpURLConnection (JDK) — không cần thêm thư viện.
 */
object CloudMapLoader {

    /**
     * @param baseUrl  URL endpoint (vd https://region-project.cloudfunctions.net/getCadastral)
     * @param apiKey   Khoá gửi qua header X-API-Key (để trống nếu server công khai)
     * @param layer    Tên lớp/tờ bản đồ (?layer=…); để trống nếu server chỉ có 1 lớp
     * @param hintCm   Kinh tuyến trục gợi ý cho VN-2000 (từ cài đặt dự án)
     */
    suspend fun fetchGeoJson(
        baseUrl : String,
        apiKey  : String,
        layer   : String,
        hintCm  : Double = 0.0
    ): VectorLayerImporter.ImportResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank())
            return@withContext VectorLayerImporter.ImportResult.Error("Chưa cấu hình URL server bản đồ")

        var conn: HttpURLConnection? = null
        try {
            val urlStr = buildString {
                append(baseUrl.trim())
                if (layer.isNotBlank()) {
                    append(if (baseUrl.contains("?")) "&" else "?")
                    append("layer=").append(URLEncoder.encode(layer.trim(), "UTF-8"))
                }
            }
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod  = "GET"
                connectTimeout = 15_000
                readTimeout    = 30_000
                if (apiKey.isNotBlank()) setRequestProperty("X-API-Key", apiKey.trim())
                setRequestProperty("Accept", "application/geo+json, application/json")
            }
            val code = conn.responseCode
            if (code == 401 || code == 403)
                return@withContext VectorLayerImporter.ImportResult.Error("Sai API key hoặc không có quyền (HTTP $code)")
            if (code == 404)
                return@withContext VectorLayerImporter.ImportResult.Error("Không tìm thấy lớp \"$layer\" trên server (404)")
            if (code != 200)
                return@withContext VectorLayerImporter.ImportResult.Error("Server trả HTTP $code")

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank())
                return@withContext VectorLayerImporter.ImportResult.Error("Server trả dữ liệu rỗng")

            val name  = layer.ifBlank { "Địa chính" }
            val vlayer = VectorLayerImporter.parseGeoJsonText(text, name, hintCm)
            if (vlayer.isEmpty)
                VectorLayerImporter.ImportResult.Error("Không có đối tượng hình học hợp lệ trong GeoJSON")
            else
                VectorLayerImporter.ImportResult.Success(vlayer, name)
        } catch (e: Exception) {
            VectorLayerImporter.ImportResult.Error("Lỗi tải bản đồ: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
