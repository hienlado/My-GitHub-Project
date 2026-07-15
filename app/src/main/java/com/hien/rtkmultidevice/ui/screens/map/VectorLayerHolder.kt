package com.hien.rtkmultidevice.ui.screens.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VectorLayerHolder — Giữ layer CAD (DXF/SHP) DÙNG CHUNG giữa các màn hình.
 *
 * Lý do tồn tại:
 *   Trước đây layer import là state riêng của từng màn hình (Định vị CAD,
 *   Cắm mốc) → khi chuyển từ CAD sang Stakeout, bản vẽ "biến mất" làm user
 *   mất kiểm soát điều hướng. Giữ layer trong singleton để:
 *     • Import ở màn hình nào cũng thấy ở màn hình khác
 *     • Layer chỉ unload khi USER chủ động bấm ✕ trên badge
 *     • Căn chỉnh toạ độ (rezone/offset) áp dụng đồng bộ mọi nơi
 */
@Singleton
class VectorLayerHolder @Inject constructor() {

    private val _layer = MutableStateFlow<VectorLayerImporter.VectorLayer?>(null)
    val layer: StateFlow<VectorLayerImporter.VectorLayer?> = _layer.asStateFlow()

    fun set(layer: VectorLayerImporter.VectorLayer) {
        _layer.value = layer
    }

    /** Gộp thêm 1 tờ vào layer hiện có (mở nhiều tờ cùng lúc). Đánh lại id để không trùng. */
    fun add(newLayer: VectorLayerImporter.VectorLayer) {
        val cur = _layer.value
        if (cur == null) { _layer.value = newLayer; return }
        val merged = (cur.features + newLayer.features)
            .mapIndexed { i, f -> f.copy(id = i) }
        _layer.value = cur.copy(features = merged, name = "Nhiều tờ (${merged.size} đối tượng)")
    }

    fun clear() {
        _layer.value = null
    }
}
