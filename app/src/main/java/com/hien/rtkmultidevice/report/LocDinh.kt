package com.hien.rtkmultidevice.report

import kotlin.math.abs
import kotlin.math.hypot

/**
 * LocDinh — Lọc bớt đỉnh thừa của đường bao thửa TRƯỚC KHI trình bày.
 *
 * Vì sao cần: bản đồ địa chính số hoá từ nhiều nguồn thường có những đỉnh nằm
 * sát nhau vài centimét, hoặc những đỉnh nằm gần như đúng trên đường thẳng nối
 * hai đỉnh kề. Chúng không sai về mặt số liệu, nhưng đưa lên sơ hoạ thì số hiệu
 * đỉnh chồng lên nhau thành một cục đen, và đưa vào bảng toạ độ thì bảng dài
 * thêm mấy dòng không nói lên điều gì.
 *
 * HAI LUẬT, áp xen kẽ cho tới khi không còn gì để bỏ:
 *
 *   1. GẦN NHAU  — hai đỉnh liên tiếp cách nhau < [HAN_SAI_GAN_M] (0,20 m):
 *      bỏ đỉnh SAU, giữ đỉnh trước. Cạnh mới nối thẳng từ đỉnh trước sang đỉnh
 *      kế tiếp nên đoạn vừa bỏ được CỘNG DỒN vào cạnh đó — tổng chiều dài chu
 *      vi gần như không đổi, không sinh ra khoảng hụt.
 *
 *   2. THẲNG HÀNG — ba đỉnh liên tiếp A, B, C mà khoảng cách từ B tới đường
 *      thẳng AC < [HAN_SAI_THANG_M] (0,02 m): bỏ B.
 *      ⚠ Luật này ĐỘC LẬP với luật 1: B vẫn bị bỏ dù nó cách A và C xa hơn
 *      0,20 m rất nhiều. Một đỉnh lệch 2 cm trên cạnh dài 30 m thì vẽ ra không
 *      ai phân biệt được, mà vẫn tốn một số hiệu đỉnh và một dòng trong bảng.
 *
 * Phải áp XEN KẼ và LẶP: bỏ một đỉnh theo luật 1 có thể làm ba đỉnh còn lại trở
 * nên thẳng hàng, và ngược lại. Chạy một lượt mỗi luật là bỏ sót.
 *
 * ⚠ KHÔNG BAO GIỜ để đường bao còn dưới 3 đỉnh — thà giữ đỉnh thừa còn hơn trả
 *   về một hình không vẽ được.
 *
 * ⚠ Đây là lọc để TRÌNH BÀY. Diện tích thửa vẫn lấy từ hồ sơ địa chính, không
 *   tính lại từ danh sách đã lọc.
 *
 * Toạ độ vào/ra: (Northing, Easting) tính bằng MÉT — VN-2000.
 */
object LocDinh {

    /** Hai đỉnh liên tiếp gần hơn ngần này (m) thì gộp làm một. */
    const val HAN_SAI_GAN_M = 0.20

    /** Đỉnh giữa lệch khỏi đường thẳng nối hai đỉnh kề ít hơn ngần này (m) thì bỏ. */
    const val HAN_SAI_THANG_M = 0.02

    private const val TOI_THIEU = 3
    private const val LAP_TOI_DA = 50

    /**
     * @param dinh   danh sách đỉnh (N, E), theo thứ tự đi vòng.
     * @param khepKin true = đường bao khép kín (thửa đất): đỉnh cuối nối về đỉnh đầu,
     *                nên cặp (cuối, đầu) và bộ ba (áp chót, cuối, đầu) cũng được xét.
     * @return danh sách đã lọc, KHÔNG lặp lại đỉnh đầu ở cuối.
     */
    fun loc(
        dinh      : List<Pair<Double, Double>>,
        khepKin   : Boolean = true,
        hanSaiGan : Double = HAN_SAI_GAN_M,
        hanSaiThang: Double = HAN_SAI_THANG_M
    ): List<Pair<Double, Double>> {
        if (dinh.size < TOI_THIEU) return dinh

        // Bỏ đỉnh khép kín lặp lại ở cuối nếu nguồn có sẵn — xử lý vòng bằng
        // chỉ số modulo, giữ thêm một bản sao chỉ tổ đếm nhầm.
        var p = dinh.toMutableList()
        if (khepKin && p.size > 1 && trung(p.first(), p.last())) p.removeAt(p.size - 1)
        if (p.size < TOI_THIEU) return p

        var vong = 0
        while (vong++ < LAP_TOI_DA) {
            val truoc = p.size
            p = boGanNhau(p, khepKin, hanSaiGan)
            p = boThangHang(p, khepKin, hanSaiThang)
            if (p.size == truoc) break          // không bỏ được gì nữa
        }
        return p
    }

    /** Số đỉnh đã bị bỏ — để hiện cho người dùng biết bảng ngắn đi vì sao. */
    fun soDinhDaBo(goc: List<Pair<Double, Double>>, daLoc: List<Pair<Double, Double>>): Int {
        val g = if (goc.size > 1 && trung(goc.first(), goc.last())) goc.size - 1 else goc.size
        return (g - daLoc.size).coerceAtLeast(0)
    }

    // ── Luật 1: hai đỉnh liên tiếp quá gần ──────────────────────────────
    private fun boGanNhau(
        p: MutableList<Pair<Double, Double>>, khepKin: Boolean, han: Double
    ): MutableList<Pair<Double, Double>> {
        if (p.size <= TOI_THIEU) return p
        val ra = ArrayList<Pair<Double, Double>>(p.size)
        var i = 0
        while (i < p.size) {
            val cur = p[i]
            // Giữ đỉnh này, rồi nuốt mọi đỉnh ngay sau nó mà còn quá gần
            ra += cur
            var j = i + 1
            while (j < p.size && khoang(cur, p[j]) < han) j++
            i = j
        }
        // Vòng khép kín: đỉnh cuối có thể sát đỉnh đầu → bỏ đỉnh CUỐI,
        // vì đỉnh 1 là mốc đánh số, đổi nó là lệch cả bảng toạ độ.
        if (khepKin && ra.size > TOI_THIEU && khoang(ra.last(), ra.first()) < han) {
            ra.removeAt(ra.size - 1)
        }
        return if (ra.size >= TOI_THIEU) ra else p
    }

    // ── Luật 2: ba đỉnh thẳng hàng ──────────────────────────────────────
    private fun boThangHang(
        p: MutableList<Pair<Double, Double>>, khepKin: Boolean, han: Double
    ): MutableList<Pair<Double, Double>> {
        if (p.size <= TOI_THIEU) return p
        val bo = BooleanArray(p.size)
        val n = p.size
        val dau = if (khepKin) 0 else 1
        val cuoi = if (khepKin) n - 1 else n - 2

        for (i in dau..cuoi) {
            // Đỉnh kề PHẢI là đỉnh còn sống — nếu không, bỏ liền hai đỉnh cạnh
            // nhau sẽ tính lệch so với đường thẳng thật sau khi lọc.
            val t = lui(p, bo, i, khepKin) ?: continue
            val s = tien(p, bo, i, khepKin) ?: continue
            if (bo[i]) continue
            if (n - bo.count { it } <= TOI_THIEU) break
            if (lechDuong(p[i], p[t], p[s]) < han) bo[i] = true
        }
        val ra = ArrayList<Pair<Double, Double>>(n)
        p.forEachIndexed { i, v -> if (!bo[i]) ra += v }
        return if (ra.size >= TOI_THIEU) ra else p
    }

    private fun lui(
        p: List<Pair<Double, Double>>, bo: BooleanArray, i: Int, khepKin: Boolean
    ): Int? {
        var k = i - 1
        var buoc = 0
        while (buoc++ < p.size) {
            if (k < 0) { if (!khepKin) return null; k = p.size - 1 }
            if (!bo[k] && k != i) return k
            k--
        }
        return null
    }

    private fun tien(
        p: List<Pair<Double, Double>>, bo: BooleanArray, i: Int, khepKin: Boolean
    ): Int? {
        var k = i + 1
        var buoc = 0
        while (buoc++ < p.size) {
            if (k > p.size - 1) { if (!khepKin) return null; k = 0 }
            if (!bo[k] && k != i) return k
            k++
        }
        return null
    }

    /** Khoảng cách từ B tới ĐƯỜNG THẲNG qua A và C (không phải tới đoạn AC). */
    fun lechDuong(
        b: Pair<Double, Double>, a: Pair<Double, Double>, c: Pair<Double, Double>
    ): Double {
        val dx = c.first - a.first
        val dy = c.second - a.second
        val d = hypot(dx, dy)
        // A trùng C: "đường thẳng" không xác định → lấy khoảng cách tới A
        if (d < 1e-9) return khoang(b, a)
        val cheo = (b.first - a.first) * dy - (b.second - a.second) * dx
        return abs(cheo) / d
    }

    private fun khoang(a: Pair<Double, Double>, b: Pair<Double, Double>) =
        hypot(b.first - a.first, b.second - a.second)

    private fun trung(a: Pair<Double, Double>, b: Pair<Double, Double>) =
        abs(a.first - b.first) < 1e-7 && abs(a.second - b.second) < 1e-7
}
