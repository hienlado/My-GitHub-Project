package com.hien.rtkmultidevice.ui.screens.stakeout

/**
 * StakeoutEntryFlags — Cờ điều hướng tới StakeoutScreen (plain object, truy cập
 * mọi nơi không cần DI).
 *
 * Dùng cho thẻ "Định vị tuyến" ở menu Khảo sát và nút trong màn Đo tuyến:
 * trước khi navigate sang Stakeout, đặt cờ openLinePicker = true; StakeoutScreen
 * khi vào sẽ tự mở hộp thoại chọn 2 điểm (đặt tên → chọn đầu/cuối tuyến).
 */
object StakeoutEntryFlags {
    @Volatile
    var openLinePicker: Boolean = false

    /** Lấy & xoá cờ (one-shot). */
    fun consumeOpenLinePicker(): Boolean {
        val v = openLinePicker
        openLinePicker = false
        return v
    }
}
