package com.hien.rtkmultidevice.ui.screens.survey

/**
 * PointListEntryFlags — CỜ MỘT LẦN, đặt trước khi mở màn Danh sách điểm.
 *
 * Vì sao cần: thẻ "Import file" / "Export file" ở tab Dự án trước đây chỉ hiện
 * thông báo "sắp có", trong khi lệnh Import/Export THẬT đã nằm sẵn trong
 * `EnhancedPointListTab`. Hai lối vào cho cùng một việc, mà một lối là hàng giả.
 *
 * Cách nối rẻ nhất và ít rủi ro nhất: đặt cờ rồi điều hướng tới danh sách điểm,
 * để chính nó tự mở hộp chọn định dạng. Không nhân bản logic đọc/ghi file, không
 * thêm tham số vào route.
 *
 * Cùng lối viết với `StakeoutEntryFlags` đã có sẵn trong nhánh này.
 *
 * ⚠ Cờ TỰ XOÁ ngay khi được đọc. Không xoá thì lần sau mở danh sách điểm bằng
 *   đường khác vẫn bị bật hộp thoại — người dùng không hiểu vì sao.
 */
object PointListEntryFlags {
    /** Mở hộp chọn định dạng IMPORT ngay khi vào danh sách điểm. */
    @Volatile var openImport = false
    /** Mở hộp chọn định dạng EXPORT ngay khi vào danh sách điểm. */
    @Volatile var openExport = false

    /** Đọc và xoá cờ import. */
    fun layImport(): Boolean { val v = openImport; openImport = false; return v }
    /** Đọc và xoá cờ export. */
    fun layExport(): Boolean { val v = openExport; openExport = false; return v }
}
