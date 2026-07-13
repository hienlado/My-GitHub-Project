package com.hien.rtkmultidevice.ui.screens.map

/**
 * CadastralCloudSource — nguồn bản đồ địa chính từ GCS (theo TỜ) cho app RTK.
 *
 * Pipeline đẩy GeoJSON VN-2000 theo tờ lên:
 *   https://storage.googleapis.com/<bucket>/sheets/<xã>/<tờ>.geojson
 *   (ví dụ: .../sheets/nghiathanh/DC12_TL2000.geojson)
 *
 * Toạ độ trong file là VN-2000 (E,N) -> app tự chuyển sang lat/lon bằng bộ tham số của app
 * (VectorLayerImporter.parseGeoJsonText với hintCm), giữ VN-2000 để CẮM MỐC chính xác.
 *
 * Không cần sửa CloudMapLoader: truyền baseUrl là URL đầy đủ và layer="" -> fetch thẳng.
 */
object CadastralCloudSource {

    /** Bucket công khai chứa GeoJSON theo tờ (khớp SheetGeoJsonPrefix của pipeline). */
    const val BUCKET = "rtk-shp-input-v1"
    const val BASE   = "https://storage.googleapis.com/$BUCKET/sheets"

    /** Danh sách xã (khớp tên thư mục pipeline). */
    val COMMUNES = listOf(
        "binhgia" to "Xã Bình Giã",
        "chauduc" to "Xã Châu Đức",
        "kimlong" to "Xã Kim Long",
        "ngaigiao" to "Xã Ngãi Giao",
        "nghiathanh" to "Xã Nghĩa Thành",
        "xuanson" to "Xã Xuân Sơn",
    )

    /** URL 1 tờ. sheet ví dụ "DC12_TL2000" (số tờ + tỉ lệ). */
    fun sheetUrl(communeSlug: String, sheet: String): String =
        "$BASE/$communeSlug/$sheet.geojson"

    /**
     * Tải 1 tờ từ cloud -> VectorLayer (hình học đầy đủ VN-2000, sẵn sàng cắm mốc).
     * @param hintCm kinh tuyến trục VN-2000 của khu vực (BR-VT: 107.75 = 107°45').
     */
    suspend fun loadSheet(
        communeSlug: String,
        sheet: String,
        hintCm: Double = 107.75
    ): VectorLayerImporter.ImportResult =
        CloudMapLoader.fetchGeoJson(
            baseUrl = sheetUrl(communeSlug, sheet),
            apiKey  = "",
            layer   = "",            // để trống -> fetch trực tiếp URL GCS
            hintCm  = hintCm
        )
}
