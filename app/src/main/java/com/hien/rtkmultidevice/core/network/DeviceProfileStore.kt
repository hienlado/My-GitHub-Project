package com.hien.rtkmultidevice.core.network

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DeviceProfileStore — Hồ sơ máy thu do NGƯỜI DÙNG tự dò được, lưu lại để lần
 * sau kết nối thẳng, khỏi dò lại.
 *
 * Vì sao tách khỏi `WifiInfoHelper.PROFILES`: hồ sơ trong mã nguồn là những máy
 * đã kiểm chứng, sửa thì phải build lại app. Hồ sơ ở đây do người đo tạo ngoài
 * hiện trường, ghi ra file JSON trong bộ nhớ app — thêm máy mới không cần lập
 * trình viên.
 *
 * File: `filesDir/device_profiles.json`
 */
object DeviceProfileStore {

    private const val TAG = "DeviceProfileStore"
    private const val FILE = "device_profiles.json"

    /**
     * @param ssidRegex mẫu tên WiFi để tự nhận ra máy (rỗng = không tự nhận).
     * @param daKiemChung true khi người dùng đã kết nối thật và thấy dữ liệu chạy.
     */
    data class HoSo(
        val ten         : String,
        val ssidRegex   : String = "",
        val hosts       : List<String> = emptyList(),
        val ports       : List<Int> = emptyList(),
        val ghiChu      : String = "",
        val webHost     : String = "",
        val loaiDuLieu  : String = "",
        val ngayTao     : Long = System.currentTimeMillis(),
        val daKiemChung : Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ten", ten); put("ssidRegex", ssidRegex)
            put("hosts", JSONArray(hosts)); put("ports", JSONArray(ports))
            put("ghiChu", ghiChu); put("webHost", webHost)
            put("loaiDuLieu", loaiDuLieu)
            put("ngayTao", ngayTao); put("daKiemChung", daKiemChung)
        }

        /** Đổi sang hồ sơ mà `WifiInfoHelper.findByProfile` dùng được. */
        fun toWifiProfile(): WifiInfoHelper.Profile? {
            if (hosts.isEmpty() || ports.isEmpty()) return null
            val rx = runCatching {
                Regex(ssidRegex.ifBlank { "(?!)" }, RegexOption.IGNORE_CASE)
            }.getOrNull() ?: return null
            return WifiInfoHelper.Profile(ten, rx, hosts, ports)
        }

        companion object {
            fun from(o: JSONObject) = HoSo(
                ten = o.optString("ten"),
                ssidRegex = o.optString("ssidRegex"),
                hosts = o.optJSONArray("hosts").toStrings(),
                ports = o.optJSONArray("ports").toInts(),
                ghiChu = o.optString("ghiChu"),
                webHost = o.optString("webHost"),
                loaiDuLieu = o.optString("loaiDuLieu"),
                ngayTao = o.optLong("ngayTao", System.currentTimeMillis()),
                daKiemChung = o.optBoolean("daKiemChung", false)
            )
        }
    }

    private fun file(c: Context) = File(c.filesDir, FILE)

    fun tatCa(c: Context): List<HoSo> = runCatching {
        val f = file(c)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(HoSo::from) }
    }.getOrElse {
        Log.w(TAG, "đọc hồ sơ lỗi: ${it.message}"); emptyList()
    }

    /** Thêm hoặc thay hồ sơ CÙNG TÊN. Trả về danh sách sau khi ghi. */
    fun luu(c: Context, hs: HoSo): List<HoSo> {
        val ds = tatCa(c).filterNot { it.ten.equals(hs.ten, ignoreCase = true) } + hs
        ghi(c, ds)
        return ds
    }

    fun xoa(c: Context, ten: String): List<HoSo> {
        val ds = tatCa(c).filterNot { it.ten.equals(ten, ignoreCase = true) }
        ghi(c, ds)
        return ds
    }

    private fun ghi(c: Context, ds: List<HoSo>) {
        runCatching {
            file(c).writeText(JSONArray().apply { ds.forEach { put(it.toJson()) } }.toString(1))
        }.onFailure { Log.w(TAG, "ghi hồ sơ lỗi: ${it.message}") }
    }

    /**
     * Dựng hồ sơ từ kết quả dò. Điền sẵn phần máy biết, để trống phần người
     * dùng phải tự đặt (tên máy, mẫu tên WiFi).
     */
    fun tuKetQua(
        ten: String, uv: DeviceProbe.UngVien, ssid: String? = null
    ): HoSo {
        // Mẫu SSID gợi ý: lấy phần chữ đầu của tên WiFi, phần số thường là số máy
        val rx = ssid?.let {
            val dau = Regex("^[A-Za-z]+").find(it)?.value
            if (!dau.isNullOrBlank() && dau.length >= 2) "^${Regex.escape(dau)}" else ""
        }.orEmpty()
        return HoSo(
            ten = ten,
            ssidRegex = rx,
            hosts = listOf(uv.host),
            ports = uv.cong,
            webHost = uv.web?.let { "http://${it.host}" }.orEmpty(),
            loaiDuLieu = uv.nghe?.moTa.orEmpty(),
            ghiChu = buildString {
                uv.web?.let { append("Web: ${it.ma} ${it.tieuDe} ${it.server}".trim()) }
                uv.nghe?.let {
                    if (isNotEmpty()) append(" · ")
                    append("${it.soByte} byte/${"%.1f".format(it.giay)}s")
                }
                if (isNotEmpty()) append(" · ")
                append("dò ${uv.nguon}")
            },
            daKiemChung = false
        )
    }
}

private fun JSONArray?.toStrings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

private fun JSONArray?.toInts(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { optInt(it) }.filter { it > 0 }
