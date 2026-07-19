package com.hien.rtkmultidevice.ui.screens.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CadastralLocalSource — nguồn bản đồ địa chính OFFLINE (đọc từ bộ nhớ máy).
 *
 * Cấu trúc dữ liệu (chép từ output pipeline: thư mục "sheets/") vào:
 *     <getExternalFilesDir>/cadastral/sheets/<xã>/<tờ>.geojson   (VN-2000)
 *     <getExternalFilesDir>/cadastral/sheets/_index.json
 *
 * Đường dẫn thật trên máy (qua USB): Android/data/com.hien.rtkmultidevice/files/cadastral/sheets/
 *
 * Dùng CHÍNH bộ parse của app (VectorLayerImporter.parseGeoJsonText) nên hệ toạ độ,
 * nhãn (số thửa/diện tích/loại đất) và khả năng đo/cắm mốc giống hệt chế độ Cloud.
 */
object CadastralLocalSource {

    fun root(context: Context): File = File(context.getExternalFilesDir(null), "cadastral")
    fun sheetsDir(context: Context): File = File(root(context), "sheets")

    /** Có sẵn dữ liệu offline hay chưa (đã chép sheets/_index.json vào máy). */
    fun hasData(context: Context): Boolean = File(sheetsDir(context), "_index.json").exists()

    /** Có chỉ mục tên chủ (sheets/_owners.json) để tìm theo chủ sử dụng. */
    fun hasOwners(context: Context): Boolean = File(sheetsDir(context), "_owners.json").exists()

    /** Một kết quả tìm theo tên chủ. */
    data class OwnerHit(
        val chu: String, val commune: String, val communeName: String,
        val to: String, val thua: String, val dienTich: String
    )

    private fun deaccent(s: String): String {
        // KHÔNG dùng regex \p{Mn} (một số máy Android không hỗ trợ -> crash).
        val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        val sb = StringBuilder(n.length)
        for (c in n) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
        }
        return sb.toString().replace('đ', 'd').replace('Đ', 'D').lowercase()
    }

    /**
     * Tìm thửa theo TÊN CHỦ (offline, không dấu, khớp chuỗi con).
     * _owners.json rất lớn (hàng trăm nghìn bản ghi) nên ĐỌC LUỒNG bằng JsonReader —
     * KHÔNG nạp cả file vào RAM (tránh OOM), chỉ giữ tối đa `limit` kết quả khớp.
     */
    suspend fun searchOwner(context: Context, query: String, limit: Int = 50): List<OwnerHit> =
        withContext(Dispatchers.IO) {
            val q = deaccent(query.trim())
            if (q.length < 2) return@withContext emptyList()
            val f = File(sheetsDir(context), "_owners.json")
            if (!f.exists()) return@withContext emptyList()
            val out = ArrayList<OwnerHit>(limit)
            try {
                android.util.JsonReader(
                    java.io.InputStreamReader(java.io.FileInputStream(f), Charsets.UTF_8)
                ).use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        var chu = ""; var commune = ""; var communeName = ""
                        var to = ""; var thua = ""; var dienTich = ""
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "chu" -> chu = r.nextString()
                                "commune" -> commune = r.nextString()
                                "communeName" -> communeName = r.nextString()
                                "to" -> to = r.nextString()
                                "thua" -> thua = r.nextString()
                                "dienTich" -> dienTich = r.nextString()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (chu.isNotEmpty() && deaccent(chu).contains(q)) {
                            out.add(OwnerHit(chu, commune, communeName, to, thua, dienTich))
                            if (out.size >= limit) break
                        }
                    }
                }
            } catch (e: Throwable) { /* trả về phần đã tìm được */ }
            out
        }

    /** Tải 1 tờ từ file cục bộ -> VectorLayer (VN-2000, sẵn sàng đo/cắm mốc). */
    suspend fun loadSheet(
        context: Context, communeSlug: String, to: String
    ): VectorLayerImporter.ImportResult = withContext(Dispatchers.IO) {
        val f = File(sheetsDir(context), "$communeSlug/$to.geojson")
        if (!f.exists())
            return@withContext VectorLayerImporter.ImportResult.Error(
                "Không có tờ $to (xã $communeSlug) trong dữ liệu offline")
        try {
            val text = f.readText()
            val name = "$communeSlug/$to"
            val vlayer = VectorLayerImporter.parseGeoJsonText(
                text, name, CadastralCloudSource.CENTRAL_MERIDIAN)
            if (vlayer.isEmpty)
                VectorLayerImporter.ImportResult.Error("Tờ $to không có đối tượng hợp lệ")
            else
                VectorLayerImporter.ImportResult.Success(vlayer, name)
        } catch (e: Exception) {
            VectorLayerImporter.ImportResult.Error("Lỗi đọc offline: ${e.message}")
        }
    }

    /** Tải chỉ mục khung tờ từ file cục bộ (sheets/_index.json). */
    suspend fun loadIndex(context: Context): List<CadastralCloudSource.SheetBox> =
        withContext(Dispatchers.IO) {
            val f = File(sheetsDir(context), "_index.json")
            if (!f.exists()) return@withContext emptyList()
            try {
                CadastralCloudSource.parseIndex(f.readText())
            } catch (e: Exception) {
                emptyList()
            }
        }

    /**
     * Tra ngược điểm -> xã/tờ/thửa OFFLINE (điểm-trong-đa-giác cục bộ).
     * Nhận toạ độ VN-2000 (x=Easting, y=Northing); đổi sang WGS-84 rồi:
     *   1) lọc tờ ứng viên theo bbox WGS-84 trong _index.json,
     *   2) nạp từng tờ, tìm thửa BAO điểm bằng VectorLayerImporter.findEnclosingFeature.
     */
    suspend fun whereAmIVn2000(
        context: Context, xEasting: Double, yNorthing: Double
    ): CadastralCloudSource.WhereResult = withContext(Dispatchers.IO) {
        val gp = VectorLayerImporter.inverseVn2000(
            yNorthing, xEasting, CadastralCloudSource.CENTRAL_MERIDIAN)
            ?: return@withContext CadastralCloudSource.WhereResult(
                false, message = "Toạ độ VN-2000 không hợp lệ")
        val lat = gp.latitude; val lon = gp.longitude

        val idx = loadIndex(context)
        if (idx.isEmpty())
            return@withContext CadastralCloudSource.WhereResult(
                false, message = "Chưa có dữ liệu offline (chép sheets/ vào máy)")

        // Ứng viên: tờ có bbox WGS-84 chứa điểm (nới nhẹ 1e-6 cho sai số biên).
        val cands = idx.filter {
            lon >= it.lonMin - 1e-6 && lon <= it.lonMax + 1e-6 &&
            lat >= it.latMin - 1e-6 && lat <= it.latMax + 1e-6
        }
        for (box in cands) {
            when (val r = loadSheet(context, box.commune, box.to)) {
                is VectorLayerImporter.ImportResult.Success -> {
                    val f = VectorLayerImporter.findEnclosingFeature(r.layer, lat, lon)
                    if (f != null) {
                        return@withContext CadastralCloudSource.WhereResult(
                            found = true,
                            xaName = box.communeName,
                            to = box.to,
                            thua = f.soThua.ifBlank { f.label },
                            dienTich = f.dienTich,
                            tenChu = f.chuSuDung
                        )
                    }
                }
                else -> { /* bỏ qua tờ lỗi, thử tờ tiếp theo */ }
            }
        }
        CadastralCloudSource.WhereResult(false, message = "Ngoài phạm vi bản đồ offline")
    }
}
