package com.hien.rtkmultidevice.export

import com.hien.rtkmultidevice.domain.model.SurveyPoint
import com.hien.rtkmultidevice.domain.model.TraversePoint
import java.util.Locale

/**
 * PointFileFormat — Định nghĩa định dạng file điểm đo linh hoạt.
 *
 * Cho phép người dùng chọn:
 *   • Thứ tự trường: P,N,E,H,Code,Comment / P,E,N,H,Code,Comment / ...
 *   • Dấu phân cách: phẩy, tab, chấm phẩy, khoảng trắng
 *   • Phần mở rộng: .csv / .txt
 *   • Có/không dòng header
 *
 * Dùng chung cho cả Import và Export (PointFileCodec).
 */

/** Các trường dữ liệu trong file điểm */
enum class PointField(val header: String, val shortLabel: String) {
    POINT_ID ("PointId",  "P"),
    NORTHING ("Northing", "N"),
    EASTING  ("Easting",  "E"),
    ELEVATION("Height",   "H"),
    CODE     ("Code",     "Code"),
    COMMENT  ("Comment",  "Comment");
}

/** Dấu phân cách trường */
enum class FieldDelimiter(val char: Char, val label: String) {
    COMMA    (',',  "Phẩy (,)"),
    TAB      ('\t', "Tab"),
    SEMICOLON(';',  "Chấm phẩy (;)"),
    SPACE    (' ',  "Khoảng trắng");
}

data class PointFileFormat(
    val fields        : List<PointField>,
    val delimiter     : FieldDelimiter = FieldDelimiter.COMMA,
    /** "csv" hoặc "txt" */
    val extension     : String = "csv",
    val includeHeader : Boolean = true
) {
    /** Nhãn ngắn hiển thị trong UI: "P, N, E, H, Code, Comment" */
    val orderLabel: String get() = fields.joinToString(", ") { it.shortLabel }

    val mimeType: String get() = if (extension == "csv") "text/csv" else "text/plain"

    companion object {
        /** Các preset thứ tự trường phổ biến trong trắc địa */
        val FIELD_ORDER_PRESETS: List<List<PointField>> = listOf(
            listOf(PointField.POINT_ID, PointField.NORTHING, PointField.EASTING,  PointField.ELEVATION, PointField.CODE, PointField.COMMENT),
            listOf(PointField.POINT_ID, PointField.EASTING,  PointField.NORTHING, PointField.ELEVATION, PointField.CODE, PointField.COMMENT),
            listOf(PointField.POINT_ID, PointField.NORTHING, PointField.EASTING,  PointField.ELEVATION),
            listOf(PointField.POINT_ID, PointField.EASTING,  PointField.NORTHING, PointField.ELEVATION),
            listOf(PointField.POINT_ID, PointField.CODE, PointField.NORTHING, PointField.EASTING, PointField.ELEVATION, PointField.COMMENT)
        )

        val DEFAULT = PointFileFormat(FIELD_ORDER_PRESETS[0])
    }
}

/**
 * PointFileCodec — Ghi/đọc file điểm theo [PointFileFormat].
 */
object PointFileCodec {

    // ──────────────────────────────────────────────────────────
    // Export
    // ──────────────────────────────────────────────────────────

    fun build(points: List<SurveyPoint>, fmt: PointFileFormat): String {
        val d  = fmt.delimiter.char.toString()
        val sb = StringBuilder()
        if (fmt.includeHeader) {
            sb.appendLine(fmt.fields.joinToString(d) { it.header })
        }
        points.forEach { p ->
            sb.appendLine(fmt.fields.joinToString(d) { f -> escape(fieldValue(p, f), fmt.delimiter.char) })
        }
        return sb.toString()
    }

    /** Xuất điểm tuyến đo (TraversePoint) — dùng cho export tuyến ra .csv/.txt */
    fun buildTraverse(points: List<TraversePoint>, fmt: PointFileFormat): String {
        val d  = fmt.delimiter.char.toString()
        val sb = StringBuilder()
        if (fmt.includeHeader) {
            sb.appendLine(fmt.fields.joinToString(d) { it.header })
        }
        points.forEach { p ->
            sb.appendLine(fmt.fields.joinToString(d) { f -> escape(traverseFieldValue(p, f), fmt.delimiter.char) })
        }
        return sb.toString()
    }

    private fun traverseFieldValue(p: TraversePoint, f: PointField): String = when (f) {
        PointField.POINT_ID  -> p.pointCode
        PointField.NORTHING  -> "%.3f".format(Locale.US, p.northing)
        PointField.EASTING   -> "%.3f".format(Locale.US, p.easting)
        PointField.ELEVATION -> "%.3f".format(Locale.US, p.altitude)
        PointField.CODE      -> p.note
        PointField.COMMENT   -> if (p.fixQuality > 0) "${p.fixLabel} HDOP=%.1f".format(Locale.US, p.hdop) else ""
    }

    private fun fieldValue(p: SurveyPoint, f: PointField): String = when (f) {
        PointField.POINT_ID  -> p.pointCode
        PointField.NORTHING  -> "%.3f".format(Locale.US, p.northing)
        PointField.EASTING   -> "%.3f".format(Locale.US, p.easting)
        PointField.ELEVATION -> "%.3f".format(Locale.US, p.altitude)
        // Model hiện chỉ có 1 trường ghi chú (note):
        //   CODE    → ghi chú người dùng
        //   COMMENT → thông tin chất lượng đo (Fix/HDOP)
        PointField.CODE      -> p.note
        PointField.COMMENT   -> if (p.fixQuality > 0) "${p.fixLabel} HDOP=%.1f".format(Locale.US, p.hdop) else ""
    }

    private fun escape(v: String, delim: Char): String {
        val cleaned = v.replace("\n", " ").replace("\r", " ")
        return if (cleaned.contains(delim) || cleaned.contains('"'))
            "\"${cleaned.replace("\"", "\"\"")}\"" else cleaned
    }

    // ──────────────────────────────────────────────────────────
    // Import
    // ──────────────────────────────────────────────────────────

    data class ParsedPoint(
        val pointId   : String,
        val northing  : Double,
        val easting   : Double,
        val elevation : Double,
        val code      : String,
        val comment   : String
    )

    /**
     * Đọc danh sách điểm từ các dòng file theo định dạng [fmt].
     * Tự bỏ qua: dòng trống, dòng comment (#), dòng header, dòng lỗi.
     */
    fun parse(lines: List<String>, fmt: PointFileFormat): List<ParsedPoint> {
        val out = mutableListOf<ParsedPoint>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val parts = splitLine(line, fmt.delimiter.char)
            if (parts.size < 3) return@forEach

            var id = ""; var code = ""; var cmt = ""; var h = 0.0
            var n: Double? = null; var e: Double? = null

            fmt.fields.forEachIndexed { i, f ->
                val v = parts.getOrNull(i)?.trim()?.trim('"') ?: return@forEachIndexed
                when (f) {
                    PointField.POINT_ID  -> id = v
                    PointField.NORTHING  -> n = v.parseNumber()
                    PointField.EASTING   -> e = v.parseNumber()
                    PointField.ELEVATION -> h = v.parseNumber() ?: 0.0
                    PointField.CODE      -> code = v
                    PointField.COMMENT   -> cmt = v
                }
            }

            val nn = n ?: return@forEach   // header / dòng lỗi → parse số thất bại
            val ee = e ?: return@forEach
            if (id.isBlank()) return@forEach
            // N/E quá nhỏ = không phải toạ độ phẳng (có thể là độ thập phân WGS-84)
            if (nn < 100.0 || ee < 100.0) return@forEach

            out.add(ParsedPoint(id, nn, ee, h, code, cmt))
        }
        return out
    }

    /** Parse số: chấp nhận "2 325 478.123" và "2325478,123" (phẩy thập phân VN) */
    private fun String.parseNumber(): Double? {
        val s = replace(" ", "")
        // Nếu có cả "," và "." → "," là phân cách nghìn; nếu chỉ có "," → thập phân
        val normalized = when {
            s.contains(',') && s.contains('.') -> s.replace(",", "")
            s.contains(',')                    -> s.replace(',', '.')
            else                               -> s
        }
        return normalized.toDoubleOrNull()
    }

    /** Tách dòng — hỗ trợ giá trị trong dấu ngoặc kép chứa dấu phân cách */
    private fun splitLine(line: String, delim: Char): List<String> {
        if (!line.contains('"')) {
            return if (delim == ' ') line.split(Regex("\\s+")) else line.split(delim)
        }
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        line.forEach { c ->
            when {
                c == '"'                -> inQuote = !inQuote
                c == delim && !inQuote -> { parts.add(sb.toString()); sb.clear() }
                else                    -> sb.append(c)
            }
        }
        parts.add(sb.toString())
        return parts
    }
}
