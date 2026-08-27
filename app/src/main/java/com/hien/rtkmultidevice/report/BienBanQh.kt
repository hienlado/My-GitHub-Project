package com.hien.rtkmultidevice.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * BienBanQh — Trang SƠ ĐỒ QUY HOẠCH của biên bản.
 *
 * Hai khung, mỗi khung một lớp:
 *   · QUY HOẠCH SỬ DỤNG ĐẤT   (TT 08/2024 — tô nền đặc)
 *   · QUY HOẠCH XÂY DỰNG      (TT 16/2025 — nền nhạt + viền đậm)
 *
 * Thửa đang lập biên bản luôn nằm CHÍNH GIỮA khung và được kẻ viền đậm, kể cả
 * khi nó giáp biên hoặc giáp tờ bản đồ — vì khung ngắm được tính từ TÂM THỬA
 * chứ không phải từ bao hình của dữ liệu quy hoạch. Vùng quy hoạch nào lọt vào
 * khung thì vẽ, phần thừa bị cắt bởi clipRect.
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

    /** Bề rộng thực tế (m) của khung ngắm, tính từ kích thước thửa. */
    private fun tamNgam(verts: List<Pair<Double, Double>>): Triple<Double, Double, Double> {
        val n0 = verts.minOf { it.first };  val n1 = verts.maxOf { it.first }
        val e0 = verts.minOf { it.second }; val e1 = verts.maxOf { it.second }
        val cn = (n0 + n1) / 2.0
        val ce = (e0 + e1) / 2.0
        // Nới 2,2 lần cạnh dài nhất để thấy được bối cảnh xung quanh thửa,
        // tối thiểu 60 m cho thửa nhỏ.
        val span = max(max(n1 - n0, e1 - e0) * 2.2, 60.0)
        return Triple(cn, ce, span)
    }

    /**
     * Vẽ MỘT khung quy hoạch.
     * @return danh sách (mã, tên, màu) thực sự xuất hiện trong khung — để làm chú dẫn.
     */
    fun veKhung(
        canvas : Canvas,
        frame  : RectF,
        vung   : List<Vung>,
        verts  : List<Pair<Double, Double>>,
        vienDam: Boolean            // true = lớp XD, vẽ thêm viền màu đậm
    ): List<Triple<String, String, Int>> {
        canvas.drawRect(frame, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f; color = Color.BLACK
        })
        if (verts.isEmpty()) return emptyList()

        val (cn, ce, span) = tamNgam(verts)
        val s = min(frame.width(), frame.height()) / span          // pt trên mét
        val fx = frame.centerX(); val fy = frame.centerY()
        // E tăng sang phải, N tăng lên trên
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
        // Vẽ vùng LỚN TRƯỚC để vùng nhỏ nằm trên, giống cách app vẽ trên bản đồ.
        vung.sortedByDescending { dienTich(it.ngoai) }.forEach { v ->
            if (v.ngoai.size < 3) return@forEach
            val path = Path()
            duong(path, v.ngoai, ::px)
            v.lo.forEach { if (it.size >= 3) duong(path, it, ::px) }
            path.fillType = Path.FillType.EVEN_ODD          // lỗ thủng thành lỗ thật
            fill.color = Color.argb(255, Color.red(v.mau), Color.green(v.mau),
                                    Color.blue(v.mau))
            // Trên giấy in thì tô ĐẶC cả hai lớp; phân biệt bằng viền.
            canvas.drawPath(path, fill)
            if (vienDam) {
                vien.color = Color.argb(230, Color.red(v.mau) / 2,
                                        Color.green(v.mau) / 2, Color.blue(v.mau) / 2)
                canvas.drawPath(path, vien)
            }
            co.putIfAbsent(v.ma, Triple(v.ma, v.ten, v.mau))
        }

        // ── Ranh thửa đang lập biên bản — chồng lên trên, viền đậm ──
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

        // thước tỉ lệ nhỏ trong khung
        thuoc(canvas, frame.left + 8f, frame.bottom - 8f, s.toDouble())
        return co.values.toList()
    }

    /** Chú dẫn ký hiệu nền tô: ô màu + mã + tên, xếp 2 cột. */
    fun veChuDan(
        canvas: Canvas, x: Float, y: Float, rong: Float,
        muc: List<Triple<String, String, Int>>, cao: Float = 11f
    ): Float {
        val p = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 7.2f }
        val cot = if (muc.size > 8) 2 else 1
        val wCot = rong / cot
        var yy = y
        muc.forEachIndexed { i, (ma, ten, mau) ->
            val c = i % cot
            val r = i / cot
            val lx = x + c * wCot
            val ly = y + r * cao
            canvas.drawRect(RectF(lx, ly, lx + 12f, ly + 7.5f), Paint().apply {
                color = Color.argb(255, Color.red(mau), Color.green(mau), Color.blue(mau))
            })
            canvas.drawRect(RectF(lx, ly, lx + 12f, ly + 7.5f), Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 0.5f; color = Color.DKGRAY
            })
            val nhan = if (ten.isBlank()) ma else "$ma — $ten"
            canvas.drawText(cat(nhan, p, wCot - 18f), lx + 15f, ly + 7f, p)
            yy = max(yy, ly + cao)
        }
        return yy
    }

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
        return kotlin.math.abs(s) / 2.0
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
        val bg = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        c.drawRect(x - 2f, y - 12f, x + w + 26f, y + 3f, bg)
        c.drawRect(x, y - 4f, x + w, y, Paint().apply { color = Color.BLACK })
        c.drawText("$m m", x + w + 3f, y + 1f,
            Paint().apply { isAntiAlias = true; textSize = 6.5f; color = Color.BLACK })
    }
}
