package com.hien.rtkmultidevice.ui.screens.survey

import androidx.lifecycle.ViewModel
import com.hien.rtkmultidevice.ui.screens.stakeout.ParcelVerticesHolder
import com.hien.rtkmultidevice.ui.screens.stakeout.StakeoutTargetHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ParcelVerticesViewModel — Cầu nối cho ngăn "Đỉnh thửa" trong Danh sách.
 *
 * Chỉ đọc dữ liệu từ ParcelVerticesHolder (đỉnh thửa lấy từ bản đồ) và đặt
 * mục tiêu định vị vào StakeoutTargetHolder. KHÔNG đụng tới Room —
 * đỉnh thửa là dữ liệu thiết kế, không phải điểm đo.
 */
@HiltViewModel
class ParcelVerticesViewModel @Inject constructor(
    private val parcelHolder   : ParcelVerticesHolder,
    private val targetHolder   : StakeoutTargetHolder
) : ViewModel() {

    val vertices  : StateFlow<List<ParcelVerticesHolder.Vertex>> = parcelHolder.vertices
    val parcelName: StateFlow<String>  = parcelHolder.parcelName
    val isClosed  : StateFlow<Boolean> = parcelHolder.isClosed

    /** Định vị 1 đỉnh (cắm mốc điểm). */
    fun stakeoutPoint(v: ParcelVerticesHolder.Vertex) {
        val name = "${parcelName.value.ifBlank { "Thửa" }}-${v.label}"
        targetHolder.set(name, v.northing, v.easting, parcelHolder.featureId.value)
    }

    /**
     * Định vị TUYẾN qua 2 đỉnh — dùng để cắm mốc dọc một cạnh thửa.
     * Stakeout sẽ hiện khoảng cách vuông góc tới cạnh + lý trình.
     */
    fun stakeoutEdge(a: ParcelVerticesHolder.Vertex, b: ParcelVerticesHolder.Vertex) {
        val name = "${parcelName.value.ifBlank { "Thửa" }} ${a.label}→${b.label}"
        targetHolder.setLine(
            name,
            listOf(a.northing to a.easting, b.northing to b.easting),
            featureId = parcelHolder.featureId.value
        )
    }

    /** Định vị TOÀN BỘ đường bao thửa (đi lần lượt qua các cạnh). */
    fun stakeoutBoundary() {
        val pts = parcelHolder.boundaryVertices()
        if (pts.size < 2) return
        targetHolder.setLine(
            "${parcelName.value.ifBlank { "Thửa" }} — đường bao",
            pts,
            featureId = parcelHolder.featureId.value
        )
    }

    fun clear() = parcelHolder.clear()
}
