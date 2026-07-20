package com.hien.rtkmultidevice.ui.screens.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import com.hien.rtkmultidevice.core.cad.CadDrawingHolder
import com.hien.rtkmultidevice.core.cad.CadType
import com.hien.rtkmultidevice.core.cad.CadVertex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/**
 * Lớp phủ VẼ CAD trên osmdroid — độc lập với logic chọn thửa.
 *
 * - CadTapOverlay: KHÔNG phải MapEventsOverlay (nên không bị updateVectorOverlay xoá).
 *   Khi CadDrawingHolder.drawMode = true → chạm map thêm đỉnh (đổi WGS-84 → VN-2000);
 *   khi tắt → trả false để các overlay khác (chọn thửa) xử lý bình thường.
 * - renderCadOverlay: vẽ đối tượng đã có + đỉnh đang vẽ (title "cad" / "cad_ip").
 */

private const val CAD_COLOR = 0xFFFFEB3B.toInt()   // vàng — phân biệt với cam của thửa
private const val IP_COLOR  = 0xFF00E5FF.toInt()   // cyan — đường đang vẽ

class CadTapOverlay(private val cm: () -> Double) : Overlay() {
    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (!CadDrawingHolder.drawMode.value) return false
        val gp = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        val vn = VectorLayerImporter.wgs84ToVn2000(gp.latitude, gp.longitude, cm()) ?: return true
        CadDrawingHolder.addVertex(CadVertex(vn.first, vn.second))   // (N, E)
        mapView.invalidate()
        return true
    }
}

/** Đảm bảo có 1 CadTapOverlay trên map (thêm lại sau khi updateVectorOverlay chạy). */
fun ensureCadTapOverlay(mapView: MapView, cm: Double) {
    if (mapView.overlays.none { it is CadTapOverlay })
        mapView.overlays.add(CadTapOverlay { cm })   // thêm TRÊN CÙNG → được kiểm tra tap trước
}

/** Vẽ lại toàn bộ đối tượng CAD + đỉnh đang vẽ. Gọi trong update block của MapView. */
fun renderCadOverlay(mapView: MapView, cm: Double) {
    mapView.overlays.removeAll {
        (it is Polyline && (it.title == "cad" || it.title == "cad_ip")) ||
        (it is Marker && it.title?.startsWith("cad") == true)
    }
    fun geo(v: CadVertex): GeoPoint? = VectorLayerImporter.inverseVn2000(v.n, v.e, cm)

    CadDrawingHolder.drawing.entities.forEach { ent ->
        when (ent.type) {
            CadType.POINT -> ent.vertices.firstOrNull()?.let { v ->
                geo(v)?.let { gp ->
                    mapView.overlays.add(Marker(mapView).apply {
                        title = "cad"; position = gp; infoWindow = null
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = dot(mapView.context, CAD_COLOR)
                    })
                }
            }
            CadType.LINE -> {
                val g = ent.vertices.mapNotNull { geo(it) }
                if (g.size >= 2) mapView.overlays.add(Polyline(mapView).apply {
                    title = "cad"; infoWindow = null; setPoints(g)
                    outlinePaint.color = CAD_COLOR; outlinePaint.strokeWidth = 3f
                })
            }
            CadType.POLYGON -> {
                val g = ent.vertices.mapNotNull { geo(it) }
                if (g.size >= 3) mapView.overlays.add(Polyline(mapView).apply {
                    title = "cad"; infoWindow = null; setPoints(g + g.first())   // khép kín
                    outlinePaint.color = CAD_COLOR; outlinePaint.strokeWidth = 3f
                })
            }
        }
    }

    // Đỉnh đang vẽ
    val ip = CadDrawingHolder.inProgress.value.mapNotNull { geo(it) }
    if (ip.size >= 2) mapView.overlays.add(Polyline(mapView).apply {
        title = "cad_ip"; infoWindow = null; setPoints(ip)
        outlinePaint.color = IP_COLOR; outlinePaint.strokeWidth = 3f
    })
    ip.forEachIndexed { i, gp ->
        mapView.overlays.add(Marker(mapView).apply {
            title = "cad_ip_v$i"; position = gp; infoWindow = null
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = dot(mapView.context, IP_COLOR)
        })
    }
    mapView.invalidate()
}

private fun dot(context: Context, color: Int): Drawable {
    val d = context.resources.displayMetrics.density
    val s = (10 * d).toInt().coerceAtLeast(6)
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawCircle(s / 2f, s / 2f, s / 2f - 1f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    c.drawCircle(s / 2f, s / 2f, s / 2f - 1f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; this.color = AndroidColor.BLACK })
    return BitmapDrawable(context.resources, bmp)
}
