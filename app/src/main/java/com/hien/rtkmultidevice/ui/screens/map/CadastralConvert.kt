package com.hien.rtkmultidevice.ui.screens.map

import android.content.Context
import org.json.JSONObject

/**
 * CadastralConvert — tra cứu chuyển đổi TỜ theo đơn vị hành chính CŨ (trước sáp nhập).
 *
 * 6 xã hiện tại gộp từ 16 xã cũ; số tờ được đánh số lại (số THỬA giữ nguyên).
 * Bảng tra cứu nằm ở assets/cadastral_convert.json:
 *   { "olds":[{"slug","name","new","newName"}...],
 *     "map": { "<xã cũ slug>": { "<tờ cũ>": "<tờ mới>" } } }
 *
 * Vì 16 tên xã cũ đều DUY NHẤT nên (xã cũ + tờ cũ) -> (xã mới + tờ mới) là xác định.
 */
object CadastralConvert {

    /** Một đơn vị xã cũ. */
    data class OldCommune(
        val slug: String, val name: String,
        val newSlug: String, val newName: String
    )

    /** Kết quả tra: xã mới (slug) + số tờ mới. */
    data class Resolved(val newSlug: String, val newName: String, val newTo: String)

    @Volatile private var loaded = false
    private val olds = ArrayList<OldCommune>()
    private val map = HashMap<String, HashMap<String, String>>()   // oldSlug -> (oldTo -> newTo)

    /** Nạp 1 lần từ assets (idempotent, an toàn luồng). */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        try {
            val txt = context.assets.open("cadastral_convert.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(txt)
            val arr = root.getJSONArray("olds")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                olds.add(OldCommune(
                    o.getString("slug"), o.getString("name"),
                    o.getString("new"), o.optString("newName", o.getString("new"))
                ))
            }
            val m = root.getJSONObject("map")
            for (key in m.keys()) {
                val inner = m.getJSONObject(key)
                val d = HashMap<String, String>(inner.length())
                for (k in inner.keys()) d[k] = inner.getString(k)
                map[key] = d
            }
        } catch (_: Exception) {
            // Thiếu asset -> danh sách rỗng, chế độ "xã cũ" sẽ không có mục.
        }
        loaded = true
    }

    /** Danh sách xã cũ (đã sắp theo xã mới rồi tên) để đổ vào dropdown. */
    fun oldCommunes(context: Context): List<OldCommune> {
        ensureLoaded(context); return olds
    }

    /** Tra tờ cũ -> tờ mới. Trả null nếu không có. */
    fun resolve(context: Context, oldSlug: String, oldTo: String): Resolved? {
        ensureLoaded(context)
        val newTo = map[oldSlug]?.get(oldTo.trim().filter { it.isDigit() }) ?: return null
        val oc = olds.firstOrNull { it.slug == oldSlug } ?: return null
        return Resolved(oc.newSlug, oc.newName, newTo)
    }
}
