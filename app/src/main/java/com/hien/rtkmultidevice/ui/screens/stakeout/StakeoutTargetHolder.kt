package com.hien.rtkmultidevice.ui.screens.stakeout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * StakeoutTargetHolder — Truyền điểm thiết kế giữa các màn hình
 * (MapScreen → StakeoutScreen) qua bộ nhớ, KHÔNG qua chuỗi route Navigation.
 *
 * Lý do tồn tại (fix crash):
 *   Trước đây toạ độ + tên điểm được nối vào route string:
 *     "stakeout/1?targetN=...&targetE=...&targetName=..."
 *   Khi tên điểm (lấy từ label DXF/TEXT) chứa ký tự đặc biệt (%, /, +, xuống dòng…)
 *   chuỗi route có thể không khớp pattern đã đăng ký trong NavHost
 *   → NavController ném IllegalArgumentException → crash app.
 *
 *   Giữ target trong singleton (Hilt) là cách an toàn tuyệt đối với mọi tên điểm,
 *   mọi locale, mọi giá trị toạ độ.
 *
 * Cách dùng:
 *   1. MapViewModel.prepareStakeout(...)  → holder.set(...)
 *   2. Navigate đến route "stakeout/{projectId}" (không kèm query args)
 *   3. StakeoutViewModel.init             → holder.consume() → setManualTarget(...)
 */
/**
 * LineTarget — Tuyến (linestring) để định vị theo khoảng cách vuông góc.
 * @param vertices Danh sách đỉnh (Northing, Easting) theo thứ tự tuyến.
 */
data class LineTarget(
    val name      : String,
    val vertices  : List<Pair<Double, Double>>,   // (N, E)
    /** Id của feature trong VectorLayer — để highlight tuyến trên bản đồ */
    val featureId : Int? = null
)

@Singleton
class StakeoutTargetHolder @Inject constructor() {

    data class Target(
        val name      : String,
        val northing  : Double,
        val easting   : Double,
        /** Id feature CAD chứa điểm — để vẽ nhãn số hiệu đỉnh trên bản đồ */
        val featureId : Int? = null
    )

    @Volatile
    private var pending: Target? = null

    @Volatile
    private var pendingLine: LineTarget? = null

    /**
     * Feature CAD đang được chọn để stakeout — KHÔNG one-shot.
     * Các màn hình (Survey, Stakeout) observe để highlight đối tượng
     * và hiển thị nhãn số hiệu đỉnh (khớp cột "#" trong Bảng toạ độ đỉnh).
     * null = không có đối tượng CAD nào đang stakeout.
     */
    private val _activeFeatureId = MutableStateFlow<Int?>(null)
    val activeFeatureId: StateFlow<Int?> = _activeFeatureId.asStateFlow()

    fun setActiveFeature(id: Int?) { _activeFeatureId.value = id }

    /**
     * Tuyến đang định vị (danh sách đỉnh N,E) — chia sẻ để màn Khảo sát vẽ
     * tuyến import lên bản đồ. null = không có tuyến nào đang định vị.
     */
    private val _activeLine = MutableStateFlow<List<Pair<Double, Double>>?>(null)
    val activeLine: StateFlow<List<Pair<Double, Double>>?> = _activeLine.asStateFlow()

    fun setActiveLine(vertices: List<Pair<Double, Double>>?) { _activeLine.value = vertices }

    /**
     * Cờ một-lần: yêu cầu StakeoutScreen mở ngay hộp thoại chọn 2 điểm để
     * định vị tuyến (dùng cho thẻ "Định vị tuyến" ở menu Khảo sát / Đo tuyến).
     */
    @Volatile
    private var pendingOpenLinePicker: Boolean = false
    fun requestOpenLinePicker() { pendingOpenLinePicker = true }
    fun consumeOpenLinePicker(): Boolean {
        val v = pendingOpenLinePicker
        pendingOpenLinePicker = false
        return v
    }

    fun set(name: String, northing: Double, easting: Double, featureId: Int? = null) {
        pending     = Target(name, northing, easting, featureId)
        pendingLine = null
        _activeFeatureId.value = featureId
    }

    /** Đặt tuyến để định vị (từ MapScreen "Định vị tuyến") */
    fun setLine(name: String, vertices: List<Pair<Double, Double>>, featureId: Int? = null) {
        pendingLine = LineTarget(name, vertices, featureId)
        pending     = null
        _activeFeatureId.value = featureId
    }

    /** Lấy target và xoá ngay (one-shot) — tránh áp dụng lại khi mở Stakeout lần sau */
    fun consume(): Target? {
        val t = pending
        pending = null
        return t
    }

    fun consumeLine(): LineTarget? {
        val l = pendingLine
        pendingLine = null
        return l
    }
}
