package com.hien.rtkmultidevice.core.gnss.ntrip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NtripCanhBao — chỗ duy nhất giữ lỗi NTRIP "vĩnh viễn" để giao diện lấy ra.
 *
 * 🔴 BÀI HỌC 31/08 — vì sao phải có cái này:
 * Nhà cung cấp đổi mật khẩu tài khoản NTRIP. Caster trả **401 Unauthorized**,
 * máy thu Sinov M6 thử lại 5 giây một lần suốt gần một tiếng. Trên màn hình
 * chỉ thấy SINGLE mãi không lên FIXED. Toàn bộ lời giải thích nằm trong
 * logcat — mà ngoài thực địa thì không ai cắm máy tính vào để xem logcat.
 *
 * Nguyên tắc: **lỗi vĩnh viễn phải nói ra, lỗi tạm thời mới được thử lại lặng lẽ.**
 *   · Mất sóng, rớt mạng, caster bận  -> tạm thời, cứ thử lại.
 *   · 401 sai tài khoản, 404 sai mountpoint -> thử lại bao nhiêu cũng vô ích.
 *
 * Là `object` chứ không tiêm qua Hilt: proxy chạy trong tầng core, không có
 * ViewModel; còn giao diện thì có nhiều màn cùng cần đọc. Một chỗ chứa tĩnh là
 * đủ và không kéo thêm phụ thuộc nào.
 */
object NtripCanhBao {

    data class CanhBao(
        val tieuDe   : String,
        val chiTiet  : String,
        /** Việc người dùng phải làm — câu mệnh lệnh ngắn, không giải thích. */
        val phaiLam  : String,
        val luc      : Long = System.currentTimeMillis()
    )

    private val _canhBao = MutableStateFlow<CanhBao?>(null)
    val canhBao: StateFlow<CanhBao?> = _canhBao.asStateFlow()

    fun bao(c: CanhBao) { _canhBao.value = c }

    /** Gọi khi kết nối caster THÀNH CÔNG — lỗi cũ hết hiệu lực. */
    fun xoa() { _canhBao.value = null }

    /** Dựng sẵn cảnh báo 401 cho gọn chỗ gọi. */
    fun sai401(user: String, doDaiMatKhau: Int, mountPoint: String) = bao(
        CanhBao(
            tieuDe = "Caster từ chối tài khoản NTRIP (401)",
            chiTiet = buildString {
                append("Tài khoản đang gửi: ")
                append(user.ifBlank { "(TRỐNG)" })
                append("  ·  mật khẩu ")
                append(if (doDaiMatKhau == 0) "TRỐNG" else "$doDaiMatKhau ký tự")
                append("  ·  mountpoint ")
                append(mountPoint)
                append(".\n\nHai nguyên nhân thường gặp, theo thứ tự hay gặp:\n")
                append("1. Nhà cung cấp ĐỔI MẬT KHẨU tài khoản — hỏi lại bên cấp dịch vụ.\n")
                append("2. Gỡ/cài lại app hoặc xoá dữ liệu app làm hỏng khoá mã hoá, ")
                append("mật khẩu lưu trong máy không giải mã được nữa.")
            },
            phaiLam = "Thiết bị ▸ NTRIP — nhập lại mật khẩu rồi Lưu."
        )
    )
}
