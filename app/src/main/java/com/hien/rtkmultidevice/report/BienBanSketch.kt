package com.hien.rtkmultidevice.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * BienBanSketch — Vẽ SƠ HOẠ VỊ TRÍ MỐC CẮM cho biên bản.
 *
 * Cách trình bày (theo yêu cầu):
 *   • Thửa CHÍNH nằm giữa khung, tô nhạt, viền đậm, đánh số đỉnh 1..n
 *     và ghi nhãn đầy đủ: Số thửa / Diện tích / Loại đất (như trên bản đồ app).
 *   • Thửa GIÁP BIÊN vẽ viền mảnh, chỉ ghi SỐ THỬA và có GẠCH CHÂN.
 *   • Mũi tên hướng Bắc + thước tỉ lệ.
 *
 * Toạ độ VN-2000: X = Northing (lên Bắc), Y = Easting (sang Đông)
 * → trên giấy: trục ngang = Y, trục dọc = X và ĐẢO CHIỀU (Bắc lên trên).
 */
object BienBanSketch {

    /**
     * Vẽ sơ hoạ vào canvas trong khung [frame].
     *
     * @param mainVertices đỉnh thửa chính (N, E) theo thứ tự
     * @param neighbours   các thửa giáp biên để vẽ nền
     * @param zoom         hệ số phóng: 1.0 = vừa khít thửa chính; <1 thấy rộng hơn
     */
    fun draw(
        canvas       : Canvas,
        frame        : RectF,
        mainVertices : List<Pair<Double, Double>>,
        mainLabel    : ParcelLabel,
        neighbours   : List<NeighbourParcel> = emptyList(),
        zoom         : Float = 0.75f
    ) {
        if (mainVertices.size < 3) return

        // ── 1. Khung sơ hoạ ──
        val framePaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 1.2f; color = Color.BLACK
        }
        canvas.drawRect(frame, framePaint)

        // ── 2. Tính phép chiếu toạ độ → giấy ──
        var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        var minE = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE
        mainVertices.forEach { (n, e) ->
            minN = min(minN, n); maxN = max(maxN, n)
            minE = min(minE, e); maxE = max(maxE, e)
        }
        val cN = (minN + maxN) / 2.0
        val cE = (minE + maxE) / 2.0
        val spanN = (maxN - minN).coerceAtLeast(1e-6)
        val spanE = (maxE - minE).coerceAtLeast(1e-6)

        val pad = 24f
        val usableW = frame.width()  - pad * 2
        val usableH = frame.height() - pad * 2
        // Tỉ lệ: mét → điểm ảnh. zoom<1 để chừa chỗ cho thửa giáp biên
        val scale = (min(usableW / spanE, usableH / spanN) * zoom).toDouble()

        val cx = frame.centerX()
        val cy = frame.centerY()
        fun px(e: Double) = (cx + (e - cE) * scale).toFloat()
        fun py(n: Double) = (cy - (n - cN) * scale).toFloat()   // Bắc lên trên

        canvas.save()
        canvas.clipRect(frame)

        // ── 3. Thửa giáp biên (vẽ trước, nằm dưới) ──
        val nbPaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 0.8f; color = Color.rgb(120, 120, 120)
        }
        val nbText = Paint().apply {
            isAntiAlias = true; color = Color.rgb(90, 90, 90)
            textSize = 9f; textAlign = Paint.Align.CENTER
        }
        neighbours.forEach { nb ->
            if (nb.vertices.size < 3) return@forEach
            val path = Path()
            nb.vertices.forEachIndexed { i, (n, e) ->
                if (i == 0) path.moveTo(px(e), py(n)) else path.lineTo(px(e), py(n))
            }
            path.close()
            canvas.drawPath(path, nbPaint)

            // Nhãn thửa giáp biên: CHỈ số thửa + GẠCH CHÂN
            if (nb.soThua.isNotBlank()) {
                val gx = nb.vertices.map { px(it.second) }.average().toFloat()
                val gy = nb.vertices.map { py(it.first) }.average().toFloat()
                if (frame.contains(gx, gy)) {
                    canvas.drawText(nb.soThua, gx, gy, nbText)
                    val half = nbText.measureText(nb.soThua) / 2f
                    canvas.drawLine(gx - half, gy + 2f, gx + half, gy + 2f, nbPaint)
                }
            }
        }

        // ── 4. Thửa chính ──
        val mainPath = Path()
        mainVertices.forEachIndexed { i, (n, e) ->
            if (i == 0) mainPath.moveTo(px(e), py(n)) else mainPath.lineTo(px(e), py(n))
        }
        mainPath.close()
        canvas.drawPath(mainPath, Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL
            color = Color.argb(28, 0, 0, 0)
        })
        canvas.drawPath(mainPath, Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 2f; color = Color.BLACK
        })

        // Đỉnh + số hiệu
        val dotPaint = Paint().apply { isAntiAlias = true; color = Color.BLACK }
        val idxPaint = Paint().apply {
            isAntiAlias = true; color = Color.BLACK
            textSize = 10f; isFakeBoldText = true
        }
        mainVertices.forEachIndexed { i, (n, e) ->
            val x = px(e); val y = py(n)
            canvas.drawCircle(x, y, 2.5f, dotPaint)
            // Đẩy nhãn ra ngoài tâm thửa cho khỏi đè cạnh
            val ox = if (x >= cx) 6f else -12f
            val oy = if (y >= cy) 12f else -5f
            canvas.drawText("${i + 1}", x + ox, y + oy, idxPaint)
        }

        // Nhãn thửa chính: Số thửa / Diện tích / Loại đất (giữa thửa)
        val gx = mainVertices.map { px(it.second) }.average().toFloat()
        val gy = mainVertices.map { py(it.first) }.average().toFloat()
        val mainText = Paint().apply {
            isAntiAlias = true; color = Color.BLACK
            textSize = 11f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        val lines = listOfNotNull(
            mainLabel.soThua.ifBlank { null },
            mainLabel.loaiDat.ifBlank { null },
            mainLabel.dienTich.ifBlank { null }
        )
        lines.forEachIndexed { i, t ->
            canvas.drawText(t, gx, gy + (i - (lines.size - 1) / 2f) * 13f, mainText)
        }

        canvas.restore()

        // ── 5. Mũi tên Bắc + thước tỉ lệ ──
        drawNorth(canvas, frame.right - 30f, frame.top + 30f)
        drawScaleBar(canvas, frame.left + 16f, frame.bottom - 14f, scale)
    }

    /** Nhãn đầy đủ của thửa chính. */
    data class ParcelLabel(
        val soThua   : String = "",
        val dienTich : String = "",
        val loaiDat  : String = ""
    )

    /** Thửa giáp biên — chỉ cần hình và số thửa. */
    data class NeighbourParcel(
        val soThua   : String,
        val vertices : List<Pair<Double, Double>>   // (N, E)
    )

    /**
     * Chọn các thửa GIÁP BIÊN quanh thửa chính: lấy những polygon có bao hình
     * giao với vùng quanh thửa chính (mở rộng theo [expand] lần kích thước thửa).
     */
    fun pickNeighbours(
        all       : List<VectorLayerImporter.VectorFeature>,
        mainId    : Int,
        mainVerts : List<Pair<Double, Double>>,
        expand    : Double = 1.2,
        maxCount  : Int = 12
    ): List<NeighbourParcel> {
        if (mainVerts.isEmpty()) return emptyList()
        val nMin = mainVerts.minOf { it.first };  val nMax = mainVerts.maxOf { it.first }
        val eMin = mainVerts.minOf { it.second }; val eMax = mainVerts.maxOf { it.second }
        val dn = (nMax - nMin) * expand; val de = (eMax - eMin) * expand

        return all.asSequence()
            .filter { it.id != mainId && it.type == VectorLayerImporter.FeatureType.POLYGON }
            .filter { it.rawPoints.size >= 3 }
            .mapNotNull { f ->
                // rawPoints VN-2000: (Easting, Northing)
                val vs = f.rawPoints.map { it.second to it.first }
                val bn0 = vs.minOf { it.first };  val bn1 = vs.maxOf { it.first }
                val be0 = vs.minOf { it.second }; val be1 = vs.maxOf { it.second }
                val hit = bn1 >= nMin - dn && bn0 <= nMax + dn &&
                          be1 >= eMin - de && be0 <= eMax + de
                if (hit) NeighbourParcel(f.soThua.ifBlank { f.label }, vs) else null
            }
            .take(maxCount)
            .toList()
    }

    // ── phụ trợ vẽ ───────────────────────────────────────────
    private fun drawNorth(c: Canvas, x: Float, y: Float) {
        val p = Paint().apply { isAntiAlias = true; color = Color.BLACK; style = Paint.Style.FILL }
        val path = Path().apply {
            moveTo(x, y - 14f); lineTo(x - 5f, y + 6f); lineTo(x, y + 1f); lineTo(x + 5f, y + 6f); close()
        }
        c.drawPath(path, p)
        c.drawText("B", x - 3.5f, y + 18f, Paint().apply {
            isAntiAlias = true; color = Color.BLACK; textSize = 10f; isFakeBoldText = true
        })
    }

    /** Thước tỉ lệ: chọn độ dài chẵn (1/2/5/10/20/50 m) vừa với khung. */
    private fun drawScaleBar(c: Canvas, x: Float, y: Float, scale: Double) {
        val nice = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
        val meters = nice.lastOrNull { it * scale <= 90.0 } ?: 1.0
        val w = (meters * scale).toFloat()
        val p = Paint().apply { isAntiAlias = true; color = Color.BLACK; strokeWidth = 1.5f }
        c.drawLine(x, y, x + w, y, p)
        c.drawLine(x, y - 3f, x, y + 3f, p)
        c.drawLine(x + w, y - 3f, x + w, y + 3f, p)
        c.drawText("${meters.toInt()} m", x + w + 5f, y + 3.5f, Paint().apply {
            isAntiAlias = true; color = Color.BLACK; textSize = 9f
        })
    }

    /** Khoảng cách tuyệt đối — tiện cho kiểm tra vẽ. */
    internal fun span(a: Double, b: Double) = abs(a - b)
}
