package com.hien.rtkmultidevice.ui.screens.map

import org.osmdroid.util.GeoPoint

/**
 * MapCameraState — Giữ mức zoom & tâm bản đồ gần nhất DÙNG CHUNG giữa các
 * màn hình (Đo điểm, Stakeout, Bản đồ) trong suốt phiên làm việc.
 *
 * Lý do tồn tại:
 *   Mỗi lần vào màn hình, MapView được tạo lại (factory chạy lại) và trước
 *   đây luôn đặt zoom = 6 (mức quốc gia) → user phải phóng to lại từ đầu.
 *   Lưu lại mức zoom/tâm gần nhất để khôi phục đúng khung nhìn làm việc.
 */
object MapCameraState {

    /** Mức zoom làm việc mặc định lần đầu vào (đủ lớn để đo/cắm mốc). */
    const val DEFAULT_WORK_ZOOM = 19.0

    /** Mức zoom gần nhất user dùng — duy trì khi vào/ra màn hình. */
    @Volatile var lastZoom: Double = DEFAULT_WORK_ZOOM

    /** Tâm bản đồ gần nhất (null = chưa có, sẽ canh theo GPS lần đầu). */
    @Volatile var lastCenter: GeoPoint? = null

    fun save(zoom: Double, lat: Double, lon: Double) {
        if (zoom > 0) lastZoom = zoom
        lastCenter = GeoPoint(lat, lon)
    }
}
