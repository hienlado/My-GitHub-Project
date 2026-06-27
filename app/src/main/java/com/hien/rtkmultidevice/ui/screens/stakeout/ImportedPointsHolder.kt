package com.hien.rtkmultidevice.ui.screens.stakeout

import com.hien.rtkmultidevice.domain.model.SurveyPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ImportedPointsHolder — Giữ tập điểm Import (CSV/TXT) DÙNG CHUNG giữa các
 * màn hình (Bố trí điểm/Stakeout, Đo điểm/Survey, Định vị tuyến).
 *
 * Lý do tồn tại:
 *   Trước đây điểm import là state riêng của StakeoutViewModel → màn Khảo sát
 *   không thấy được để hiển thị/điều hướng. Đưa vào singleton (Hilt) để:
 *     • Import ở màn nào cũng thấy ở màn khác.
 *     • Survey có thể bật/tắt hiển thị tập điểm này trên bản đồ.
 *     • Dùng chung làm nguồn chọn điểm đầu/cuối khi định vị tuyến.
 */
@Singleton
class ImportedPointsHolder @Inject constructor() {

    private val _points = MutableStateFlow<List<SurveyPoint>>(emptyList())
    val points: StateFlow<List<SurveyPoint>> = _points.asStateFlow()

    /** Thêm các điểm mới (bỏ qua mã trùng với tập hiện có). */
    fun addPoints(newPoints: List<SurveyPoint>) {
        val existing = _points.value.map { it.pointCode }.toSet()
        _points.value = _points.value + newPoints.filter { it.pointCode !in existing }
    }

    fun clear() { _points.value = emptyList() }
}
