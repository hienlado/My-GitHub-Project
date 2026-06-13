package com.hien.rtkmultidevice.ui.screens.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * GnssMapView — Bản đồ osmdroid dùng chung cho Survey, Stakeout, Map screens.
 *
 * Tính năng:
 *   - Hiển thị vị trí GPS hiện tại (auto-follow)
 *   - Hiển thị danh sách điểm đo với marker màu theo fix quality
 *   - Nhãn điểm có thể cấu hình qua [labelConfig]:
 *       • Mã điểm (P001)
 *       • Độ cao (h=131.45m)
 *       • Fix quality (FIXED, FLOAT)
 *       • HDOP
 *       • Toạ độ VN-2000 (X/Y)
 *   - Hiển thị điểm mục tiêu Stakeout (target marker + đường dẫn hướng)
 *   - Tile source có thể thay đổi
 */
@Composable
fun GnssMapView(
    modifier             : Modifier = Modifier,
    gnss                 : GnssStatus,
    points               : List<SurveyPoint> = emptyList(),
    followGps            : Boolean = true,
    tileSource           : MapTileSource = MapTileSource.GOOGLE_NORMAL,   // mặc định Google Maps
    labelConfig          : MapLabelConfig = MapLabelConfig(),
    targetPoint          : GeoPoint? = null,
    targetLabel          : String = "",
    vectorLayer          : VectorLayerImporter.VectorLayer? = null,
    /** Id feature cần highlight (đang chọn / đang định vị) — vẽ cyan nét đậm */
    highlightFeatureId   : Int? = null,
    /** Hiện/ẩn nút zoom +/- và Fit layer */
    showZoomControls     : Boolean = true,
    /** Khoảng cách từ bottom để tránh đè lên panel phía dưới */
    zoomControlsBottomPadding : Dp = 80.dp,
    onMarkerTap          : (SurveyPoint) -> Unit = {},
    /**
     * Chạm vào node của vector layer (đỉnh polyline / điểm / vùng).
     * Tham số: (feature, vertexIdx) — màn hình gọi sẽ mở Bảng toạ độ đỉnh
     * (VertexTableDialog) để chọn đỉnh cắm mốc.
     * null = không vẽ node tappable (chỉ vẽ polyline).
     */
    onVectorNodeTap      : ((feature: VectorLayerImporter.VectorFeature, vertexIdx: Int) -> Unit)? = null,
    onScrolled           : () -> Unit = {}
) {
    val mapViewRef    = remember { mutableStateOf<MapView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier) {

        // ── Bản đồ osmdroid ─────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                Configuration.getInstance().apply {
                    load(ctx, ctx.getSharedPreferences("osmdroid", 0))
                    // UA theo nguồn tile: OSM chặn UA giả trình duyệt, Google cần UA trình duyệt
                    userAgentValue = MapTileSource.userAgentFor(tileSource)
                    // Dùng filesDir thay cacheDir: Android có thể dọn cacheDir giữa chừng
                    // → SQLite tile cache hỏng → tile không vẽ được
                    val baseDir = java.io.File(ctx.filesDir, "osmdroid")
                    val tileDir = java.io.File(baseDir, "tiles")
                    tileDir.mkdirs()
                    osmdroidBasePath             = baseDir
                    osmdroidTileCache            = tileDir
                    tileDownloadMaxQueueSize     = 40
                    tileFileSystemCacheMaxBytes  = 200L * 1024 * 1024
                    tileFileSystemCacheTrimBytes = 150L * 1024 * 1024
                }
                MapView(ctx).apply {
                    setTileSource(tileSource.toOsmdroidSource())
                    setMultiTouchControls(true)
                    // Center mặc định = Trung tâm Việt Nam (Đà Nẵng) ở zoom 6
                    // để hiện bản đồ ngay khi chưa có GPS — tránh thấy biển hoặc màn xám
                    controller.setZoom(6.0)
                    controller.setCenter(GeoPoint(16.0, 107.5))
                    mapViewRef.value = this
                }
            },
            update = { mapView ->
                if (mapView.tileProvider.tileSource.name() != tileSource.toOsmdroidSource().name()) {
                    // Đổi UA TRƯỚC khi đổi nguồn tile
                    Configuration.getInstance().userAgentValue = MapTileSource.userAgentFor(tileSource)
                    mapView.setTileSource(tileSource.toOsmdroidSource())
                }
                // Vector layer: vẽ có cache — chỉ tái tạo khi layer/highlight thay đổi
                updateGnssVectorOverlays(mapView, vectorLayer, highlightFeatureId, onVectorNodeTap)
                updateGnssMapOverlays(
                    mapView     = mapView,
                    gnss        = gnss,
                    points      = points,
                    followGps   = followGps,
                    labelConfig = labelConfig,
                    targetPoint = targetPoint,
                    targetLabel = targetLabel,
                    onMarkerTap = onMarkerTap,
                    onScrolled  = onScrolled
                )
            }
        )

        // ── Zoom controls — phải dưới ────────────────────────
        if (showZoomControls) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = zoomControlsBottomPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Fit to layer — chỉ hiện khi có vectorLayer
                if (vectorLayer != null && !vectorLayer.isEmpty) {
                    SmallFloatingActionButton(
                        onClick = { fitToVectorLayer(mapViewRef.value, vectorLayer) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor   = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, "Fit layer", Modifier.size(18.dp))
                    }
                }

                // Zoom In
                SmallFloatingActionButton(
                    onClick = { mapViewRef.value?.controller?.zoomIn() },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor   = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, "Zoom in", Modifier.size(18.dp))
                }

                // Zoom Out
                SmallFloatingActionButton(
                    onClick = { mapViewRef.value?.controller?.zoomOut() },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor   = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Remove, "Zoom out", Modifier.size(18.dp))
                }

            }
        }
    }

    // Lifecycle-aware: osmdroid cần onResume() để bắt đầu download tiles,
    // onPause() để dừng lại khi màn hình không visible.
    // Không có onResume() → tiles không bao giờ được request dù có internet.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapViewRef.value?.onPause()
                else                      -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef.value?.onDetach()
        }
    }
}

/**
 * Zoom bản đồ để hiển thị toàn bộ vector layer (DXF/SHP).
 * Tính bounding box từ tất cả polyline → animateTo với padding 50px.
 */
private fun fitToVectorLayer(mapView: MapView?, layer: VectorLayerImporter.VectorLayer) {
    if (mapView == null) return
    // Gồm cả polyline + polygon + point để bao trọn layer
    val allPoints = (layer.polylines + layer.polygons).flatten() + layer.points
    if (allPoints.isEmpty()) return

    val minLat = allPoints.minOf { it.latitude }
    val maxLat = allPoints.maxOf { it.latitude }
    val minLon = allPoints.minOf { it.longitude }
    val maxLon = allPoints.maxOf { it.longitude }

    if (minLat == maxLat && minLon == maxLon) {
        // Chỉ có 1 điểm → zoom đến điểm đó
        mapView.controller.animateTo(GeoPoint(minLat, minLon))
        mapView.controller.setZoom(18.0)
        return
    }

    val bbox = BoundingBox(maxLat, maxLon, minLat, minLon)
    mapView.post {
        mapView.zoomToBoundingBox(bbox, true, 60)
    }
}

// ────────────────────────────────────────────────────────────
// Core overlay update
// ────────────────────────────────────────────────────────────

private fun updateGnssMapOverlays(
    mapView     : MapView,
    gnss        : GnssStatus,
    points      : List<SurveyPoint>,
    followGps   : Boolean,
    labelConfig : MapLabelConfig,
    targetPoint : GeoPoint?,
    targetLabel : String,
    onMarkerTap : (SurveyPoint) -> Unit,
    onScrolled  : () -> Unit
) {
    // Chỉ xoá overlay GPS/điểm đo — GIỮ NGUYÊN vector overlays (đã cache riêng)
    // và MapEventsOverlay (bắt tap vào giữa thửa)
    mapView.overlays.removeAll { overlay ->
        when (overlay) {
            is Polyline                                       -> overlay.title != "vector"
            is Marker                                         -> overlay.title?.startsWith("vec_") != true
            is org.osmdroid.views.overlay.MapEventsOverlay    -> false
            else                                              -> true
        }
    }

    // ── Polyline nối các điểm đo ─────────────────────────
    // CHỈ nối các điểm ĐO RTK thật (fixQuality > 0).
    // Điểm import từ DXF/CSV (fixQuality = 0 — ví dụ tâm thửa, nhãn TEXT)
    // KHÔNG được nối — tránh những đường nối tâm thửa rối mắt trên bản đồ.
    val measuredPoints = points.filter {
        it.fixQuality > 0 && it.latitude != 0.0 && it.longitude != 0.0
    }
    if (measuredPoints.size >= 2) {
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = AndroidColor.parseColor("#661565C0")
            outlinePaint.strokeWidth = 3f
            setPoints(measuredPoints.map { GeoPoint(it.latitude, it.longitude) })
        }
        mapView.overlays.add(polyline)
    }

    // ── Marker từng điểm đo với nhãn ─────────────────────
    points.forEach { point ->
        if (point.latitude == 0.0 && point.longitude == 0.0) return@forEach
        val marker = Marker(mapView).apply {
            position = GeoPoint(point.latitude, point.longitude)
            title    = point.pointCode
            snippet  = buildSnippet(point)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createLabeledPointDrawable(
                context     = mapView.context,
                color       = AndroidColor.parseColor(point.fixColorHex),
                labelConfig = labelConfig,
                point       = point
            )
            setOnMarkerClickListener { _, _ -> onMarkerTap(point); true }
        }
        mapView.overlays.add(marker)
    }

    // ── Marker điểm mục tiêu Stakeout ───────────────────
    targetPoint?.let { tp ->
        // "Sợi tóc" — đường dẫn từ vị trí RTK đến mục tiêu
        // (điểm thiết kế, hoặc chân vuông góc khi định vị tuyến) — màu xanh blue
        if (gnss.hasFix) {
            val line = Polyline(mapView).apply {
                infoWindow = null
                outlinePaint.color = AndroidColor.parseColor("#FF2979FF")   // xanh blue
                outlinePaint.strokeWidth = 3f
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
                setPoints(listOf(GeoPoint(gnss.latitude, gnss.longitude), tp))
            }
            mapView.overlays.add(line)
        }
        // Target marker
        val targetMarker = Marker(mapView).apply {
            position = tp
            title    = targetLabel.ifEmpty { "Mục tiêu" }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createTargetDrawable(mapView.context)
        }
        mapView.overlays.add(targetMarker)
    }

    // ── GPS vị trí hiện tại ──────────────────────────────
    if (gnss.hasFix) {
        val currentPos = GeoPoint(gnss.latitude, gnss.longitude)
        val gpsMarker = Marker(mapView).apply {
            position = currentPos
            title    = gnss.fixLabel
            snippet  = "HDOP: ${"%.1f".format(gnss.hdop)}  Sats: ${gnss.satelliteCount}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createGpsPositionDrawable(mapView.context, gnss.fixQuality)
        }
        mapView.overlays.add(gpsMarker)
        if (followGps) mapView.controller.animateTo(currentPos)
    }

    mapView.invalidate()
}

/**
 * Vẽ vector layer (DXF/SHP) lên GnssMapView — CÓ CACHE theo object identity:
 * chỉ tái tạo overlay khi layer thực sự thay đổi, tránh vẽ lại hàng trăm
 * đối tượng mỗi lần GNSS cập nhật (1 Hz).
 *
 * Khi [onVectorNodeTap] != null: thêm marker tappable tại các node
 * (điểm POINT, đỉnh polyline/polygon) — chạm để chọn làm điểm cắm mốc.
 */
private fun updateGnssVectorOverlays(
    mapView            : MapView,
    layer              : VectorLayerImporter.VectorLayer?,
    highlightFeatureId : Int?,
    onVectorNodeTap    : ((feature: VectorLayerImporter.VectorFeature, vertexIdx: Int) -> Unit)?
) {
    // Cache check — chỉ vẽ lại khi layer HOẶC highlight thay đổi
    // (identityHashCode: so sánh rẻ, không deep-compare hàng nghìn toạ độ)
    val cacheKey = "vec_${System.identityHashCode(layer)}_$highlightFeatureId"
    if (mapView.tag == cacheKey) return
    mapView.tag = cacheKey

    // Xoá overlay vector cũ + MapEventsOverlay cũ
    mapView.overlays.removeAll { it is Polyline && it.title == "vector" }
    mapView.overlays.removeAll { it is Marker  && it.title?.startsWith("vec_") == true }
    mapView.overlays.removeAll { it is org.osmdroid.views.overlay.MapEventsOverlay }

    if (layer == null) { mapView.invalidate(); return }

    // ── Tap vào GIỮA thửa (vùng trống bên trong đối tượng khép kín) ──
    // → tìm polygon nhỏ nhất chứa điểm chạm → mở tuỳ chọn định vị.
    // Tap vùng trống ngoài mọi đối tượng khép kín → không làm gì.
    // Đặt ở index 0 để marker/polyline được ưu tiên xử lý tap trước.
    if (onVectorNodeTap != null) {
        val receiver = object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val f = VectorLayerImporter.findEnclosingFeature(layer, p.latitude, p.longitude)
                    ?: return false
                if (!f.isVn2000) return false
                onVectorNodeTap(f, 0)
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        mapView.overlays.add(0, org.osmdroid.views.overlay.MapEventsOverlay(receiver))
    }

    val normalColor    = AndroidColor.argb(200, 255, 100, 0)   // cam
    val highlightColor = AndroidColor.argb(255, 0, 229, 255)   // cyan nổi bật

    // ── Polyline / Polygon — vẽ THEO TỪNG FEATURE để chạm trực tiếp ──
    // Chạm vào bất kỳ chỗ nào trên đường → mở tuỳ chọn Định vị điểm/tuyến.
    // Feature đang chọn/định vị được HIGHLIGHT cyan + nét đậm.
    layer.features.forEach { feature ->
        if (feature.type == VectorLayerImporter.FeatureType.POINT) return@forEach
        if (feature.geoPoints.size < 2) return@forEach
        val isHighlighted = feature.id == highlightFeatureId
        val pl = Polyline(mapView).apply {
            title      = "vector"
            infoWindow = null   // tắt popup mặc định "vector" của osmdroid
            setPoints(feature.geoPoints)
            outlinePaint.color       = if (isHighlighted) highlightColor else normalColor
            outlinePaint.strokeWidth = if (isHighlighted) 7f else 2.5f
            if (onVectorNodeTap != null && feature.isVn2000) {
                setOnClickListener { _, _, _ -> onVectorNodeTap(feature, 0); true }
            } else {
                setOnClickListener { _, _, _ -> true }  // nuốt tap — không hiện popup
            }
        }
        mapView.overlays.add(pl)
    }

    // ── Nhãn số hiệu đỉnh — feature đang chọn để stakeout ──
    // Số thứ tự khớp cột "#" trong Bảng toạ độ đỉnh (VertexTableDialog):
    // đỉnh đầu = 1, đỉnh cuối = số đỉnh. Vẽ cho MỌI màn hình dùng GnssMapView
    // (Survey, Stakeout, Map) — không phụ thuộc onVectorNodeTap.
    if (highlightFeatureId != null) {
        layer.features.firstOrNull { it.id == highlightFeatureId }?.let { f ->
            f.geoPoints.take(300).forEachIndexed { idx, gp ->
                mapView.overlays.add(Marker(mapView).apply {
                    title      = "vec_lbl_${f.id}_$idx"
                    position   = gp
                    infoWindow = null
                    // Nhãn nằm phía trên-phải node để không che node
                    setAnchor(0f, 1f)
                    icon = createVertexNumberLabel(mapView.context, "${idx + 1}")
                    setOnMarkerClickListener { _, _ -> false }   // không chặn tap
                })
            }
        }
    }

    // ── Node tappable (chỉ khi có callback) ──────────────
    if (onVectorNodeTap == null) { mapView.invalidate(); return }

    var markerBudget = 1500
    layer.features.forEach { feature ->
        if (markerBudget <= 0) return@forEach
        // Chỉ node VN-2000 mới chọn được làm điểm cắm mốc
        if (!feature.isVn2000) return@forEach

        val indices: List<Int> = when (feature.type) {
            VectorLayerImporter.FeatureType.POINT    -> feature.geoPoints.indices.toList()
            VectorLayerImporter.FeatureType.POLYLINE ->
                if (feature.geoPoints.size <= 10) feature.geoPoints.indices.toList()
                else listOf(0, feature.geoPoints.lastIndex)
            VectorLayerImporter.FeatureType.POLYGON  -> feature.geoPoints.indices.toList().take(50)
        }

        indices.forEach { vidx ->
            if (markerBudget <= 0) return@forEach
            val gp = feature.geoPoints.getOrNull(vidx) ?: return@forEach
            if (feature.rawPoints.getOrNull(vidx) == null) return@forEach
            mapView.overlays.add(Marker(mapView).apply {
                title      = "vec_${feature.id}_$vidx"
                position   = gp
                infoWindow = null
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createVectorNodeDot(
                    mapView.context, feature.type,
                    highlighted = feature.id == highlightFeatureId
                )
                setOnMarkerClickListener { _, _ ->
                    // Mở dialog hành động — Định vị điểm / tuyến / Bảng đỉnh
                    onVectorNodeTap(feature, vidx); true
                }
            })
            markerBudget--
        }
    }
    mapView.invalidate()
}

/**
 * Nhãn số hiệu đỉnh — chữ cyan đậm viền đen (halo) để đọc rõ trên mọi nền bản đồ.
 * Số khớp với cột "#" trong Bảng toạ độ đỉnh.
 */
private fun createVertexNumberLabel(
    context : Context,
    text    : String
): android.graphics.drawable.Drawable {
    val textSize = 30f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        typeface  = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    val pad = 4f
    val w = (paint.measureText(text) + pad * 2).toInt().coerceAtLeast(1)
    val h = (textSize + pad * 2).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c   = Canvas(bmp)
    val x   = pad
    val y   = textSize  // baseline

    // Viền đen (halo) cho dễ đọc
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 6f
    paint.color = AndroidColor.argb(220, 0, 0, 0)
    c.drawText(text, x, y, paint)

    // Chữ cyan — đồng bộ màu highlight feature
    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.parseColor("#FF00E5FF")
    c.drawText(text, x, y, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

/** Icon node vector — chấm cam (cyan + to hơn khi highlight) có dấu "+" */
private fun createVectorNodeDot(
    context     : Context,
    featureType : VectorLayerImporter.FeatureType,
    highlighted : Boolean = false
): android.graphics.drawable.Drawable {
    val base = if (featureType == VectorLayerImporter.FeatureType.POINT) 26 else 18
    val size = if (highlighted) base + 8 else base
    val fillColor = if (highlighted) AndroidColor.parseColor("#FF00E5FF")
                    else AndroidColor.parseColor("#FFFF6D00")
    val bmp  = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c    = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.WHITE; c.drawCircle(size/2f, size/2f, size/2f, p)
    p.color = fillColor;          c.drawCircle(size/2f, size/2f, size/2f - 3f, p)
    p.color = AndroidColor.WHITE; p.strokeWidth = 1.5f; p.style = Paint.Style.STROKE
    val cx = size/2f; val cy = size/2f; val r = size/2f - 7f
    if (r > 1f) { c.drawLine(cx, cy-r, cx, cy+r, p); c.drawLine(cx-r, cy, cx+r, cy, p) }
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

// ────────────────────────────────────────────────────────────
// Drawable helpers
// ────────────────────────────────────────────────────────────

/**
 * Tạo bitmap bao gồm dot marker + nhãn văn bản bên dưới.
 * Số dòng nhãn phụ thuộc vào [labelConfig].
 */
private fun createLabeledPointDrawable(
    context     : Context,
    color       : Int,
    labelConfig : MapLabelConfig,
    point       : SurveyPoint
): android.graphics.drawable.Drawable {
    // Thu thập các dòng nhãn theo config
    val lines = mutableListOf<String>()
    if (labelConfig.showPointCode)  lines.add(point.pointCode)
    if (labelConfig.showElevation)  lines.add("h=${"%.2f".format(point.altitude)}m")
    if (labelConfig.showVn2000 && point.northing != 0.0) {
        lines.add("X=${"%.1f".format(point.northing)}")
        lines.add("Y=${"%.1f".format(point.easting)}")
    }
    if (labelConfig.showFixQuality) lines.add(point.fixLabel)
    if (labelConfig.showHdop)       lines.add("H=${"%.1f".format(point.hdop)}")

    val dotSize    = 24
    val textSize   = 28f
    val lineHeight = 32
    val padding    = 4

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize  = textSize
        this.typeface  = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Tính chiều rộng tối đa của nhãn
    val maxTextWidth = if (lines.isEmpty()) 0f
                       else lines.maxOf { paint.measureText(it) }
    val bitmapW = maxOf(dotSize, maxTextWidth.toInt() + padding * 2)
    val bitmapH = dotSize + if (lines.isEmpty()) 0 else padding + lines.size * lineHeight

    val bitmap = android.graphics.Bitmap.createBitmap(bitmapW, bitmapH, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Vẽ dot
    val cx = bitmapW / 2f; val cy = dotSize / 2f; val r = dotSize / 2f
    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(cx, cy, r, paint)
    paint.color = color
    canvas.drawCircle(cx, cy, r - 3f, paint)

    // Vẽ các dòng nhãn
    if (lines.isNotEmpty()) {
        paint.color     = AndroidColor.WHITE
        paint.style     = Paint.Style.FILL
        val bgRect = android.graphics.RectF(0f, dotSize.toFloat(), bitmapW.toFloat(), bitmapH.toFloat())
        paint.alpha = 180
        canvas.drawRoundRect(bgRect, 4f, 4f, paint)

        paint.alpha = 255
        paint.color = AndroidColor.parseColor("#1A237E")
        paint.textAlign = Paint.Align.CENTER
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, cx, dotSize + padding + textSize + i * lineHeight, paint)
        }
    }

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/** Marker mục tiêu Stakeout — hình thoi cam */
private fun createTargetDrawable(context: Context): android.graphics.drawable.Drawable {
    val size = 36
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f; val cy = size / 2f; val r = size / 2f - 2

    // Vòng tròn ngoài
    paint.color = AndroidColor.WHITE; paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, r, paint)
    // Vòng tròn trong
    paint.color = AndroidColor.parseColor("#FFFF6F00")
    canvas.drawCircle(cx, cy, r - 4f, paint)
    // Dấu X ở giữa
    paint.color = AndroidColor.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
    val d = r / 2
    canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint)
    canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/** GPS position marker — màu theo fix quality */
private fun createGpsPositionDrawable(context: Context, fixQuality: Int): android.graphics.drawable.Drawable {
    val color = when (fixQuality) {
        4    -> AndroidColor.parseColor("#FF2E7D32")  // Fixed — xanh
        5    -> AndroidColor.parseColor("#FF558B2F")  // Float — xanh nhạt
        2    -> AndroidColor.parseColor("#FF1565C0")  // DGPS — xanh dương
        1    -> AndroidColor.parseColor("#FFF57F17")  // Single — vàng
        else -> AndroidColor.parseColor("#FFB71C1C")  // No fix — đỏ
    }
    val size = 36
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f; val cy = size / 2f; val r = size / 2f - 2

    paint.color = AndroidColor.WHITE; paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, r, paint)
    paint.color = color
    canvas.drawCircle(cx, cy, r - 3f, paint)
    // Chấm trắng ở giữa
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(cx, cy, 5f, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

private fun buildSnippet(p: SurveyPoint): String = buildString {
    append("h=${"%.2f".format(p.altitude)}m  ${p.fixLabel}")
    if (p.northing != 0.0) append("\nX=${"%.3f".format(p.northing)}  Y=${"%.3f".format(p.easting)}")
}
