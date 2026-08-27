package com.hien.rtkmultidevice.report

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringWriter

/**
 * BienBanXml — Ghi/đọc BIÊN BẢN BÀN GIAO MỐC GIỚI dưới dạng XML.
 *
 * Vì sao có bước XML:
 *   Nhiều thông tin KHÔNG có trong dữ liệu địa chính (số phát hành GCN, ngày cấp,
 *   địa chỉ chủ sử dụng, xưng hô ông/bà…). App xuất XML với phần đã biết được điền sẵn,
 *   người dùng mở bằng trình soạn thảo bất kỳ để bổ sung/kiểm tra, rồi mới xuất PDF.
 *
 * File XML có thụt lề và chú thích tiếng Việt để dễ sửa bằng tay.
 */
object BienBanXml {

    private const val NS = ""

    /** Lý do đọc XML thất bại gần nhất — để báo lỗi cụ thể thay vì "sai định dạng". */
    @Volatile var lastError: String? = null
        private set

    // ══════════════════════════════════════════════════════════
    // GHI
    // ══════════════════════════════════════════════════════════

    fun toXml(b: BienBan): String {
        val w = StringWriter()
        val s = Xml.newSerializer()
        s.setOutput(w)
        s.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        s.startDocument("UTF-8", true)

        s.comment(
            "\n  BIÊN BẢN BÀN GIAO MỐC GIỚI — sửa nội dung trong file này rồi xuất PDF." +
            "\n  Các ô để trống là thông tin app không có sẵn, cần điền tay.\n"
        )
        s.startTag(NS, "BienBanBanGiaoMocGioi")

        tagGroup(s, "ThoiGian") {
            text(s, "Ngay", b.ngay.toString())
            text(s, "Thang", b.thang.toString())
            text(s, "Nam", b.nam.toString())
        }

        tagGroup(s, "ThuaDat") {
            text(s, "SoThua", b.soThua)
            text(s, "SoTo", b.soTo)
            text(s, "Xa", b.xa)
            text(s, "Huyen", b.huyen)
            text(s, "Tinh", b.tinh)
            text(s, "DienTich", b.dienTich)
            text(s, "LoaiDat", b.loaiDat)
        }

        tagGroup(s, "ChuSuDung") {
            text(s, "HoTen", b.chuSuDung)
            text(s, "XungHo", b.xungHo)          // ông / bà
            text(s, "DiaChi", b.diaChiChu)
        }

        tagGroup(s, "GiayChungNhan") {
            text(s, "SoPhatHanh", b.soPhatHanhGcn)
            text(s, "NoiCap", b.noiCapGcn)
            text(s, "NgayCap", b.ngayCapGcn)
        }

        tagGroup(s, "DonViDoDac") {
            text(s, "Ten", b.donViTen)
            text(s, "DaiDien", b.donViDaiDien)
            text(s, "ChucVu", b.donViChucVu)
            text(s, "DiaChi", b.donViDiaChi)
            text(s, "VPDD", b.donViVpdd)
        }

        tagGroup(s, "HeToaDo") {
            text(s, "KinhTuyenTruc", b.kinhTuyenTruc)
            text(s, "MuiChieu", b.muiChieu)
        }

        s.startTag(NS, "BangKeMocGioi")
        b.moc.forEach { m ->
            s.startTag(NS, "Moc")
            s.attribute(NS, "dinh", m.dinh)
            // LUÔN dùng Locale.US: máy đặt tiếng Việt sẽ ghi dấu PHẨY thập phân
            // (1173361,78) khiến lúc đọc lại toDoubleOrNull() trả null → toạ độ = 0.00
            text(s, "X", "%.2f".format(java.util.Locale.US, m.x))
            text(s, "Y", "%.2f".format(java.util.Locale.US, m.y))
            text(s, "KhoangCach",
                m.khoangCach?.let { "%.2f".format(java.util.Locale.US, it) } ?: "")
            s.endTag(NS, "Moc")
        }
        s.endTag(NS, "BangKeMocGioi")

        s.endTag(NS, "BienBanBanGiaoMocGioi")
        s.endDocument()
        return w.toString()
    }

    fun save(file: File, b: BienBan) {
        file.parentFile?.mkdirs()
        file.writeText(toXml(b), Charsets.UTF_8)
    }

    // ══════════════════════════════════════════════════════════
    // ĐỌC
    // ══════════════════════════════════════════════════════════

    /**
     * Đọc lại XML đã sửa. Dùng cách gom tất cả thẻ lá vào map theo
     * "ThẻCha/ThẻCon" nên không phụ thuộc thứ tự người dùng sắp xếp.
     */
    /** Các thẻ CHỨA thẻ con — tuyệt đối không được gọi nextText() lên chúng. */
    private val CONTAINERS = setOf(
        "BienBanBanGiaoMocGioi",          // thẻ gốc
        "ThoiGian", "ThuaDat", "ChuSuDung", "GiayChungNhan",
        "DonViDoDac", "HeToaDo", "BangKeMocGioi"
    )

    fun load(file: File): BienBan? = loadText(file.readText(Charsets.UTF_8))

    /**
     * Đọc XML từ CHUỖI. Cần bản này vì file người dùng sửa nằm trong Downloads,
     * lấy về qua MediaStore (ReportStorage.readText) chứ không phải java.io.File.
     */
    fun loadText(xml: String): BienBan? = runCatching {
        val p = Xml.newPullParser()
        p.setInput(java.io.StringReader(xml))

        val map  = HashMap<String, String>()
        val mocs = ArrayList<BienBan.MocGioi>()
        var parent = ""
        var curMocDinh: String? = null
        val curMoc = HashMap<String, String>()

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when {
                    // Thẻ gốc và các nhóm: chỉ ghi nhớ tên, KHÔNG đọc text
                    p.name in CONTAINERS -> parent = p.name
                    p.name == "Moc" -> {
                        curMocDinh = p.getAttributeValue(NS, "dinh") ?: "${mocs.size + 1}"
                        curMoc.clear()
                    }
                    else -> {
                        // Thẻ lá — bọc runCatching để một thẻ lạ không làm hỏng cả file
                        val name = p.name
                        val v = runCatching { p.nextText().trim() }.getOrDefault("")
                        if (curMocDinh != null) curMoc[name] = v else map["$parent/$name"] = v
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "Moc") {
                    // Chấp nhận cả dấu phẩy thập phân do người dùng sửa tay
                    fun num(s: String?) = s?.trim()?.replace(',', '.')?.toDoubleOrNull()
                    mocs += BienBan.MocGioi(
                        dinh       = curMocDinh ?: "${mocs.size + 1}",
                        x          = num(curMoc["X"]) ?: 0.0,
                        y          = num(curMoc["Y"]) ?: 0.0,
                        khoangCach = num(curMoc["KhoangCach"])
                    )
                    curMocDinh = null
                }
            }
            ev = p.next()
        }

        fun g(k: String) = map[k].orEmpty()
        BienBan(
            ngay  = g("ThoiGian/Ngay").toIntOrNull() ?: 1,
            thang = g("ThoiGian/Thang").toIntOrNull() ?: 1,
            nam   = g("ThoiGian/Nam").toIntOrNull() ?: 2026,
            soThua = g("ThuaDat/SoThua"), soTo = g("ThuaDat/SoTo"),
            xa = g("ThuaDat/Xa"), huyen = g("ThuaDat/Huyen"), tinh = g("ThuaDat/Tinh"),
            dienTich = g("ThuaDat/DienTich"), loaiDat = g("ThuaDat/LoaiDat"),
            chuSuDung = g("ChuSuDung/HoTen"), xungHo = g("ChuSuDung/XungHo").ifBlank { "ông" },
            diaChiChu = g("ChuSuDung/DiaChi"),
            soPhatHanhGcn = g("GiayChungNhan/SoPhatHanh"),
            noiCapGcn = g("GiayChungNhan/NoiCap"), ngayCapGcn = g("GiayChungNhan/NgayCap"),
            donViTen = g("DonViDoDac/Ten"), donViDaiDien = g("DonViDoDac/DaiDien"),
            donViChucVu = g("DonViDoDac/ChucVu"), donViDiaChi = g("DonViDoDac/DiaChi"),
            donViVpdd = g("DonViDoDac/VPDD"),
            kinhTuyenTruc = g("HeToaDo/KinhTuyenTruc").ifBlank { "107°45'" },
            muiChieu = g("HeToaDo/MuiChieu").ifBlank { "3°" },
            moc = mocs
        )
    }.onSuccess { lastError = null }
     .onFailure { lastError = "${it::class.simpleName}: ${it.message}" }
     .getOrNull()

    // ── helper ───────────────────────────────────────────────
    private inline fun tagGroup(
        s: org.xmlpull.v1.XmlSerializer, name: String, body: () -> Unit
    ) {
        s.startTag(NS, name); body(); s.endTag(NS, name)
    }

    private fun text(s: org.xmlpull.v1.XmlSerializer, tag: String, value: String) {
        s.startTag(NS, tag); s.text(value); s.endTag(NS, tag)
    }
}
