package com.hien.rtkmultidevice.core.cad

import java.util.Locale

/**
 * Xuất bản vẽ CAD ra DXF (R12 / AC1009) — định dạng ASCII cổ điển, đọc được ở hầu hết
 * phần mềm (AutoCAD, MicroStation, QGIS, Civil3D). Không cần handle/subclass.
 *
 * Quy ước toạ độ: DXF X = Easting (E), Y = Northing (N), Z = 0. (VN-2000, mét)
 * POINT -> POINT; LINE -> POLYLINE hở; POLYGON -> POLYLINE kín; nhãn -> TEXT.
 */
object CadDxfExporter {

    private fun f(v: Double) = String.format(Locale.US, "%.4f", v)
    private fun safeLayer(name: String) =
        name.trim().ifBlank { "0" }.replace(' ', '_').replace(Regex("[^A-Za-z0-9_\\-]"), "_")

    fun export(drawing: CadDrawing, textHeight: Double = 1.0): String = buildString {
        // ── HEADER ─────────────────────────────
        line("0", "SECTION"); line("2", "HEADER")
        line("9", "\$ACADVER"); line("1", "AC1009")
        line("0", "ENDSEC")

        // ── TABLES: LAYER ──────────────────────
        line("0", "SECTION"); line("2", "TABLES")
        line("0", "TABLE"); line("2", "LAYER"); line("70", drawing.layers.size.toString())
        drawing.layers.forEach { lyr ->
            line("0", "LAYER")
            line("2", safeLayer(lyr.name))
            line("70", "0")
            line("62", lyr.colorAci.toString())
            line("6", "CONTINUOUS")
        }
        line("0", "ENDTAB"); line("0", "ENDSEC")

        // ── ENTITIES ───────────────────────────
        line("0", "SECTION"); line("2", "ENTITIES")
        drawing.entities.forEach { ent ->
            val ly = safeLayer(ent.layer)
            when (ent.type) {
                CadType.POINT -> ent.vertices.firstOrNull()?.let { v ->
                    line("0", "POINT"); line("8", ly)
                    line("10", f(v.e)); line("20", f(v.n)); line("30", "0.0")
                }
                CadType.LINE, CadType.POLYGON -> if (ent.vertices.size >= 2) {
                    line("0", "POLYLINE"); line("8", ly); line("66", "1")
                    line("70", if (ent.type == CadType.POLYGON) "1" else "0")   // 1 = kín
                    line("10", "0.0"); line("20", "0.0"); line("30", "0.0")
                    ent.vertices.forEach { v ->
                        line("0", "VERTEX"); line("8", ly)
                        line("10", f(v.e)); line("20", f(v.n)); line("30", "0.0")
                    }
                    line("0", "SEQEND"); line("8", ly)
                }
            }
            // Nhãn (TEXT) tại đỉnh đầu / tâm
            if (ent.label.isNotBlank() && ent.vertices.isNotEmpty()) {
                val c = centroid(ent.vertices)
                line("0", "TEXT"); line("8", ly)
                line("10", f(c.e)); line("20", f(c.n)); line("30", "0.0")
                line("40", f(textHeight))
                line("1", ent.label)
            }
        }
        line("0", "ENDSEC")
        line("0", "EOF")
    }

    private fun centroid(vs: List<CadVertex>): CadVertex {
        val n = vs.sumOf { it.n } / vs.size
        val e = vs.sumOf { it.e } / vs.size
        return CadVertex(n, e)
    }

    private fun StringBuilder.line(code: String, value: String) {
        append(code).append('\n').append(value).append('\n')
    }
}
