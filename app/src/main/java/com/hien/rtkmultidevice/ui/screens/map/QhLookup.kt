package com.hien.rtkmultidevice.ui.screens.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * QhLookup — tra QUY HOẠCH của một thửa đất (offline).
 *
 * Dữ liệu do dự án "Convert Plan Maps" sinh ra, đặt cạnh dữ liệu địa chính:
 *     <sheets>/_qh/<xã>/<tờ>.tra.json
 *
 * Nội dung một tờ:
 *   { "xa":"xakimlong", "to":"54", "n":103,
 *     "thua": { "42": { "dt":2841.6,
 *                       "sdd":[{"ma":"CLN","ten":"...","m2":2159.6,"pc":76.0}, ...],
 *                       "xd" :[{"ma":"NNP_TTK","ten":"...","m2":2216.4,"pc":78.0}, ...] } } }
 *
 * "sdd" = quy hoạch sử dụng đất (TT 08/2024/TT-BTNMT)
 * "xd"  = quy hoạch xây dựng    (TT 16/2025/TT-BXD)
 *
 * ⚠ 44 % số thửa vắt qua từ 2 vùng quy hoạch trở lên, nên mỗi thửa là một DANH SÁCH
 *   kèm m² và %, không phải một mã duy nhất. Hiển thị một mã là sai bản chất.
 *
 * ⚠ 463/195.152 thửa TRÙNG SỐ THỬA trong cùng một tờ → giá trị khi đó là MẢNG;
 *   chọn phần tử có "dt" gần diện tích thửa nhất.
 */
object QhLookup {

    data class Muc(val ma: String, val ten: String, val m2: Double, val pc: Double)

    data class ThuaQh(val dt: Double, val sdd: List<Muc>, val xd: List<Muc>) {
        val coDuLieu: Boolean get() = sdd.isNotEmpty() || xd.isNotEmpty()
    }

    fun qhDir(context: Context): File = File(CadastralLocalSource.sheetsDir(context), "_qh")

    /** Máy đã chép dữ liệu quy hoạch vào chưa. */
    fun coDuLieu(context: Context): Boolean = File(qhDir(context), "_meta.json").exists()

    // Nhớ TỜ vừa đọc: chạm nhiều thửa trong cùng một tờ thì khỏi đọc lại file (~37 KB).
    private var cacheKey: String? = null
    private var cacheObj: JSONObject? = null

    /**
     * @param xa           slug thư mục xã, vd "xakimlong" (KHÔNG phải "Xã Kim Long")
     * @param dienTichThua diện tích thửa đang xét — chỉ dùng để gỡ rối khi trùng số thửa
     */
    suspend fun tra(
        context: Context,
        xa: String,
        to: String,
        soThua: String,
        dienTichThua: Double
    ): ThuaQh? = withContext(Dispatchers.IO) {
        if (xa.isBlank() || to.isBlank() || soThua.isBlank()) return@withContext null

        val key = "$xa/$to"
        var root: JSONObject? = if (key == cacheKey) cacheObj else null
        if (root == null) {
            val f = File(qhDir(context), "$xa/$to.tra.json")
            if (!f.exists()) return@withContext null
            root = try {
                JSONObject(f.readText()).optJSONObject("thua")
            } catch (e: Throwable) {
                null
            }
            if (root == null) return@withContext null
            cacheKey = key
            cacheObj = root
        }

        when (val v = root.opt(soThua)) {
            is JSONObject -> doc(v)
            is JSONArray -> {                       // trùng số thửa trong cùng tờ
                var best: JSONObject? = null
                var lech = Double.MAX_VALUE
                for (i in 0 until v.length()) {
                    val o = v.optJSONObject(i) ?: continue
                    val k = abs(o.optDouble("dt", 0.0) - dienTichThua)
                    if (k < lech) { lech = k; best = o }
                }
                best?.let { doc(it) }
            }
            else -> null
        }
    }

    private fun doc(o: JSONObject) = ThuaQh(
        dt  = o.optDouble("dt", 0.0),
        sdd = muc(o.optJSONArray("sdd")),
        xd  = muc(o.optJSONArray("xd"))
    )

    private fun muc(a: JSONArray?): List<Muc> {
        if (a == null) return emptyList()
        val out = ArrayList<Muc>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Muc(o.optString("ma"), o.optString("ten"),
                        o.optDouble("m2", 0.0), o.optDouble("pc", 0.0)))
        }
        return out
    }

    /**
     * Số kiểu Việt Nam: 2.159,6
     * ⚠ PHẢI format bằng Locale.US rồi mới hoán vị dấu — dùng locale máy thì trên máy
     *   đặt tiếng Việt sẽ ra dấu phẩy thập phân, đúng cái bẫy đã làm hỏng file XML biên bản.
     */
    private fun soVN(v: Double): String {
        val s = String.format(Locale.US, "%,.1f", v)   // 2,159.6
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c == ',') '.' else if (c == '.') ',' else c)
        return sb.toString()                            // 2.159,6
    }

    /** Một dòng cho giao diện: "Đất trồng cây lâu năm — 2.159,6 m² (76%)" */
    fun dong(m: Muc, chiMotMuc: Boolean): String =
        if (chiMotMuc && m.pc >= 99.0) "${m.ten} — ${soVN(m.m2)} m²"
        else "${m.ten} — ${soVN(m.m2)} m² (${m.pc.toInt()}%)"

    /** Gộp cả khối để hiện nhanh trong AlertDialog. */
    fun khoi(tieuDe: String, ds: List<Muc>): String {
        if (ds.isEmpty()) return ""
        val sb = StringBuilder(tieuDe).append('\n')
        ds.forEach { sb.append("  • ").append(dong(it, ds.size == 1)).append('\n') }
        return sb.toString()
    }
}
