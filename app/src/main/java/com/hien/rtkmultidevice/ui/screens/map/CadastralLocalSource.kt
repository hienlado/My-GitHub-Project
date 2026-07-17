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
}
