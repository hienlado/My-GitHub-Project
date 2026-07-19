package com.hien.rtkmultidevice.ui.screens.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.*

/**
 * VectorLayerImporter — Đọc file vector và chuyển về WGS-84 để hiển thị trên bản đồ.
 *
 * Hỗ trợ: DXF (ASCII), SHP (ESRI), CSV/TXT
 *
 * Tính năng:
 *   • VectorFeature lưu rawPoints (toạ độ gốc) → hiển thị VN-2000 + stakeout
 *   • applyOffset(ΔN, ΔE) hiệu chỉnh lệch toạ độ
 *   • applyRezone(newCM) đổi kinh tuyến trục khi import sai múi
 *   • hintCentralMeridian từ cài đặt app → import chính xác ngay lần đầu
 */
object VectorLayerImporter {

    // ══════════════════════════════════════════════════════════
    // Constants
    // ══════════════════════════════════════════════════════════

    /** Danh sách kinh tuyến trục múi 3° VN-2000 Việt Nam */
    val VN2000_CENTRAL_MERIDIANS = listOf(
        102.0, 103.0, 104.0, 105.0, 106.0, 107.0, 107.75, 108.0, 108.5, 109.0
    )

    /** Kinh tuyến trục mặc định khi không phát hiện được: 107°45' = 107.75° */
    const val DEFAULT_CM = 107.75

    // ══════════════════════════════════════════════════════════
    // Enums
    // ══════════════════════════════════════════════════════════

    enum class CoordSystem {
        WGS84, VN2000_3DEG, VN2000_6DEG, UNKNOWN_PROJECTED
    }

    enum class FeatureType { POINT, POLYLINE, POLYGON }

    // ══════════════════════════════════════════════════════════
    // VectorFeature
    // ══════════════════════════════════════════════════════════

    data class VectorFeature(
        val id              : Int,
        val type            : FeatureType,
        /** Toạ độ WGS-84 — dùng để vẽ trên bản đồ */
        val geoPoints       : List<GeoPoint>,
        /** Toạ độ gốc từ file: (x=easting/lon, y=northing/lat) */
        val rawPoints       : List<Pair<Double, Double>>,
        val coordSystem     : CoordSystem,
        /** Kinh tuyến trục đã dùng khi chiếu (0.0 nếu WGS84) */
        val centralMeridian : Double = 0.0,
        val dxfLayer        : String = "",
        val label           : String = "",
        val soThua          : String = "",
        val dienTich        : String = "",
        val loaiDat         : String = "",
        val chuSuDung       : String = ""
    ) {
        val centroid: GeoPoint? get() {
            if (geoPoints.isEmpty()) return null
            return GeoPoint(
                geoPoints.map { it.latitude  }.average(),
                geoPoints.map { it.longitude }.average()
            )
        }
        val rawCentroid: Pair<Double, Double>? get() {
            if (rawPoints.isEmpty()) return null
            return Pair(rawPoints.map { it.first }.average(), rawPoints.map { it.second }.average())
        }
        val isVn2000 get() = coordSystem == CoordSystem.VN2000_3DEG || coordSystem == CoordSystem.VN2000_6DEG
    }

    // ══════════════════════════════════════════════════════════
    // VectorLayer
    // ══════════════════════════════════════════════════════════

    data class VectorLayer(
        val name        : String,
        val features    : List<VectorFeature>,
        val coordSystem : CoordSystem,
        val detectedCm  : Double = 0.0,
        val offsetN     : Double = 0.0,
        val offsetE     : Double = 0.0
    ) {
        val points   : List<GeoPoint>       get() = features.filter { it.type == FeatureType.POINT    }.flatMap { it.geoPoints }
        val polylines: List<List<GeoPoint>> get() = features.filter { it.type == FeatureType.POLYLINE }.map { it.geoPoints }
        val polygons : List<List<GeoPoint>> get() = features.filter { it.type == FeatureType.POLYGON  }.map { it.geoPoints }
        val isEmpty      get() = features.isEmpty()
        val featureCount get() = features.size

        /**
         * Tạo layer mới với offset cộng thêm.
         * VN-2000: rawY(northing)+=ΔN, rawX(easting)+=ΔE → tính lại GeoPoints
         * WGS-84:  dịch xấp xỉ (1m ≈ 1/111000°)
         */
        fun applyOffset(deltaN: Double, deltaE: Double): VectorLayer {
            val newFeatures = features.map { f ->
                when {
                    f.isVn2000 -> {
                        val shifted = f.rawPoints.map { (x, y) -> Pair(x + deltaE, y + deltaN) }
                        val newGeo  = shifted.mapNotNull { (x, y) -> inverseVn2000(y, x, f.centralMeridian.takeIf { it > 0.0 }) }
                        f.copy(geoPoints = newGeo, rawPoints = shifted)
                    }
                    f.coordSystem == CoordSystem.WGS84 -> {
                        val avgLat = f.geoPoints.map { it.latitude }.average()
                        val dLat   = deltaN / 111_000.0
                        val dLon   = deltaE / (111_000.0 * cos(avgLat * PI / 180.0))
                        val newGeo = f.geoPoints.map { GeoPoint(it.latitude + dLat, it.longitude + dLon) }
                        val newRaw = f.rawPoints.map { (x, y) -> Pair(x + dLon, y + dLat) }
                        f.copy(geoPoints = newGeo, rawPoints = newRaw)
                    }
                    else -> f
                }
            }
            return copy(features = newFeatures, offsetN = this.offsetN + deltaN, offsetE = this.offsetE + deltaE)
        }
    }

    // ══════════════════════════════════════════════════════════
    // Static helpers
    // ══════════════════════════════════════════════════════════

    /** Tái chiếu toàn bộ layer sang kinh tuyến trục mới. */
    fun applyRezone(layer: VectorLayer, newCm: Double): VectorLayer {
        val newFeatures = layer.features.map { f ->
            if (!f.isVn2000) return@map f
            val newGeo = f.rawPoints.mapNotNull { (x, y) -> inverseVn2000(y, x, newCm) }
            f.copy(geoPoints = newGeo, centralMeridian = newCm)
        }
        return layer.copy(features = newFeatures, detectedCm = newCm)
    }

    /** Convenience wrapper cho `layer.applyOffset()`. */
    fun applyOffset(layer: VectorLayer, deltaN: Double, deltaE: Double): VectorLayer =
        layer.applyOffset(deltaN, deltaE)

    // ══════════════════════════════════════════════════════════
    // ImportResult
    // ══════════════════════════════════════════════════════════

    sealed class ImportResult {
        data class Success(val layer: VectorLayer, val fileName: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    // ══════════════════════════════════════════════════════════
    // Entry point
    // ══════════════════════════════════════════════════════════

    /**
     * @param hintCentralMeridian Kinh tuyến trục từ cài đặt app (0 = tự phát hiện).
     *        Nên truyền vào để import chính xác ngay, tránh phải dùng Căn chỉnh.
     */
    suspend fun importFromUri(
        context             : Context,
        uri                 : Uri,
        fileName            : String,
        hintCentralMeridian : Double = 0.0
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            // Lấy extension — ưu tiên từ fileName (đã query DISPLAY_NAME)
            // Fallback: MIME type từ ContentResolver nếu fileName không có extension
            val extFromName = fileName.substringAfterLast('.', "").lowercase()
            val ext = if (extFromName.isNotEmpty() && extFromName.length <= 4) {
                extFromName
            } else {
                val mime = context.contentResolver.getType(uri) ?: ""
                when {
                    mime.contains("dxf", ignoreCase = true)  -> "dxf"
                    mime.contains("shape", ignoreCase = true) ||
                        mime.contains("shp", ignoreCase = true) -> "shp"
                    mime.contains("csv", ignoreCase = true) ||
                        mime.contains("text", ignoreCase = true) -> "csv"
                    // Thử đọc 3 byte đầu để phát hiện SHP magic number
                    else -> "dxf"  // Mặc định thử DXF (ASCII text dễ detect)
                }
            }
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Error("Không đọc được file")
            val layer = when (ext) {
                "dxf"        -> parseDxf(stream, fileName, hintCentralMeridian)
                "shp"        -> parseShp(stream, fileName, hintCentralMeridian)
                "csv", "txt"       -> parseCsvPoints(stream, fileName, hintCentralMeridian)
                "geojson", "json"  -> parseGeoJson(stream, fileName, hintCentralMeridian)
                else         -> return@withContext ImportResult.Error(
                    "Định dạng .$ext chưa được hỗ trợ.\nHỗ trợ: .dxf, .shp, .csv, .txt, .geojson"
                )
            }
            if (layer.isEmpty) ImportResult.Error("File không chứa dữ liệu hình học hợp lệ")
            else ImportResult.Success(layer, fileName)
        } catch (e: Exception) {
            ImportResult.Error("Lỗi đọc file: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════
    // DXF Parser — Token-based (robust, handles variable attribute counts)
    // ══════════════════════════════════════════════════════════

    private fun parseDxf(stream: InputStream, name: String, hintCm: Double): VectorLayer {
        // ── 1. Đọc toàn bộ file, chuẩn hoá encoding/EOL/BOM ────────────────
        val bytes = stream.readBytes()
        val text  = runCatching { String(bytes, Charsets.UTF_8) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
            .removePrefix("﻿")          // UTF-8 BOM
            .replace("\r\n", "\n").replace("\r", "\n")
        val lines = text.split('\n')

        // ── 2. Tokenize: mỗi cặp (dòng code, dòng value) → Pair<Int,String> ─
        val tokens = ArrayList<Pair<Int, String>>(lines.size / 2 + 1)
        var j = 0
        while (j + 1 < lines.size) {
            val code = lines[j].trim().toIntOrNull()
            if (code != null) {
                tokens.add(code to lines[j + 1].trim())
                j += 2
            } else {
                j++     // bỏ qua dòng không phải số (comment, blank…)
            }
        }

        // ── 3. Scan entities ────────────────────────────────────────────────
        val features         = mutableListOf<VectorFeature>()
        var fId              = 0
        var inEntities       = false
        var detectedCs       = CoordSystem.UNKNOWN_PROJECTED
        var detectedCm       = hintCm

        // Accumulator cho old-style POLYLINE → VERTEX… → SEQEND
        var polyLyr          = ""
        val polyRaw          = mutableListOf<Pair<Double, Double>>()

        fun recordCs(cs: CoordSystem, cm: Double) {
            if (detectedCs == CoordSystem.UNKNOWN_PROJECTED && cs != CoordSystem.UNKNOWN_PROJECTED) {
                detectedCs = cs; detectedCm = cm
            }
        }

        var k = 0
        while (k < tokens.size) {
            val (code, value) = tokens[k++]
            when {
                code == 2 && value == "ENTITIES" -> inEntities = true
                code == 0 && value == "ENDSEC"   -> inEntities = false
                inEntities && code == 0          -> {
                    val entityType = value

                    // ── Collect ALL attribute pairs until the next entity (code 0) ──
                    // Dùng Map<Int, MutableList<String>> để xử lý các code lặp lại
                    // (VD: LWPOLYLINE dùng 10 nhiều lần cho X, 20 nhiều lần cho Y)
                    val attr = HashMap<Int, MutableList<String>>()
                    while (k < tokens.size && tokens[k].first != 0) {
                        val (ac, av) = tokens[k++]
                        attr.getOrPut(ac) { mutableListOf() }.add(av)
                    }

                    // Helper: lấy giá trị double từ attr (index=0 mặc định)
                    fun dbl(gc: Int, idx: Int = 0) =
                        attr[gc]?.getOrNull(idx)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                    fun str(gc: Int) = attr[gc]?.firstOrNull() ?: ""

                    when (entityType) {
                        // ── Điểm ──────────────────────────────────────────
                        "POINT" -> {
                            val x = dbl(10); val y = dbl(20)
                            val (cs, cm, geo) = resolve(x, y, hintCm)
                            if (geo != null) {
                                recordCs(cs, cm)
                                features += VectorFeature(fId++, FeatureType.POINT, listOf(geo), listOf(x to y), cs, cm, str(8))
                            }
                        }

                        // ── Đường thẳng 2 điểm ────────────────────────────
                        "LINE" -> {
                            val x1 = dbl(10); val y1 = dbl(20)
                            val x2 = dbl(11); val y2 = dbl(21)
                            val (cs, cm, g1) = resolve(x1, y1, hintCm)
                            val (_,  _,  g2) = resolve(x2, y2, hintCm)
                            if (g1 != null && g2 != null) {
                                recordCs(cs, cm)
                                features += VectorFeature(fId++, FeatureType.POLYLINE,
                                    listOf(g1, g2), listOf(x1 to y1, x2 to y2), cs, cm, str(8))
                            }
                        }

                        // ── Polyline nhẹ (AutoCAD 2000+) ──────────────────
                        // Code 10 lặp lại cho mỗi đỉnh X, code 20 lặp lại cho mỗi đỉnh Y
                        "LWPOLYLINE" -> {
                            val xs  = attr[10]?.mapNotNull { it.replace(",", ".").toDoubleOrNull() } ?: emptyList()
                            val ys  = attr[20]?.mapNotNull { it.replace(",", ".").toDoubleOrNull() } ?: emptyList()
                            val raw = xs.zip(ys)
                            if (raw.size >= 2) {
                                val (cs, cm, _) = resolve(raw[0].first, raw[0].second, hintCm)
                                val geo = raw.mapNotNull { (x, y) -> resolve(x, y, hintCm).third }
                                if (geo.size >= 2) {
                                    recordCs(cs, cm)
                                    features += VectorFeature(fId++, FeatureType.POLYLINE, geo, raw, cs, cm, str(8))
                                }
                            }
                        }

                        // ── Polyline cũ (R12) — bắt đầu accumulate ────────
                        "POLYLINE" -> {
                            polyLyr = str(8)
                            polyRaw.clear()
                        }

                        // ── Đỉnh của POLYLINE cũ ──────────────────────────
                        "VERTEX" -> {
                            val x = dbl(10); val y = dbl(20)
                            if (x != 0.0 || y != 0.0) polyRaw.add(x to y)
                        }

                        // ── Kết thúc POLYLINE cũ — flush accumulator ──────
                        "SEQEND" -> {
                            if (polyRaw.size >= 2) {
                                val (cs, cm, _) = resolve(polyRaw[0].first, polyRaw[0].second, hintCm)
                                val geo = polyRaw.mapNotNull { (x, y) -> resolve(x, y, hintCm).third }
                                if (geo.size >= 2) {
                                    recordCs(cs, cm)
                                    features += VectorFeature(fId++, FeatureType.POLYLINE,
                                        geo, polyRaw.toList(), cs, cm, polyLyr)
                                }
                            }
                            polyRaw.clear(); polyLyr = ""
                        }

                        // ── Cung tròn / Đường tròn → điểm tâm ───────────
                        "ARC", "CIRCLE" -> {
                            val x = dbl(10); val y = dbl(20)
                            val (cs, cm, geo) = resolve(x, y, hintCm)
                            if (geo != null) {
                                recordCs(cs, cm)
                                features += VectorFeature(fId++, FeatureType.POINT, listOf(geo), listOf(x to y), cs, cm, str(8))
                            }
                        }

                        // ── Chữ / Block Insert → điểm chèn ───────────────
                        "TEXT", "MTEXT", "INSERT" -> {
                            val x = dbl(10); val y = dbl(20)
                            val (cs, cm, geo) = resolve(x, y, hintCm)
                            if (geo != null) {
                                recordCs(cs, cm)
                                // code 1 = nội dung text; code 2 = block name (INSERT)
                                val lbl = str(1).ifBlank { str(2) }
                                features += VectorFeature(fId++, FeatureType.POINT, listOf(geo), listOf(x to y), cs, cm, str(8), lbl)
                            }
                        }

                        // Bỏ qua SPLINE, HATCH, DIMENSION, ... chưa hỗ trợ
                        else -> { /* skip */ }
                    }
                }
                else -> { /* tokens ngoài ENTITIES section — bỏ qua */ }
            }
        }

        if (detectedCs == CoordSystem.UNKNOWN_PROJECTED && features.isNotEmpty())
            detectedCs = features.first().coordSystem
        return VectorLayer(name = name, features = features, coordSystem = detectedCs, detectedCm = detectedCm)
    }

        // ══════════════════════════════════════════════════════════
    // SHP Parser
    // ══════════════════════════════════════════════════════════

    private fun parseShp(stream: InputStream, name: String, hintCm: Double): VectorLayer {
        val bytes = stream.readBytes()
        val buf   = ByteBuffer.wrap(bytes)
        buf.order(ByteOrder.BIG_ENDIAN)
        if (buf.getInt(0) != 9994) throw IllegalArgumentException("Không phải file SHP hợp lệ")

        val features   = mutableListOf<VectorFeature>()
        var fId        = 0
        var detectedCs = CoordSystem.UNKNOWN_PROJECTED
        var detectedCm = hintCm

        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.position(100)

        while (buf.remaining() >= 8) {
            buf.order(ByteOrder.BIG_ENDIAN)
            buf.getInt()
            val contentLen = buf.getInt() * 2
            if (buf.remaining() < contentLen) break
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val shpType = buf.getInt()

            when (shpType) {
                1 -> {
                    val x = buf.getDouble(); val y = buf.getDouble()
                    val (cs, cm, geo) = resolve(x, y, hintCm)
                    if (geo != null) {
                        if (detectedCs == CoordSystem.UNKNOWN_PROJECTED) { detectedCs=cs; detectedCm=cm }
                        features += VectorFeature(fId++, FeatureType.POINT, listOf(geo), listOf(x to y), cs, cm)
                    }
                }
                3, 5 -> {
                    repeat(4) { buf.getDouble() }
                    val numParts  = buf.getInt()
                    val numPoints = buf.getInt()
                    val partStarts = IntArray(numParts) { buf.getInt() }
                    val rawAll = Array(numPoints) { buf.getDouble() to buf.getDouble() }
                    for (p in 0 until numParts) {
                        val start   = partStarts[p]
                        val end     = if (p+1 < numParts) partStarts[p+1] else numPoints
                        val rawRing = (start until end).map { rawAll[it] }
                        if (rawRing.size < 2) continue
                        val (cs, cm, _) = resolve(rawRing[0].first, rawRing[0].second, hintCm)
                        val geo = rawRing.mapNotNull { (x,y) -> resolve(x,y,hintCm).third }
                        if (geo.size < 2) continue
                        if (detectedCs == CoordSystem.UNKNOWN_PROJECTED) { detectedCs=cs; detectedCm=cm }
                        val type = if (shpType==5) FeatureType.POLYGON else FeatureType.POLYLINE
                        features += VectorFeature(fId++, type, geo, rawRing, cs, cm)
                    }
                }
                else -> buf.position(buf.position() + contentLen - 4)
            }
        }
        if (detectedCs == CoordSystem.UNKNOWN_PROJECTED && features.isNotEmpty()) detectedCs = features.first().coordSystem
        return VectorLayer(name=name, features=features, coordSystem=detectedCs, detectedCm=detectedCm)
    }

    // ══════════════════════════════════════════════════════════
    // CSV/TXT Points
    // ══════════════════════════════════════════════════════════

    private fun parseCsvPoints(stream: InputStream, name: String, hintCm: Double): VectorLayer {
        val features   = mutableListOf<VectorFeature>()
        var fId        = 0
        var detectedCs = CoordSystem.UNKNOWN_PROJECTED
        var detectedCm = hintCm

        stream.bufferedReader().forEachLine { line ->
            if (line.startsWith("#") || line.isBlank()) return@forEachLine
            val parts = if (line.contains('\t')) line.split('\t') else line.split(',')
            if (parts.size < 3) return@forEachLine
            val lbl  = parts[0].trim()
            val col1 = parts[1].trim().replace(" ","").toDoubleOrNull() ?: return@forEachLine  // northing/lat
            val col2 = parts[2].trim().replace(" ","").toDoubleOrNull() ?: return@forEachLine  // easting/lon
            val (cs, cm, geo) = resolve(col2, col1, hintCm)
            if (geo != null) {
                if (detectedCs == CoordSystem.UNKNOWN_PROJECTED) { detectedCs=cs; detectedCm=cm }
                features += VectorFeature(fId++, FeatureType.POINT, listOf(geo), listOf(col2 to col1), cs, cm, label=lbl)
            }
        }
        if (detectedCs == CoordSystem.UNKNOWN_PROJECTED && features.isNotEmpty()) detectedCs = features.first().coordSystem
        return VectorLayer(name=name, features=features, coordSystem=detectedCs, detectedCm=detectedCm)
    }

    // ══════════════════════════════════════════════════════════
    // GeoJSON parser
    // ══════════════════════════════════════════════════════════

    private fun parseGeoJson(
        stream: java.io.InputStream, name: String, hintCentralMeridian: Double
    ): VectorLayer =
        parseGeoJsonText(stream.bufferedReader().use { it.readText() }, name, hintCentralMeridian)

    /**
     * Đọc chuỗi GeoJSON (FeatureCollection / Feature / Geometry) → VectorLayer.
     * Toạ độ [x, y] = [lon/easting, lat/northing]; resolve() tự nhận diện
     * WGS-84 hay VN-2000 theo độ lớn. Dùng cho cả file cục bộ lẫn tải từ server.
     */
    internal fun parseGeoJsonText(
        text: String, name: String, hintCentralMeridian: Double = 0.0
    ): VectorLayer {
        val root = org.json.JSONObject(text)
        val features = mutableListOf<VectorFeature>()
        var idSeq = 0
        var layerCs = CoordSystem.UNKNOWN_PROJECTED
        var layerCm = 0.0

        fun coordsToPts(arr: org.json.JSONArray): Pair<List<GeoPoint>, List<Pair<Double, Double>>> {
            val geo = mutableListOf<GeoPoint>(); val raw = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONArray(i)
                val x = c.getDouble(0); val y = c.getDouble(1)
                val (cs, cm, gp) = resolve(x, y, hintCentralMeridian)
                if (gp != null) {
                    geo += gp; raw += Pair(x, y)
                    if (layerCs == CoordSystem.UNKNOWN_PROJECTED) { layerCs = cs; layerCm = cm }
                }
            }
            return geo to raw
        }

        fun addGeometry(
            geom: org.json.JSONObject, label: String,
            soThua: String = "", dienTich: String = "", loaiDat: String = "", chuSuDung: String = ""
        ) {
            val coords = geom.optJSONArray("coordinates") ?: return
            when (geom.optString("type")) {
                "Point" -> {
                    val x = coords.getDouble(0); val y = coords.getDouble(1)
                    val (cs, cm, gp) = resolve(x, y, hintCentralMeridian)
                    if (gp != null) {
                        if (layerCs == CoordSystem.UNKNOWN_PROJECTED) { layerCs = cs; layerCm = cm }
                        features += VectorFeature(idSeq++, FeatureType.POINT, listOf(gp), listOf(Pair(x, y)), cs, cm, "", label, soThua, dienTich, loaiDat, chuSuDung)
                    }
                }
                "LineString" -> {
                    val (g, r) = coordsToPts(coords)
                    if (g.size >= 2) features += VectorFeature(idSeq++, FeatureType.POLYLINE, g, r, layerCs, layerCm, "", label, soThua, dienTich, loaiDat, chuSuDung)
                }
                "Polygon" -> if (coords.length() > 0) {
                    val (g, r) = coordsToPts(coords.getJSONArray(0))   // vòng ngoài
                    if (g.size >= 3) features += VectorFeature(idSeq++, FeatureType.POLYGON, g, r, layerCs, layerCm, "", label, soThua, dienTich, loaiDat, chuSuDung)
                }
                "MultiLineString" -> for (i in 0 until coords.length()) {
                    val (g, r) = coordsToPts(coords.getJSONArray(i))
                    if (g.size >= 2) features += VectorFeature(idSeq++, FeatureType.POLYLINE, g, r, layerCs, layerCm, "", label, soThua, dienTich, loaiDat, chuSuDung)
                }
                "MultiPolygon" -> for (i in 0 until coords.length()) {
                    val poly = coords.getJSONArray(i)
                    if (poly.length() > 0) {
                        val (g, r) = coordsToPts(poly.getJSONArray(0))
                        if (g.size >= 3) features += VectorFeature(idSeq++, FeatureType.POLYGON, g, r, layerCs, layerCm, "", label, soThua, dienTich, loaiDat, chuSuDung)
                    }
                }
            }
        }

        fun propOf(props: org.json.JSONObject?, keys: List<String>): String {
            if (props == null) return ""
            for (k in keys) if (props.has(k)) return props.opt(k)?.toString() ?: ""
            return ""
        }

        fun labelOf(props: org.json.JSONObject?): String = propOf(props, listOf(
            "so_thua", "SHThua", "soThua", "SoThua", "TCT", "SoHieuThua",
            "MaThua", "maThua", "ThuaDat", "thua", "id", "name", "Name", "label"
        ))

        when (root.optString("type")) {
            "FeatureCollection" -> {
                val fc = root.optJSONArray("features") ?: org.json.JSONArray()
                for (i in 0 until fc.length()) {
                    val f = fc.getJSONObject(i)
                    val geom = f.optJSONObject("geometry") ?: continue
                    val props = f.optJSONObject("properties")
                    val label = labelOf(props).ifEmpty { "T${i + 1}" }
                    val soThua   = propOf(props, listOf("so_thua", "SoThua", "TCT", "SoHieuThua")).ifEmpty { label }
                    val dienTich = propOf(props, listOf("dien_tich_m2", "DienTich", "DTPhapLy", "DienTichThuc"))
                    val loaiDat  = propOf(props, listOf("loai_dat", "LRD", "MaLD", "LoaiDat", "KyHieuDTSD"))
                    val chu      = propOf(props, listOf("TenChu", "ten_chu", "ChuSuDung", "TenChuSuDung", "Chu", "chu_su_dung"))
                    addGeometry(geom, label, soThua, dienTich, loaiDat, chu)
                }
            }
            "Feature" -> root.optJSONObject("geometry")?.let { addGeometry(it, labelOf(root.optJSONObject("properties"))) }
            else -> if (root.has("coordinates")) addGeometry(root, "")
        }
        return VectorLayer(name, features, layerCs, layerCm)
    }

    // ══════════════════════════════════════════════════════════
    // Coordinate resolution
    // ══════════════════════════════════════════════════════════

    private fun resolve(x: Double, y: Double, hintCm: Double): Triple<CoordSystem, Double, GeoPoint?> {
        if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite())
            return Triple(CoordSystem.UNKNOWN_PROJECTED, 0.0, null)
        return if (abs(x) <= 180.0 && abs(y) <= 90.0) {
            Triple(CoordSystem.WGS84, 0.0, GeoPoint(y, x))
        } else {
            val (cs, cm) = when {
                x in 300_000.0..900_000.0    -> Pair(CoordSystem.VN2000_3DEG, ktt(hintCm))
                x in 1_000_000.0..4_000_000.0 -> Pair(CoordSystem.VN2000_6DEG, ktt(hintCm))
                else -> return Triple(CoordSystem.UNKNOWN_PROJECTED, 0.0, null)
            }
            Triple(cs, cm, inverseVn2000(y, x, cm))
        }
    }

    private fun ktt(hintCm: Double): Double =
        if (hintCm in 100.0..115.0) hintCm else DEFAULT_CM

    // ══════════════════════════════════════════════════════════
    // VN-2000 ↔ WGS-84 Datum: 7-parameter Bursa-Wolf (QCVN 04:2009/BTNMT)
    // ══════════════════════════════════════════════════════════
    //
    // Tham số chính thức (QCVN 04:2009/BTNMT, phương quy ước position-vector):
    //
    //  Chiều WGS84 → VN2000:
    //    ΔX0 = +191.90441429 m   ΔY0 = +39.30318279 m   ΔZ0 = +111.45032835 m
    //    ω0  = +0.00928836"      ψ0  = -0.01975479"      ε0  = +0.00427372"
    //    k   = -0.252906278 ppm  →  m = 1 − 0.252906278×10⁻⁶
    //
    //  Chiều VN2000 → WGS84: đảo dấu tất cả tham số trên.
    //    ΔX0 = -191.90441429 m   ΔY0 = -39.30318279 m   ΔZ0 = -111.45032835 m
    //    ω0  = -0.00928836"      ψ0  = +0.01975479"      ε0  = -0.00427372"
    //    k   = +0.252906278 ppm  →  m = 1 + 0.252906278×10⁻⁶
    //
    // ══════════════════════════════════════════════════════════

    // 1 arc-second = π/648000 radians
    // WGS84 → VN2000
    private val H_DX =  191.90441429            // m  (dương)
    private val H_DY =   39.30318279            // m  (dương)
    private val H_DZ =  111.45032835            // m  (dương)
    // SỬA (2026): ma trận helmert() dưới đây theo dạng coordinate-frame; để KHỚP đúng
    // pipeline (ProjNet/TOWGS84, position-vector) đã kiểm chứng, RY và RZ phải ĐẢO DẤU
    // so với bảng tham số hiển thị (RX giữ nguyên). Đồng bộ với HelmertTransform của app RTK.
    private val H_RX =  0.00928836 * (PI / 648000.0)   // ω0
    private val H_RY =  0.01975479 * (PI / 648000.0)   // ψ0  (ĐẢO DẤU: từ −0.01975479)
    private val H_RZ = -0.00427372 * (PI / 648000.0)   // ε0  (ĐẢO DẤU: từ +0.00427372)
    private val H_M  =  1.0 - 0.252906278e-6   // m = 1 + k×10⁻⁶, k=-0.252906278ppm

    // VN2000 → WGS84 (đảo dấu tất cả tham số)
    private val I_DX = -H_DX
    private val I_DY = -H_DY
    private val I_DZ = -H_DZ
    private val I_RX = -H_RX
    private val I_RY = -H_RY
    private val I_RZ = -H_RZ
    private val I_M  =  1.0 + 0.252906278e-6   // k=+0.252906278ppm

    // Bursa-Wolf transform — ma trận dạng coordinate-frame:
    //   [ 1   rz  -ry ; -rz  1   rx ;  ry -rx  1 ]  (nhân với hệ số tỉ lệ m, cộng tịnh tiến)
    private fun helmert(
        dx: Double, dy: Double, dz: Double,
        rx: Double, ry: Double, rz: Double, m: Double,
        x: Double,  y: Double,  z: Double
    ): Triple<Double, Double, Double> = Triple(
        dx + m * ( x  + rz*y - ry*z),
        dy + m * (-rz*x + y  + rx*z),
        dz + m * ( ry*x - rx*y + z )
    )

    // WGS84 geographic (rad) → ECEF (m)  [ellipsoid WGS84 / GRS-80]
    private fun geographicToEcef(latRad: Double, lonRad: Double): Triple<Double,Double,Double> {
        val a = 6_378_137.0; val e2 = 0.00669437999014
        val sinLat = sin(latRad)
        val N = a / sqrt(1.0 - e2 * sinLat * sinLat)
        return Triple(
            N * cos(latRad) * cos(lonRad),
            N * cos(latRad) * sin(lonRad),
            N * (1.0 - e2) * sinLat
        )
    }

    // ECEF (m) → geographic (lat, lon radians) — Bowring iterative
    private fun ecefToGeographic(X: Double, Y: Double, Z: Double): Pair<Double,Double> {
        val a = 6_378_137.0; val e2 = 0.00669437999014
        val lon = atan2(Y, X)
        val p = sqrt(X * X + Y * Y)
        var lat = atan2(Z, p * (1.0 - e2))
        repeat(10) {
            val N = a / sqrt(1.0 - e2 * sin(lat) * sin(lat))
            lat = atan2(Z + e2 * N * sin(lat), p)
        }
        return lat to lon
    }

    // WGS84 ECEF → VN2000 ECEF
    private fun wgs84EcefToVn2000(xw: Double, yw: Double, zw: Double) =
        helmert(H_DX, H_DY, H_DZ, H_RX, H_RY, H_RZ, H_M, xw, yw, zw)

    // VN2000 ECEF → WGS84 ECEF  (dùng tham số inverse trực tiếp)
    private fun vn2000EcefToWgs84(xv: Double, yv: Double, zv: Double) =
        helmert(I_DX, I_DY, I_DZ, I_RX, I_RY, I_RZ, I_M, xv, yv, zv)

    // ══════════════════════════════════════════════════════════
    // Inverse Gauss-Krüger: VN-2000 → WGS-84
    // ══════════════════════════════════════════════════════════

    internal fun inverseVn2000(northing: Double, easting: Double, centralMeridian: Double? = null): GeoPoint? {
        val cm = centralMeridian ?: when {
            easting in 300_000.0..900_000.0    -> DEFAULT_CM
            easting in 1_000_000.0..4_000_000.0 -> DEFAULT_CM
            else -> return null
        }
        val a  = 6_378_137.0; val f = 1.0/298.257222101
        val b  = a*(1-f); val e2 = (a*a-b*b)/(a*a)
        val e1 = (1-sqrt(1-e2))/(1+sqrt(1-e2)); val k0 = 0.9999
        val E  = easting - 500_000.0; val N = northing
        val M  = N/k0
        val mu = M/(a*(1-e2/4-3*e2*e2/64-5*e2*e2*e2/256))

        val phi1 = mu +
            (3*e1/2 - 27*e1*e1*e1/32)*sin(2*mu) +
            (21*e1*e1/16 - 55*e1*e1*e1*e1/32)*sin(4*mu) +
            (151*e1*e1*e1/96)*sin(6*mu) +
            (1097*e1*e1*e1*e1/512)*sin(8*mu)

        val N1 = a/sqrt(1-e2*sin(phi1)*sin(phi1))
        val T1 = tan(phi1)*tan(phi1)
        val C1 = e2*cos(phi1)*cos(phi1)/(1-e2)
        val R1 = a*(1-e2)/(1-e2*sin(phi1)*sin(phi1)).pow(1.5)
        val D  = E/(N1*k0)

        val latRad = phi1 - (N1*tan(phi1)/R1)*(
            D*D/2 -
            (5+3*T1+10*C1-4*C1*C1-9*e2/(1-e2))*D*D*D*D/24 +
            (61+90*T1+298*C1+45*T1*T1-252*e2/(1-e2)-3*C1*C1)*D*D*D*D*D*D/720)

        val lonRad = (cm*PI/180.0) +
            (D - (1+2*T1+C1)*D*D*D/6 +
             (5-2*C1+28*T1-3*C1*C1+8*e2/(1-e2)+24*T1*T1)*D*D*D*D*D/120) / cos(phi1)

        // Áp dụng chuyển đổi datum 7-parameter VN2000 → WGS84
        val (xVn, yVn, zVn) = geographicToEcef(latRad, lonRad)
        val (xWgs, yWgs, zWgs) = vn2000EcefToWgs84(xVn, yVn, zVn)
        val (latWgsRad, lonWgsRad) = ecefToGeographic(xWgs, yWgs, zWgs)
        val lat = latWgsRad * 180.0 / PI
        val lon = lonWgsRad * 180.0 / PI
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }

    // ══════════════════════════════════════════════════════════
    // Forward Gauss-Krüger: WGS-84 → VN-2000 (cho stakeout)
    // ══════════════════════════════════════════════════════════

    internal fun wgs84ToVn2000(lat: Double, lon: Double, centralMeridian: Double): Pair<Double, Double>? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        // Áp dụng chuyển đổi datum 7-parameter WGS84 → VN2000
        val (xWgs, yWgs, zWgs) = geographicToEcef(lat * PI / 180.0, lon * PI / 180.0)
        val (xVnE, yVnE, zVnE) = wgs84EcefToVn2000(xWgs, yWgs, zWgs)
        val (latVnRad, lonVnRad) = ecefToGeographic(xVnE, yVnE, zVnE)
        val latVn = latVnRad * 180.0 / PI
        val lonVn = lonVnRad * 180.0 / PI
        val a    = 6_378_137.0; val f = 1.0/298.257222101
        val b    = a*(1-f); val e2 = (a*a-b*b)/(a*a); val k0 = 0.9999
        val la0  = centralMeridian*PI/180.0
        val latr = latVn*PI/180.0; val lonr = lonVn*PI/180.0
        val Nn   = a/sqrt(1-e2*sin(latr)*sin(latr))
        val T    = tan(latr)*tan(latr); val C = e2*cos(latr)*cos(latr)/(1-e2)
        val A    = cos(latr)*(lonr-la0)
        val e4   = e2*e2; val e6 = e4*e2
        val M    = a*((1-e2/4-3*e4/64-5*e6/256)*latr -
                      (3*e2/8+3*e4/32+45*e6/1024)*sin(2*latr) +
                      (15*e4/256+45*e6/1024)*sin(4*latr) -
                      (35*e6/3072)*sin(6*latr))
        val E = k0*Nn*(A + (1-T+C)*A*A*A/6 +
                       (5-18*T+T*T+72*C-58*e2/(1-e2))*A*A*A*A*A/120) + 500_000.0
        val Nr= k0*(M + Nn*tan(latr)*(A*A/2 +
                    (5-T+9*C+4*C*C)*A*A*A*A/24 +
                    (61-58*T+T*T+600*C-330*e2/(1-e2))*A*A*A*A*A*A/720))
        return Pair(Nr, E)
    }

    // ══════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════

    fun formatVn2000(northing: Double, easting: Double): String =
        "X: %,.3f m\nY: %,.3f m".format(Locale.US, northing, easting)

    // ══════════════════════════════════════════════════════════
    // Point-in-Polygon — chọn thửa bằng cách chạm VÀO GIỮA thửa
    // ══════════════════════════════════════════════════════════

    /**
     * Tìm đối tượng KHÉP KÍN (polygon / polyline đầu≈cuối) chứa điểm chạm.
     *
     * Quy tắc:
     *   • Chạm vùng trống KHÔNG nằm trong đối tượng khép kín nào → null (không hiện gì)
     *   • Nhiều thửa lồng nhau → chọn thửa NHỎ NHẤT chứa điểm (đúng thửa địa chính)
     *
     * @param lat/lon Toạ độ WGS-84 của điểm chạm trên bản đồ
     */
    fun findEnclosingFeature(layer: VectorLayer?, lat: Double, lon: Double): VectorFeature? {
        layer ?: return null
        return layer.features
            .filter { f ->
                f.geoPoints.size >= 3 && isClosedFeature(f) &&
                pointInPolygon(lat, lon, f.geoPoints)
            }
            .minByOrNull { bboxAreaDeg(it.geoPoints) }
    }

    /** Khép kín = POLYGON, hoặc POLYLINE có điểm đầu ≈ điểm cuối (≤ ~1 cm) */
    private fun isClosedFeature(f: VectorFeature): Boolean {
        if (f.type == FeatureType.POLYGON) return true
        if (f.type != FeatureType.POLYLINE) return false
        val pts = f.geoPoints
        if (pts.size < 4) return false
        val a = pts.first(); val b = pts.last()
        return abs(a.latitude - b.latitude) < 1e-7 && abs(a.longitude - b.longitude) < 1e-7
    }

    /** Ray-casting point-in-polygon trên toạ độ WGS-84 */
    private fun pointInPolygon(lat: Double, lon: Double, ring: List<GeoPoint>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i].latitude; val xi = ring[i].longitude
            val yj = ring[j].latitude; val xj = ring[j].longitude
            if ((yi > lat) != (yj > lat) &&
                lon < (xj - xi) * (lat - yi) / (yj - yi) + xi
            ) inside = !inside
            j = i
        }
        return inside
    }

    private fun bboxAreaDeg(pts: List<GeoPoint>): Double {
        val minLat = pts.minOf { it.latitude };  val maxLat = pts.maxOf { it.latitude }
        val minLon = pts.minOf { it.longitude }; val maxLon = pts.maxOf { it.longitude }
        return (maxLat - minLat) * (maxLon - minLon)
    }
}
