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
import kotlin.math.sqrt

/**
 * BienBanSketch — Vẽ SƠ HOẠ VỊ TRÍ MỐC CẮM cho biên bản.
 *
 * Nguyên tắc trình bày (đã tinh chỉnh theo góp ý thực tế):
 *   • Thửa CHÍNH: TÔ NỀN nhạt, viền MẢNH (đã tô nền thì không cần nét đậm).
 *   • Nhãn thửa chính TRẢI theo hình thửa — cỡ chữ tự co để lọt trong lòng thửa,
 *     đặt tại trọng tâm diện tích (không phải trung bình đỉnh) nên ít đè cạnh.
 *   • Mọi chữ đều có MẶT NẠ trắng viền quanh → không bị nét vẽ cắt ngang.
 *   • Chống ĐÈ NHAU: giữ danh sách vùng đã chiếm; nhãn nào va chạm thì
 *     thử vị trí khác, không được thì bỏ qua (thà thiếu còn hơn rối).
 *
 * Toạ độ VN-2000: X = Northing (Bắc), Y = Easting (Đông)
 * → giấy: ngang = Y, dọc = X đảo chiều (Bắc lên trên).
 */
object BienBanSketch {

    fun draw(
        canvas       : Canvas,
        frame        : RectF,
        mainVertices : List<Pair<Double, Double>>,
        mainLabel    : ParcelLabel,
        neighbours   : List<NeighbourParcel> = emptyList(),
        zoom         : Float = 0.72f,
        /** Tâm khung do người dùng chọn (N, E). null = lấy tâm thửa chính. */
        centerN      : Double? = null,
        centerE      : Double? = null
    ) {
        if (mainVertices.size < 3) return

        // Vùng đã bị chiếm bởi chữ — dùng để chống đè
        val taken = ArrayList<RectF>()

        canvas.drawRect(frame, Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 1.2f; color = Color.BLACK
        })

        // ── Phép chiếu toạ độ → giấy ──
        val minN = mainVertices.minOf { it.first };  val maxN = mainVertices.maxOf { it.first }
        val minE = mainVertices.minOf { it.second }; val maxE = mainVertices.maxOf { it.second }
        val cN = centerN ?: ((minN + maxN) / 2.0)
        val cE = centerE ?: ((minE + maxE) / 2.0)
        val spanN = (maxN - minN).coerceAtLeast(1e-6)
        val spanE = (maxE - minE).coerceAtLeast(1e-6)

        val pad = 22f
        val scale = (min((frame.width() - pad * 2) / spanE,
                         (frame.height() - pad * 2) / spanN) * zoom).toDouble()
        val fx = frame.centerX(); val fy = frame.centerY()
        fun px(e: Double) = (fx + (e - cE) * scale).toFloat()
        fun py(n: Double) = (fy - (n - cN) * scale).toFloat()

        canvas.save()
        canvas.clipRect(frame)

        val mainPts = mainVertices.map { (n, e) -> px(e) to py(n) }

        // ── 1. Thửa giáp biên (nền) ──
        val nbStroke = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 0.7f; color = Color.rgb(130, 130, 130)
        }
        val nbLabelP = Paint().apply {
            isAntiAlias = true; color = Color.rgb(80, 80, 80)
            textSize = 8.5f; textAlign = Paint.Align.CENTER
        }
        val nbBoxes = ArrayList<Pair<String, Pair<Float, Float>>>()

        neighbours.forEach { nb ->
            if (nb.vertices.size < 3) return@forEach
            val pts = nb.vertices.map { (n, e) -> px(e) to py(n) }
            val path = Path()
            pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            path.close()
            canvas.drawPath(path, nbStroke)
            if (nb.soThua.isNotBlank()) {
                val cpt = centroid(pts)
                nbBoxes += nb.soThua to cpt
            }
        }

        // ── 2. Thửa chính: TÔ NỀN + viền mảnh ──
        val mainPath = Path()
        mainPts.forEachIndexed { i, (x, y) -> if (i == 0) mainPath.moveTo(x, y) else mainPath.lineTo(x, y) }
        mainPath.close()
        canvas.drawPath(mainPath, Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL
            color = Color.argb(38, 0, 0, 0)          // nền xám nhạt
        })
        canvas.drawPath(mainPath, Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            // CÙNG lực nét với thửa giáp (0.7f): thửa chính đã tô xám nên tự nổi,
            // kẻ đậm hơn nữa làm hình nặng và lệch tông so với các thửa xung quanh.
            strokeWidth = 0.7f
            color = Color.BLACK
        })

        // ── 3. Nhãn thửa giáp biên (chỉ SỐ THỬA, gạch chân) ──
        nbBoxes.forEach { (so, c) ->
            val (x, y) = c
            if (!frame.contains(x, y)) return@forEach
            // Không đặt nhãn nếu rơi vào trong thửa chính (tránh đè nhãn chính)
            if (pointInPoly(x, y, mainPts)) return@forEach
            val r = textRect(so, x, y, nbLabelP, padX = 3f, padY = 3f)
            if (taken.any { RectF.intersects(it, r) }) return@forEach
            taken += r
            drawHaloText(canvas, so, x, y, nbLabelP)
            val half = nbLabelP.measureText(so) / 2f
            canvas.drawLine(x - half, y + 2f, x + half, y + 2f, Paint().apply {
                isAntiAlias = true; color = Color.rgb(80, 80, 80); strokeWidth = 0.7f
            })
        }

        // ── 4. Số hiệu đỉnh — đẩy RA NGOÀI theo hướng từ tâm, chống đè ──
        val cMain = centroid(mainPts)
        val idxP = Paint().apply {
            isAntiAlias = true; color = Color.BLACK
            textSize = 8.5f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        val dotP = Paint().apply { isAntiAlias = true; color = Color.BLACK }

        mainPts.forEachIndexed { i, (x, y) ->
            canvas.drawCircle(x, y, 2.2f, dotP)
            val vx = x - cMain.first; val vy = y - cMain.second
            val len = sqrt(vx * vx + vy * vy).coerceAtLeast(0.001f)
            // Thử đẩy xa dần cho tới khi không va chạm
            var placed = false
            for (d in floatArrayOf(9f, 14f, 19f, 25f)) {
                val lx = x + vx / len * d
                val ly = y + vy / len * d + 3f
                val r = textRect("${i + 1}", lx, ly, idxP, padX = 2.5f, padY = 2.5f)
                if (taken.none { RectF.intersects(it, r) } && frame.contains(lx, ly)) {
                    taken += r
                    drawHaloText(canvas, "${i + 1}", lx, ly, idxP)
                    placed = true; break
                }
            }
            if (!placed) { /* bỏ qua nhãn đè — giữ hình sạch */ }
        }

        // ── 5. Nhãn thửa chính — TRẢI theo hình thửa, tự co cỡ chữ ──
        // Thứ tự theo mẫu địa chính, từ trên xuống:
        //     Loại ruộng đất
        //     Số hiệu thửa
        //     ────────────      <- gạch ngang kiểu phân số
        //     Diện tích
        val lines = listOfNotNull(
            mainLabel.loaiDat.ifBlank { null },
            mainLabel.soThua.ifBlank { null },
            mainLabel.dienTich.ifBlank { null }
        )
        // Vị trí dòng cần kẻ gạch phân số = ngay TRƯỚC dòng diện tích
        val iGach = if (mainLabel.soThua.isNotBlank() && mainLabel.dienTich.isNotBlank())
            lines.indexOf(mainLabel.dienTich) else -1
        if (lines.isNotEmpty()) {
            // Bề rộng lòng thửa tại vị trí đặt nhãn (đo theo bao hình cho đơn giản)
            val wMain = (mainPts.maxOf { it.first } - mainPts.minOf { it.first })
            val hMain = (mainPts.maxOf { it.second } - mainPts.minOf { it.second })
            val mainP = Paint().apply {
                isAntiAlias = true; color = Color.BLACK
                isFakeBoldText = true; textAlign = Paint.Align.CENTER
            }
            // Co cỡ chữ để dòng dài nhất lọt trong ~72% bề rộng thửa
            var ts = 11f
            while (ts > 5.5f) {
                mainP.textSize = ts
                val wide = lines.maxOf { mainP.measureText(it) }
                if (wide <= wMain * 0.72f && lines.size * (ts + 2.5f) <= hMain * 0.72f) break
                ts -= 0.5f
            }
            mainP.textSize = ts
            val lh = ts + 2.5f
            lines.forEachIndexed { i, t ->
                val ly = cMain.second + (i - (lines.size - 1) / 2f) * lh
                drawHaloText(canvas, t, cMain.first, ly, mainP)
                taken += textRect(t, cMain.first, ly, mainP, padX = 2f, padY = 2f)
                // Gạch ngang kiểu phân số giữa Số hiệu thửa và Diện tích
                if (i == iGach) {
                    val wGach = maxOf(mainP.measureText(lines[i]),
                                      mainP.measureText(lines[maxOf(i - 1, 0)])) / 2f + 2f
                    // Gạch nằm CHÍNH GIỮA khoảng hở giữa hai dòng chữ, tính theo
                    // MÉP CHỮ chứ không theo đường cơ sở: mép dưới dòng trên ở
                    // ly-lh+0.20*ts (chân chữ), mép trên dòng dưới ở ly-0.72*ts.
                    // Lấy 0.62*lh như trước làm gạch tụt xuống sát dòng diện tích.
                    val yGach = ly - (lh + 0.52f * ts) / 2f
                    canvas.drawLine(cMain.first - wGach, yGach, cMain.first + wGach, yGach,
                        Paint().apply {
                            isAntiAlias = true; color = Color.WHITE; strokeWidth = 2.6f
                        })
                    canvas.drawLine(cMain.first - wGach, yGach, cMain.first + wGach, yGach,
                        Paint().apply {
                            isAntiAlias = true; color = Color.BLACK; strokeWidth = 1.0f
                        })
                }
            }
        }

        canvas.restore()

        // ── 6. Hướng Bắc + thước tỉ lệ (có nền che) ──
        drawNorth(canvas, frame.right - 26f, frame.top + 26f)
        drawScaleBar(canvas, frame.left + 14f, frame.bottom - 12f, scale)
    }

    data class ParcelLabel(
        val soThua   : String = "",
        val dienTich : String = "",
        val loaiDat  : String = ""
    )

    data class NeighbourParcel(
        val soThua   : String,
        val vertices : List<Pair<Double, Double>>   // (N, E)
    )

    /**
     * @param minExpandM Bán kính tìm TỐI THIỂU (mét) quanh thửa chính.
     *   Chỉ nới theo tỉ lệ kích thước thửa là chưa đủ: thửa nhỏ 20–40 m sẽ có
     *   vùng tìm rất hẹp, bỏ sót thửa bên kia đường/kênh và thửa thuộc tờ khác.
     */
    fun pickNeighbours(
        all        : List<VectorLayerImporter.VectorFeature>,
        mainId     : Int,
        mainVerts  : List<Pair<Double, Double>>,
        expand     : Double = 1.2,
        maxCount   : Int = 40,
        minExpandM : Double = 120.0
    ): List<NeighbourParcel> {
        if (mainVerts.isEmpty()) return emptyList()
        val nMin = mainVerts.minOf { it.first };  val nMax = mainVerts.maxOf { it.first }
        val eMin = mainVerts.minOf { it.second }; val eMax = mainVerts.maxOf { it.second }
        // rawPoints là VN-2000 (mét) nên so sánh trực tiếp bằng mét
        val dn = max((nMax - nMin) * expand, minExpandM)
        val de = max((eMax - eMin) * expand, minExpandM)

        // QUAN TRỌNG: phải SẮP XẾP THEO KHOẢNG CÁCH rồi mới cắt.
        // Cắt theo thứ tự danh sách sẽ vớ phải thửa xa nằm đầu tờ và bỏ mất
        // chính những thửa ôm sát thửa chính (chúng thường nằm cuối danh sách).
        return all.asSequence()
            .filter { it.id != mainId && it.type == VectorLayerImporter.FeatureType.POLYGON }
            .filter { it.rawPoints.size >= 3 }
            .mapNotNull { f ->
                val vs = f.rawPoints.map { it.second to it.first }   // (N, E)
                val bn0 = vs.minOf { it.first };  val bn1 = vs.maxOf { it.first }
                val be0 = vs.minOf { it.second }; val be1 = vs.maxOf { it.second }
                val hit = bn1 >= nMin - dn && bn0 <= nMax + dn &&
                          be1 >= eMin - de && be0 <= eMax + de
                if (!hit) return@mapNotNull null
                // KHE HỞ giữa hai bao hình: 0 = chạm/chồng nhau (giáp biên thật)
                val gapN = max(0.0, max(nMin - bn1, bn0 - nMax))
                val gapE = max(0.0, max(eMin - be1, be0 - eMax))
                val gap  = kotlin.math.hypot(gapN, gapE)
                Triple(gap, f, vs)
            }
            .sortedBy { it.first }            // gần nhất lên trước
            .take(maxCount)
            .map { (_, f, vs) -> NeighbourParcel(f.soThua.ifBlank { f.label }, vs) }
            .toList()
    }

    // ══════════════════════════════════════════════════════════
    // Tiện ích vẽ
    // ══════════════════════════════════════════════════════════

    /**
     * Vẽ chữ có MẶT NẠ trắng: vẽ viền trắng dày trước, rồi mới vẽ chữ đen lên.
     * Nhờ vậy chữ luôn đọc được dù nằm trên cạnh thửa hay nét vẽ khác.
     */
    private fun drawHaloText(c: Canvas, text: String, x: Float, y: Float, p: Paint) {
        val halo = Paint(p).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
            color = Color.WHITE
        }
        c.drawText(text, x, y, halo)
        c.drawText(text, x, y, Paint(p).apply { style = Paint.Style.FILL })
    }

    /** Hộp bao của chữ (dùng để phát hiện đè nhau). Giả định textAlign = CENTER. */
    private fun textRect(
        text: String, x: Float, y: Float, p: Paint, padX: Float, padY: Float
    ): RectF {
        val w = p.measureText(text) / 2f + padX
        val h = p.textSize / 2f + padY
        return RectF(x - w, y - h, x + w, y + h)
    }

    /** Trọng tâm DIỆN TÍCH của đa giác — đặt nhãn chuẩn hơn trung bình đỉnh. */
    private fun centroid(pts: List<Pair<Float, Float>>): Pair<Float, Float> {
        var a = 0.0; var cx = 0.0; var cy = 0.0
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            val cross = (x1.toDouble() * y2 - x2.toDouble() * y1)
            a += cross
            cx += (x1 + x2) * cross
            cy += (y1 + y2) * cross
        }
        a *= 0.5
        return if (abs(a) < 1e-6)
            pts.map { it.first }.average().toFloat() to pts.map { it.second }.average().toFloat()
        else
            (cx / (6 * a)).toFloat() to (cy / (6 * a)).toFloat()
    }

    /** Điểm có nằm trong đa giác không (ray casting). */
    private fun pointInPoly(x: Float, y: Float, pts: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = pts.lastIndex
        for (i in pts.indices) {
            val (xi, yi) = pts[i]; val (xj, yj) = pts[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Mũi tên Bắc — có nền trắng bo tròn để không lẫn vào nét thửa. */
    private fun drawNorth(c: Canvas, x: Float, y: Float) {
        c.drawRoundRect(
            RectF(x - 11f, y - 18f, x + 11f, y + 22f), 4f, 4f,
            Paint().apply { isAntiAlias = true; color = Color.argb(215, 255, 255, 255) }
        )
        val p = Paint().apply { isAntiAlias = true; color = Color.BLACK; style = Paint.Style.FILL }
        c.drawPath(Path().apply {
            moveTo(x, y - 14f); lineTo(x - 5f, y + 5f); lineTo(x, y + 0.5f); lineTo(x + 5f, y + 5f); close()
        }, p)
        c.drawText("B", x, y + 17f, Paint().apply {
            isAntiAlias = true; color = Color.BLACK; textSize = 9f
            isFakeBoldText = true; textAlign = Paint.Align.CENTER
        })
    }

    private fun drawScaleBar(c: Canvas, x: Float, y: Float, scale: Double) {
        val nice = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
        val meters = nice.lastOrNull { it * scale <= 85.0 } ?: 1.0
        val w = (meters * scale).toFloat()
        c.drawRect(
            RectF(x - 4f, y - 9f, x + w + 30f, y + 7f),
            Paint().apply { isAntiAlias = true; color = Color.argb(215, 255, 255, 255) }
        )
        val p = Paint().apply { isAntiAlias = true; color = Color.BLACK; strokeWidth = 1.4f }
        c.drawLine(x, y, x + w, y, p)
        c.drawLine(x, y - 3f, x, y + 3f, p)
        c.drawLine(x + w, y - 3f, x + w, y + 3f, p)
        c.drawText("${meters.toInt()} m", x + w + 4f, y + 3f, Paint().apply {
            isAntiAlias = true; color = Color.BLACK; textSize = 8.5f
        })
    }

    internal fun span(a: Double, b: Double) = abs(a - b)
}
