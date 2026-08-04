package com.hien.rtkmultidevice.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * BienBanPdf — Xuất BIÊN BẢN BÀN GIAO MỐC GIỚI ra PDF khổ A4, IN 2 MẶT.
 *
 *   Mặt 1 (trang 1): phần văn bản — nội dung bàn giao, bên giao, bên nhận, chữ ký.
 *   Mặt 2 (trang 2): BẢNG KÊ TOẠ ĐỘ MỐC GIỚI + SƠ HOẠ VỊ TRÍ MỐC CẮM
 *                    + xác nhận chủ sử dụng đất lân cận.
 *
 * A4 @72dpi = 595 × 842 pt. Lề 56 pt (~2 cm).
 *
 * Chữ tiếng Việt: dùng font hệ thống (Roboto) — hỗ trợ đầy đủ dấu.
 */
object BienBanPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 56f

    /**
     * @param sketch Hàm vẽ sơ hoạ vào khung cho sẵn (null = để khung trống).
     */
    fun export(
        outFile : File,
        b       : BienBan,
        sketch  : ((Canvas, RectF) -> Unit)? = null
    ): File {
        val doc = PdfDocument()
        drawPage1(doc, b)
        drawPage2(doc, b, sketch)
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    // Bề rộng cột bảng toạ độ (pt). Cột "Đỉnh thửa" đủ rộng cho tiêu đề,
    // bù lại thu hẹp X/Y vì số liệu ngắn hơn ô.
    private const val W_DINH = 54f
    private const val W_X    = 70f
    private const val W_Y    = 68f
    private const val W_KC   = 40f
    private const val TBL_W  = W_DINH + W_X + W_Y + W_KC   // 232

    // ══════════════════════════════════════════════════════════
    // MẶT 1 — phần văn bản
    // ══════════════════════════════════════════════════════════

    private fun drawPage1(doc: PdfDocument, b: BienBan) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val c = page.canvas
        var y = MARGIN + 10f

        val center = paint(11f, bold = true, align = Paint.Align.CENTER)
        val body   = paint(11f)
        val bodyB  = paint(11f, bold = true)
        val cx     = PAGE_W / 2f

        // Quốc hiệu
        c.drawText("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", cx, y, center); y += 16f
        c.drawText("Độc Lập - Tự Do - Hạnh Phúc", cx, y, paint(11f, italic = true, align = Paint.Align.CENTER))
        y += 8f
        c.drawLine(cx - 90f, y, cx + 90f, y, Paint().apply { strokeWidth = 1f })
        y += 34f

        c.drawText("BIÊN BẢN BÀN GIAO MỐC GIỚI", cx, y, paint(15f, bold = true, align = Paint.Align.CENTER))
        y += 30f

        // Đoạn mở đầu
        val doan = "Hôm nay, ngày ${b.ngay} tháng ${b.thang} năm ${b.nam} tại khu đất thuộc thửa đất số " +
            "${b.soThua} tờ bản đồ địa chính số ${b.soTo} ${b.xa}, ${b.huyen}, ${b.tinh}, " +
            "đã được ${b.donViTen} thực hiện cắm mốc giới xác định ranh giới thửa đất (khu đất). " +
            "Đơn vị đo đạc tiến hành bàn giao ${b.soMocText} mốc ranh cho chủ sử dụng đất là ${b.chuSuDung}."
        y = wrap(c, doan, MARGIN, y, PAGE_W - MARGIN * 2, body, 16f) + 10f

        y = wrap(c,
            "Kể từ thời điểm bàn giao, ${b.xungHo} ${b.chuSuDung} có trách nhiệm bảo quản mốc.",
            MARGIN, y, PAGE_W - MARGIN * 2, body, 16f) + 8f

        y = wrap(c,
            "${b.donViTen} chịu trách nhiệm về mặt kỹ thuật đo đạc và sự thống nhất giữa mốc trên thực địa " +
            "và trên hồ sơ. Toạ độ các mốc cắm ở thực địa được ${b.xungHo} ${b.chuSuDung} cung cấp theo " +
            "Giấy chứng nhận quyền sử dụng đất có số phát hành: ${b.soPhatHanhGcn} do ${b.noiCapGcn} " +
            "cấp ngày ${b.ngayCapGcn}.",
            MARGIN, y, PAGE_W - MARGIN * 2, body, 16f) + 20f

        // 1. Bên giao mốc
        c.drawText("1. Bên giao mốc:", MARGIN, y, bodyB); y += 18f
        c.drawText(b.donViTen.uppercase(), MARGIN, y, bodyB); y += 16f
        c.drawText("Đại diện: ${b.donViDaiDien}     Chức vụ: ${b.donViChucVu}", MARGIN, y, body); y += 16f
        y = wrap(c, "Địa chỉ: ${b.donViDiaChi}", MARGIN, y, PAGE_W - MARGIN * 2, body, 16f)
        if (b.donViVpdd.isNotBlank())
            y = wrap(c, "VPĐD: ${b.donViVpdd}", MARGIN, y, PAGE_W - MARGIN * 2, body, 16f)
        y += 14f

        // 2. Bên nhận mốc
        c.drawText("2. Bên nhận mốc:", MARGIN, y, bodyB); y += 18f
        c.drawText("Chủ đầu tư (sử dụng đất): ${b.chuSuDung}", MARGIN, y, body); y += 16f
        y = wrap(c, "Địa chỉ: ${b.diaChiChu}", MARGIN, y, PAGE_W - MARGIN * 2, body, 16f) + 20f

        // 3. Xác nhận
        c.drawText("3. Xác nhận của các bên liên quan", MARGIN, y, bodyB); y += 28f
        val col1 = PAGE_W * 0.28f
        val col2 = PAGE_W * 0.72f
        c.drawText("BÊN GIAO MỐC", col1, y, paint(11f, bold = true, align = Paint.Align.CENTER))
        c.drawText("BÊN NHẬN MỐC", col2, y, paint(11f, bold = true, align = Paint.Align.CENTER))
        y += 16f
        c.drawText(b.donViTen, col1, y, paint(9f, align = Paint.Align.CENTER))
        c.drawText(b.chuSuDung, col2, y, paint(10f, align = Paint.Align.CENTER))

        doc.finishPage(page)
    }

    // ══════════════════════════════════════════════════════════
    // MẶT 2 — bảng kê toạ độ + sơ hoạ
    // ══════════════════════════════════════════════════════════

    private fun drawPage2(doc: PdfDocument, b: BienBan, sketch: ((Canvas, RectF) -> Unit)?) {
        val rowH  = 17f
        val cellB = paint(9.5f, bold = true, align = Paint.Align.CENTER)
        val cell  = paint(9.5f, align = Paint.Align.CENTER)
        val line  = Paint().apply { strokeWidth = 0.8f; color = Color.BLACK }
        val tblL  = MARGIN
        val colX  = floatArrayOf(
            tblL, tblL + W_DINH, tblL + W_DINH + W_X,
            tblL + W_DINH + W_X + W_Y, tblL + TBL_W
        )

        var pageNo   = 2
        var rowIdx   = 0
        var firstPg  = true

        while (rowIdx < b.moc.size || firstPg) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val c = page.canvas
            var y = MARGIN + 10f
            val cx = PAGE_W / 2f

            if (firstPg) {
                c.drawText("BẢNG KÊ TOẠ ĐỘ MỐC GIỚI", cx, y,
                    paint(13f, bold = true, align = Paint.Align.CENTER))
                y += 18f
                c.drawText(
                    "(Hệ toạ độ VN-2000, kinh tuyến trục ${b.kinhTuyenTruc}, múi chiếu ${b.muiChieu})",
                    cx, y, paint(9f, italic = true, align = Paint.Align.CENTER)
                )
                y += 22f
            } else {
                c.drawText("BẢNG KÊ TOẠ ĐỘ MỐC GIỚI (tiếp theo)", cx, y,
                    paint(11f, bold = true, align = Paint.Align.CENTER))
                y += 22f
            }

            val tblTop = y

            // ── Sơ hoạ: chỉ vẽ ở trang đầu của bảng ──
            var skBottom = tblTop
            if (firstPg) {
                val skFrame = RectF(tblL + TBL_W + 16f, tblTop, PAGE_W - MARGIN, tblTop + 300f)
                c.drawText("SƠ HOẠ VỊ TRÍ MỐC CẮM", skFrame.centerX(), tblTop - 6f,
                    paint(9.5f, bold = true, align = Paint.Align.CENTER))
                if (sketch != null) sketch(c, skFrame)
                else c.drawRect(skFrame, Paint().apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.2f
                })
                skBottom = skFrame.bottom
            }

            // ── Tiêu đề cột ──
            c.drawText("Đỉnh thửa", (colX[0] + colX[1]) / 2f, y + 12f, cellB)
            c.drawText("X (m)",     (colX[1] + colX[2]) / 2f, y + 12f, cellB)
            c.drawText("Y (m)",     (colX[2] + colX[3]) / 2f, y + 12f, cellB)
            c.drawText("K/C (m)",   (colX[3] + colX[4]) / 2f, y + 12f, cellB)
            y += rowH

            // ── Các dòng vừa với trang (chừa chỗ cho mục Ghi chú) ──
            val yLimit = PAGE_H - MARGIN - 60f
            val rowTop = y
            while (rowIdx < b.moc.size && y + rowH <= yLimit) {
                val m = b.moc[rowIdx]
                c.drawText(m.dinh, (colX[0] + colX[1]) / 2f, y + 12f, cell)
                // Locale.US → luôn dấu CHẤM thập phân như mẫu biên bản
                c.drawText("%.2f".format(java.util.Locale.US, m.x), (colX[1] + colX[2]) / 2f, y + 12f, cell)
                c.drawText("%.2f".format(java.util.Locale.US, m.y), (colX[2] + colX[3]) / 2f, y + 12f, cell)
                // K/C là khoảng cách GIỮA hai đỉnh → đặt so le, nằm giữa 2 dòng
                m.khoangCach?.let {
                    c.drawText("%.2f".format(java.util.Locale.US, it), (colX[3] + colX[4]) / 2f, y + 21f, cell)
                }
                y += rowH
                rowIdx++
            }
            val tblBottom = y

            // ── Khung bảng ──
            // Đường DỌC: kẻ đủ 5 đường.
            colX.forEach { x -> c.drawLine(x, tblTop, x, tblBottom, line) }
            // Đường NGANG: NGẮT TẠI CỘT K/C (chỉ kẻ tới colX[3]) vì số liệu K/C
            // nằm so le giữa hai dòng — kẻ hết sẽ cắt ngang chữ.
            var ry = tblTop
            while (ry <= tblBottom + 0.1f) { c.drawLine(colX[0], ry, colX[3], ry, line); ry += rowH }
            // Riêng cột K/C chỉ đóng khung trên/dưới
            c.drawLine(colX[3], tblTop, colX[4], tblTop, line)
            c.drawLine(colX[3], rowTop, colX[4], rowTop, line)
            c.drawLine(colX[3], tblBottom, colX[4], tblBottom, line)

            // ── Mục 4: Ghi chú — cơ động, luôn nằm dưới cùng phần đã vẽ ──
            if (rowIdx >= b.moc.size) {
                val y4 = maxOf(tblBottom, skBottom) + 26f
                if (y4 < PAGE_H - MARGIN) {
                    c.drawText("4. Ghi chú:", MARGIN, y4, paint(11f, bold = true))
                    // 3 dòng kẻ trống để viết tay
                    var ly = y4 + 20f
                    repeat(3) {
                        if (ly < PAGE_H - MARGIN) {
                            c.drawLine(MARGIN, ly, PAGE_W - MARGIN, ly, Paint().apply {
                                strokeWidth = 0.6f; color = Color.rgb(120, 120, 120)
                            })
                            ly += 18f
                        }
                    }
                }
            }

            doc.finishPage(page)
            firstPg = false
            pageNo++
            if (rowIdx >= b.moc.size) break
        }
    }

    // ══════════════════════════════════════════════════════════
    // Tiện ích
    // ══════════════════════════════════════════════════════════

    private fun paint(
        size  : Float,
        bold  : Boolean = false,
        italic: Boolean = false,
        align : Paint.Align = Paint.Align.LEFT
    ) = Paint().apply {
        isAntiAlias = true
        color       = Color.BLACK
        textSize    = size
        textAlign   = align
        typeface    = Typeface.create(
            Typeface.SERIF,
            when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold           -> Typeface.BOLD
                italic         -> Typeface.ITALIC
                else           -> Typeface.NORMAL
            }
        )
    }

    /**
     * Xuống dòng tự động theo bề rộng — PdfDocument không tự wrap.
     * @return toạ độ y sau đoạn văn
     */
    private fun wrap(
        c: Canvas, text: String, x: Float, yStart: Float,
        maxW: Float, p: Paint, lineH: Float
    ): Float {
        var y = yStart
        val words = text.split(' ')
        val sb = StringBuilder()
        words.forEach { w ->
            val test = if (sb.isEmpty()) w else "$sb $w"
            if (p.measureText(test) > maxW) {
                c.drawText(sb.toString(), x, y, p); y += lineH
                sb.clear(); sb.append(w)
            } else {
                sb.clear(); sb.append(test)
            }
        }
        if (sb.isNotEmpty()) { c.drawText(sb.toString(), x, y, p); y += lineH }
        return y
    }
}
