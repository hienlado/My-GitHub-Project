package com.hien.rtkmultidevice.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * BienBanQh — Trang SƠ ĐỒ QUY HOẠCH của biên bản.
 *
 * Hai khung, mỗi khung một lớp:
 *   · QUY HOẠCH SỬ DỤNG ĐẤT   (TT 08/2024) — tô nền đặc theo màu quy định.
 *   · QUY HOẠCH XÂY DỰNG      (TT 16/2025 Phụ lục I Mục 07) — Phụ lục quy định
 *     KHÔNG tô nền mà thể hiện bằng MẪU TÔ (hatch) cùng màu. Trên giấy in một
 *     lần, không có ngân sách khung hình như bản đồ sống, nên vẽ đúng mẫu tô.
 *
 * Thửa đang lập biên bản luôn nằm CHÍNH GIỮA khung, kể cả khi giáp biên hay
 * giáp tờ bản đồ — khung ngắm tính từ TÂM THỬA chứ không từ bao hình dữ liệu
 * quy hoạch. Vùng nào lọt vào khung thì vẽ, phần thừa bị clipRect cắt.
 *
 * Toạ độ vào: VN-2000 mét, cặp (N, E) — giống bảng kê mốc giới.
 */
object BienBanQh {

    /** Một khoanh quy hoạch. `lo` = các lỗ thủng bên trong (đảo). */
    data class Vung(
        val ma    : String,
        val ten   : String,
        val mau   : Int,                                  // ARGB
        val ngoai : List<Pair<Double, Double>>,           // (N, E)
        val lo    : List<List<Pair<Double, Double>>> = emptyList()
    )

    // ══════════════════════════════════════════════════════════
    // MẪU TÔ TT16 — đọc từ style_qhxd_v2.json
    // ══════════════════════════════════════════════════════════

    /** `lines` = các chùm đường song song; `glyph` = một hình lặp trên lưới ô. */
    data class HoaVan(
        val kieu  : String,                       // "lines" | "glyph"
        val duong : List<Duong> = emptyList(),
        val tile  : Float = 12f,
        val netPx : Float = 1f,
        val to    : Boolean = false,
        val lechX : Float = 0f,
        val lechY : Float = 0f,
        val path  : List<List<Any>> = emptyList()
    ) {
        data class Duong(val goc: Float, val buoc: Float, val netDut: FloatArray, val netPx: Float)
    }

    /** Đọc bảng mẫu tô. Thiếu file thì trả rỗng — khung vẫn vẽ được, chỉ không có hoạ tiết. */
    fun napHoaVan(styleFile: File): Map<String, HoaVan> = runCatching {
        if (!styleFile.exists()) return emptyMap()
        val root = JSONObject(styleFile.readText()).optJSONObject("loai_dat") ?: return emptyMap()
        val out = HashMap<String, HoaVan>()
        val it = root.keys()
        while (it.hasNext()) {
            val ma = it.next()
            val m = root.optJSONObject(ma)?.optJSONObject("mau_to") ?: continue
            when (m.optString("kieu")) {
                "lines" -> {
                    val ds = ArrayList<HoaVan.Duong>()
                    val arr = m.optJSONArray("duong") ?: continue
                    for (i in 0 until arr.length()) {
                        val d = arr.optJSONObject(i) ?: continue
                        val nd = d.optJSONArray("net_dut")
                        val dash = FloatArray(nd?.length() ?: 0) { k ->
                            nd!!.optDouble(k, 0.0).toFloat()
                        }
                        ds += HoaVan.Duong(
                            goc = d.optDouble("goc", 0.0).toFloat(),
                            buoc = d.optDouble("buoc", 8.0).toFloat(),
                            netDut = dash,
                            netPx = d.optDouble("net_px", 0.9).toFloat()
                        )
                    }
                    if (ds.isNotEmpty()) out[ma] = HoaVan("lines", duong = ds)
                }
                "glyph" -> {
                    val pa = m.optJSONArray("path") ?: continue
                    val segs = ArrayList<List<Any>>()
                    for (i in 0 until pa.length()) {
                        val s = pa.optJSONArray(i) ?: continue
                        val one = ArrayList<Any>()
                        one += s.optString(0)
                        for (k in 1 until s.length()) one += s.optDouble(k, 0.0).toFloat()
                        segs += one
                    }
                    val lech = m.optJSONArray("lech")
                    out[ma] = HoaVan(
                        "glyph", tile = m.optDouble("tile", 12.0).toFloat(),
                        netPx = m.optDouble("net_px", 1.0).toFloat(),
                        to = m.optBoolean("to", false),
                        lechX = lech?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                        lechY = lech?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                        path = segs
                    )
                }
            }
        }
        out
    }.getOrDefault(emptyMap())

    /** Vẽ hoạ tiết trong phạm vi `vung` (đã clip sẵn) trên hình chữ nhật `r`. */
    private fun veHoaVan(c: Canvas, r: RectF, hv: HoaVan, mau: Int) {
        val p = Paint().apply { isAntiAlias = true; color = mau }
        when (hv.kieu) {
            "lines" -> hv.duong.forEach { d ->
                p.style = Paint.Style.STROKE
                p.strokeWidth = d.netPx
                p.pathEffect = if (d.netDut.size >= 2) DashPathEffect(d.netDut, 0f) else null
                veChum(c, r, d.goc, d.buoc, p)
            }
            "glyph" -> {
                val t = hv.tile.coerceAtLeast(4f)
                p.style = if (hv.to) Paint.Style.FILL else Paint.Style.STROKE
                p.strokeWidth = hv.netPx
                var gx = r.left - t + hv.lechX % t
                while (gx < r.right + t) {
                    var gy = r.top - t + hv.lechY % t
                    while (gy < r.bottom + t) {
                        c.save(); c.translate(gx, gy)
                        c.drawPath(hinh(hv.path), p)
                        c.restore()
                        gy += t
                    }
                    gx += t
                }
            }
        }
        p.pathEffect = null
    }

    /** Chùm đường song song nghiêng `goc` độ, cách nhau `buoc`, phủ kín `r`. */
    private fun veChum(c: Canvas, r: RectF, goc: Float, buoc: Float, p: Paint) {
        val b = buoc.coerceAtLeast(2f)
        val rad = Math.toRadians(goc.toDouble())
        val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
        // pháp tuyến của chùm
        val nx = -dy; val ny = dx
        val cxr = r.centerX(); val cyr = r.centerY()
        val cheo = hypot(r.width().toDouble(), r.height().toDouble()).toFloat() / 2f + b
        var s = -cheo
        while (s <= cheo) {
            val ox = cxr + nx * s; val oy = cyr + ny * s
            c.drawLine(ox - dx * cheo, oy - dy * cheo, ox + dx * cheo, oy + dy * cheo, p)
            s += b
        }
    }

    /** Dựng Path của một glyph từ lệnh M/L/Z/R (chữ nhật) /C (tròn) /H (lục giác). */
    private fun hinh(segs: List<List<Any>>): Path {
        val path = Path()
        segs.forEach { s ->
            val k = s.getOrNull(0) as? String ?: return@forEach
            fun f(i: Int) = (s.getOrNull(i) as? Float) ?: 0f
            when (k) {
                "M" -> path.moveTo(f(1), f(2))
                "L" -> path.lineTo(f(1), f(2))
                "Z" -> path.close()
                "R" -> path.addRect(f(1), f(2), f(1) + f(3), f(2) + f(4), Path.Direction.CW)
                "C" -> path.addCircle(f(1), f(2), f(3), Path.Direction.CW)
                "H" -> {                              // lục giác đều, tâm (x,y), bán kính r
                    val cxh = f(1); val cyh = f(2); val rr = f(3)
                    for (i in 0 until 6) {
                        val a = Math.toRadians(60.0 * i)
                        val x = cxh + rr * cos(a).toFloat()
                        val y = cyh + rr * sin(a).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                }
            }
        }
        return path
    }

    // ══════════════════════════════════════════════════════════
    // KHUNG BẢN ĐỒ
    // ══════════════════════════════════════════════════════════

    /** Bề rộng thực tế (m) của khung ngắm, tính từ kích thước thửa. */
    private fun tamNgam(verts: List<Pair<Double, Double>>): Triple<Double, Double, Double> {
        val n0 = verts.minOf { it.first };  val n1 = verts.maxOf { it.first }
        val e0 = verts.minOf { it.second }; val e1 = verts.maxOf { it.second }
        val span = max(max(n1 - n0, e1 - e0) * 2.2, 60.0)
        return Triple((n0 + n1) / 2.0, (e0 + e1) / 2.0, span)
    }

    /**
     * Vẽ MỘT khung quy hoạch.
     * @param hoaVan bảng mẫu tô TT16; rỗng = chỉ tô màu thuần.
     * @return danh sách (mã, tên, màu) THỰC SỰ xuất hiện trong khung — làm chú dẫn.
     */
    fun veKhung(
        canvas : Canvas,
        frame  : RectF,
        vung   : List<Vung>,
        verts  : List<Pair<Double, Double>>,
        hoaVan : Map<String, HoaVan> = emptyMap(),
        moTo   : Boolean = true            // true = QHSDD tô đặc; false = QHXD nền nhạt + mẫu tô
    ): List<Triple<String, String, Int>> {
        canvas.drawRect(frame, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f; color = Color.BLACK
        })
        if (verts.isEmpty()) return emptyList()

        val (cn, ce, span) = tamNgam(verts)
        val s = min(frame.width(), frame.height()) / span
        val fx = frame.centerX(); val fy = frame.centerY()
        fun px(n: Double, e: Double) = Pair(
            (fx + (e - ce) * s).toFloat(),
            (fy - (n - cn) * s).toFloat()
        )

        canvas.save()
        canvas.clipRect(frame)

        val co = LinkedHashMap<String, Triple<String, String, Int>>()
        val fill = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val vien = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 0.9f
        }
        vung.sortedByDescending { dienTich(it.ngoai) }.forEach { v ->
            if (v.ngoai.size < 3) return@forEach
            val path = Path()
            duong(path, v.ngoai, ::px)
            v.lo.forEach { if (it.size >= 3) duong(path, it, ::px) }
            path.fillType = Path.FillType.EVEN_ODD

            // Chỉ ghi vào CHÚ DẪN khi vùng thật sự chạm khung — không liệt kê cả
            // bảng mã như trước, chú dẫn phải khớp đúng những gì mắt nhìn thấy.
            val bb = RectF()
            path.computeBounds(bb, true)
            if (!RectF.intersects(bb, frame)) return@forEach

            val dac = Color.argb(255, Color.red(v.mau), Color.green(v.mau), Color.blue(v.mau))
            if (moTo) {
                fill.color = dac
                canvas.drawPath(path, fill)
            } else {
                // TT16: không tô nền. Nền rất nhạt chỉ để khoanh vùng, hoạ tiết mới là chính.
                fill.color = Color.argb(38, Color.red(v.mau), Color.green(v.mau), Color.blue(v.mau))
                canvas.drawPath(path, fill)
                val hv = hoaVan[v.ma]
                if (hv != null) {
                    canvas.save()
                    canvas.clipPath(path)
                    veHoaVan(canvas, bb, hv, dac)
                    canvas.restore()
                }
                vien.color = dac
                canvas.drawPath(path, vien)
            }
            co.putIfAbsent(v.ma, Triple(v.ma, v.ten, v.mau))
        }

        // ── Ranh thửa đang lập biên bản — chồng lên trên ──
        val pThua = Path()
        duong(pThua, verts, ::px)
        canvas.drawPath(pThua, Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 2.6f; color = Color.WHITE
        })
        canvas.drawPath(pThua, Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 1.4f; color = Color.BLACK
        })
        canvas.restore()

        thuoc(canvas, frame.left + 8f, frame.bottom - 8f, s.toDouble())
        return co.values.toList()
    }

    // ══════════════════════════════════════════════════════════
    // CHÚ DẪN
    // ══════════════════════════════════════════════════════════

    const val CAO_DONG = 11f

    /** Số dòng chú dẫn sẽ chiếm — để trang tính trước chiều cao mà căn lề. */
    fun soDong(n: Int): Int = if (n <= 0) 0 else (n + soCot(n) - 1) / soCot(n)

    private fun soCot(n: Int) = if (n > 8) 2 else 1

    /**
     * Chú dẫn: ô mẫu (có cả hoạ tiết nếu là lớp XD) + mã + tên, xếp 1–2 cột.
     * @return y sau khi vẽ xong.
     */
    fun veChuDan(
        canvas: Canvas, x: Float, y: Float, rong: Float,
        muc: List<Triple<String, String, Int>>,
        hoaVan: Map<String, HoaVan> = emptyMap(),
        moTo: Boolean = true
    ): Float {
        if (muc.isEmpty()) return y
        val p = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 7.2f }
        val cot = soCot(muc.size)
        val wCot = rong / cot
        var yy = y
        muc.forEachIndexed { i, (ma, ten, mau) ->
            val lx = x + (i % cot) * wCot
            val ly = y + (i / cot) * CAO_DONG
            val o = RectF(lx, ly, lx + 13f, ly + 8f)
            val dac = Color.argb(255, Color.red(mau), Color.green(mau), Color.blue(mau))
            if (moTo) {
                canvas.drawRect(o, Paint().apply { color = dac })
            } else {
                canvas.drawRect(o, Paint().apply {
                    color = Color.argb(38, Color.red(mau), Color.green(mau), Color.blue(mau))
                })
                hoaVan[ma]?.let {
                    canvas.save(); canvas.clipRect(o); veHoaVan(canvas, o, it, dac); canvas.restore()
                }
            }
            canvas.drawRect(o, Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 0.5f; color = Color.DKGRAY
            })
            val nhan = if (ten.isBlank()) ma else "$ma — $ten"
            canvas.drawText(cat(nhan, p, wCot - 19f), lx + 16f, ly + 7f, p)
            yy = max(yy, ly + CAO_DONG)
        }
        return yy
    }

    // ══════════════════════════════════════════════════════════

    private fun duong(path: Path, pts: List<Pair<Double, Double>>,
                      px: (Double, Double) -> Pair<Float, Float>) {
        pts.forEachIndexed { i, (n, e) ->
            val (x, y) = px(n, e)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun dienTich(pts: List<Pair<Double, Double>>): Double {
        if (pts.size < 3) return 0.0
        var s = 0.0
        for (i in pts.indices) {
            val (n1, e1) = pts[i]
            val (n2, e2) = pts[(i + 1) % pts.size]
            s += e1 * n2 - e2 * n1
        }
        return abs(s) / 2.0
    }

    private fun cat(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.length > 3 && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    private fun thuoc(c: Canvas, x: Float, y: Float, s: Double) {
        val m = listOf(10, 20, 50, 100, 200, 500).firstOrNull { it * s >= 34 } ?: 500
        val w = (m * s).toFloat()
        c.drawRect(x - 2f, y - 12f, x + w + 26f, y + 3f,
            Paint().apply { color = Color.argb(210, 255, 255, 255) })
        c.drawRect(x, y - 4f, x + w, y, Paint().apply { color = Color.BLACK })
        c.drawText("$m m", x + w + 3f, y + 1f,
            Paint().apply { isAntiAlias = true; textSize = 6.5f; color = Color.BLACK })
    }
}
