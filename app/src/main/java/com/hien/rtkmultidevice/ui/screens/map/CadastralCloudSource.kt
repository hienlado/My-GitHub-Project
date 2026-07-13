package com.hien.rtkmultidevice.ui.screens.map

/**
 * CadastralCloudSource — nguồn bản đồ địa chính (theo TỜ) cho app RTK.
 *
 * Bucket bật "public access prevention" nên KHÔNG public được -> gọi qua Cloud Function
 * getCadastral (gated bằng X-API-Key). Function trả GeoJSON VN-2000 của 1 tờ.
 *
 * Cấu hình sau khi deploy function: đổi FUNCTION_URL và API_KEY.
 */
object CadastralCloudSource {

    // Cloud Function getCadastral (đã deploy).
    const val FUNCTION_URL = "https://asia-east1-fluid-axe-328823.cloudfunctions.net/getCadastral"
    const val API_KEY = "rtk-cadastral-2026-x7k9"

    /** Kinh tuyến trục VN-2000 khu vực (BR-VT: 107°45'). */
    const val CENTRAL_MERIDIAN = 107.75

    /** Danh sách xã (slug khớp thư mục pipeline -> tên hiển thị). */
    val COMMUNES = listOf(
        "binhgia" to "Xã Bình Giã",
        "chauduc" to "Xã Châu Đức",
        "kimlong" to "Xã Kim Long",
        "ngaigiao" to "Xã Ngãi Giao",
        "nghiathanh" to "Xã Nghĩa Thành",
        "xuanson" to "Xã Xuân Sơn",
    )

    /** Tờ + thửa sau khi tách chuỗi nhập. */
    data class SheetParcel(val to: String, val thua: String?)

    /**
     * Tách chuỗi nhập "122/90", "122.90", "122-90", "122 90" hoặc chỉ "122".
     * Trả null nếu không có số tờ.
     */
    fun parse(raw: String): SheetParcel? {
        val parts = raw.trim().split('/', '.', '-', ' ', ',').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val to = parts[0].filter { it.isDigit() }
        if (to.isBlank()) return null
        val thua = parts.getOrNull(1)?.filter { it.isDigit() }?.ifBlank { null }
        return SheetParcel(to, thua)
    }

    /** Tải 1 tờ từ Cloud Function -> VectorLayer (VN-2000, sẵn sàng cắm mốc). */
    suspend fun loadSheet(communeSlug: String, to: String): VectorLayerImporter.ImportResult =
        CloudMapLoader.fetchGeoJson(
            baseUrl = FUNCTION_URL,
            apiKey  = API_KEY,
            layer   = "$communeSlug/$to",   // function đọc ?layer=<xã>/<tờ>
            hintCm  = CENTRAL_MERIDIAN
        )
}
