package com.hien.rtkmultidevice.ui.screens.baseconfig

import com.hien.rtkmultidevice.data.datastore.AppSettings
import java.util.Locale

/**
 * Loại thiết bị làm Base — cấu hình Base ĐI THEO THIẾT BỊ (dễ thay máy sau này).
 * Mỗi máy có hướng dẫn riêng; máy dùng lệnh (ComNav) có thể sinh sẵn chuỗi lệnh.
 *
 * Thêm máy mới: khai báo thêm 1 hằng số + nhánh trong guidance()/commands().
 */
enum class BaseDevice(val key: String, val displayName: String, val commandBased: Boolean) {
    COMNAV_T30("COMNAV_T30", "ComNav T30 (lệnh SinoGNSS)", true),
    STEC("STEC", "STEC (web 192.168.10.1)", false),
    GENERIC("GENERIC", "Máy khác / thủ công", false);

    companion object {
        fun from(key: String): BaseDevice = entries.firstOrNull { it.key == key } ?: COMNAV_T30
    }

    private fun d(v: Double, n: Int) = "%.${n}f".format(Locale.US, v)

    /**
     * Chuỗi lệnh cấu hình Base (chỉ máy commandBased). Trả rỗng nếu máy không dùng lệnh.
     * LƯU Ý: đây là mẫu theo bộ lệnh SinoGNSS/NovAtel — KIỂM CHỨNG với tài liệu T30 trước khi gửi.
     * Cổng ra (COM2) tuỳ đấu nối radio/datalink — chỉnh cho khớp máy của bạn.
     */
    fun commands(c: AppSettings.BaseConfig): List<String> {
        if (this != COMNAV_T30) return emptyList()
        val pos = when (c.mode) {
            2 -> "FIX AUTO"                                                    // bình sai tự động
            else -> "FIX POSITION ${d(c.lat, 9)} ${d(c.lon, 9)} ${d(c.ellHeight, 3)}"  // điểm đã biết / hiện tại
        }
        return listOf(
            "UNLOGALL",
            pos,
            "LOG COM2 RTCM1006 ONTIME 10",
            "LOG COM2 RTCM1033 ONTIME 10",
            "LOG COM2 RTCM1074 ONTIME 1",
            "LOG COM2 RTCM1084 ONTIME 1",
            "LOG COM2 RTCM1094 ONTIME 1",
            "LOG COM2 RTCM1124 ONTIME 1",
            "SAVECONFIG"
        )
    }

    /** Hướng dẫn cấu hình base cho từng loại máy. */
    fun guidance(c: AppSettings.BaseConfig): String = when (this) {
        COMNAV_T30 ->
            "ComNav T30 dùng bộ lệnh SinoGNSS (kiểu NovAtel). Gửi các lệnh dưới qua cổng lệnh " +
            "(Bluetooth/serial), mỗi lệnh 1 dòng, kết thúc bằng CR/LF:\n\n" +
            commands(c).joinToString("\n") +
            "\n\n• Điểm đã biết / vị trí hiện tại → FIX POSITION <lat> <lon> <h>.\n" +
            "• Bình sai TB → FIX AUTO.\n" +
            "• Đổi COM2 cho khớp cổng phát cải chính (radio/datalink) của bạn.\n" +
            "• SAVECONFIG để lưu vào máy. KIỂM CHỨNG cú pháp với tài liệu T30 trước khi dùng."
        STEC ->
            "STEC cấu hình qua web:\n" +
            "1. Nối WiFi hotspot của máy (tên = số series) → http://192.168.10.1 (admin/password).\n" +
            "2. Working Mode = Base.\n" +
            "3. Nhập toạ độ WGS-84 ở trên (hoặc chọn Average để máy tự bình sai).\n" +
            "4. Datalink (Radio/NTRIP Server/TCP) + định dạng RTCM3.\n" +
            "5. Nhập chiều cao anten = ${d(c.antennaHeight, 3)} m."
        GENERIC ->
            "Máy khác: đặt Working Mode = Base; nhập toạ độ base = giá trị WGS-84 ở trên " +
            "(hoặc bình sai tự động); bật đầu ra RTCM3 (nên có 1006/1033 + MSM 1074/1084/1094/1124); " +
            "đặt chiều cao anten = ${d(c.antennaHeight, 3)} m. Xem tài liệu máy để biết cách phát cải chính."
    }
}
