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

    /**
     * Khoá các tờ ĐÃ nạp ("slug/số tờ") — chống nạp trùng.
     *
     * Vì sao cần: trước đây add() luôn nối thêm, nên bấm "tôi đang ở thửa nào"
     * nhiều lần sẽ gộp CÙNG MỘT TỜ nhiều lần → số đối tượng nhân lên → bản đồ
     * vẽ lại hàng nghìn vùng mỗi lần → giật lag.
     */
    private val _loadedKeys = MutableStateFlow<Set<String>>(emptySet())
    val loadedKeys: StateFlow<Set<String>> = _loadedKeys.asStateFlow()

    /** Bộ đếm id chạy tiếp — KHÔNG đánh lại id toàn bộ mỗi lần thêm tờ. */
    private var nextId = 0

    fun isLoaded(key: String): Boolean = key in _loadedKeys.value

    fun set(layer: VectorLayerImporter.VectorLayer) {
        _layer.value = layer
        _loadedKeys.value = emptySet()
        nextId = layer.features.size
    }

    /**
     * Gộp thêm 1 tờ. Trả false nếu tờ đã có (không làm gì).
     * Chỉ đánh id cho các đối tượng MỚI — tránh copy lại toàn bộ layer cũ.
     */
    fun addSheet(key: String, newLayer: VectorLayerImporter.VectorLayer): Boolean {
        if (key in _loadedKeys.value) return false
        val cur = _layer.value
        val shifted = newLayer.features.map { it.copy(id = nextId++) }
        _layer.value = if (cur == null)
            newLayer.copy(features = shifted)
        else
            cur.copy(
                features = cur.features + shifted,
                name = "Nhiều tờ (${cur.features.size + shifted.size} đối tượng)"
            )
        _loadedKeys.value = _loadedKeys.value + key
        return true
    }

    /** Giữ lại cho các nguồn không có khoá tờ (import DXF/SHP). */
    fun add(newLayer: VectorLayerImporter.VectorLayer) {
        addSheet("adhoc-${nextId}-${newLayer.features.size}", newLayer)
    }

    fun clear() {
        _layer.value = null
        _loadedKeys.value = emptySet()
        nextId = 0
    }
}
