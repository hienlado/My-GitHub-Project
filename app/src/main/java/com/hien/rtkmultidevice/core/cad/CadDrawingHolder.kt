package com.hien.rtkmultidevice.core.cad

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot

/**
 * CadDrawingHolder — trạng thái VẼ CAD DÙNG CHUNG toàn app (singleton, độc lập màn hình).
 *
 * Bất kỳ màn nào (Định vị CAD, Đo điểm, Cắm mốc) chỉ cần:
 *   - hiển thị CadDrawPanel để điều khiển,
 *   - gọi renderCadOverlay()/ensureCadTapOverlay() trong update block của MapView.
 * Không phụ thuộc ViewModel/logic của màn. Bản vẽ giữ nguyên khi chuyển màn.
 */
object CadDrawingHolder {

    val drawing = CadDrawing()

    // rev tăng mỗi khi bản vẽ đổi -> kích hoạt vẽ lại / recompose.
    private val _rev = MutableStateFlow(0)
    val rev: StateFlow<Int> = _rev
    private fun bump() { _rev.value += 1 }

    private val _mode = MutableStateFlow(false)          // đang ở chế độ vẽ?
    val drawMode: StateFlow<Boolean> = _mode
    private val _tool = MutableStateFlow(CadType.POLYGON)
    val tool: StateFlow<CadType> = _tool
    private val _layer = MutableStateFlow("0")
    val layer: StateFlow<String> = _layer
    private val _inProgress = MutableStateFlow<List<CadVertex>>(emptyList())
    val inProgress: StateFlow<List<CadVertex>> = _inProgress
    private val _snap = MutableStateFlow(true)
    val snapEnabled: StateFlow<Boolean> = _snap

    /** Điểm để BẮT (snap): điểm đo/đỉnh thửa/điểm import (VN-2000). Màn hình nạp vào. */
    @Volatile var snapPoints: List<CadVertex> = emptyList()

    fun setMode(on: Boolean) { _mode.value = on; if (!on) cancelInProgress() }
    fun toggleMode() = setMode(!_mode.value)
    fun setTool(t: CadType) { _tool.value = t; cancelInProgress() }
    fun setLayer(name: String) { _layer.value = name; drawing.ensureLayer(name); bump() }
    fun setSnap(on: Boolean) { _snap.value = on }

    /** Thêm 1 đỉnh (đã ở VN-2000). POINT: tạo luôn 1 đối tượng. */
    fun addVertex(v: CadVertex) {
        val p = if (_snap.value) snap(v) else v
        if (_tool.value == CadType.POINT) {
            drawing.add(CadType.POINT, listOf(p), _layer.value)
        } else {
            _inProgress.value = _inProgress.value + p
        }
        bump()
    }

    /** Bắt đỉnh vào điểm gần nhất (< ngưỡng 0.3 m) trong snapPoints hoặc đỉnh đã vẽ. */
    private fun snap(v: CadVertex): CadVertex {
        val cand = snapPoints.asSequence() + drawing.entities.asSequence().flatMap { it.vertices.asSequence() }
        var best: CadVertex? = null; var bestD = 0.30   // 30 cm
        for (c in cand) {
            val d = hypot(c.n - v.n, c.e - v.e)
            if (d < bestD) { bestD = d; best = c }
        }
        return best ?: v
    }

    fun undoVertex() {
        if (_inProgress.value.isNotEmpty()) { _inProgress.value = _inProgress.value.dropLast(1); bump() }
    }

    /** Hoàn tất đối tượng đang vẽ (đường ≥2 đỉnh, vùng ≥3 đỉnh). */
    fun finish(label: String = ""): Boolean {
        val vs = _inProgress.value
        val ok = when (_tool.value) {
            CadType.LINE -> vs.size >= 2
            CadType.POLYGON -> vs.size >= 3
            CadType.POINT -> false
        }
        if (!ok) return false
        drawing.add(_tool.value, vs, _layer.value, label)
        _inProgress.value = emptyList(); bump()
        return true
    }

    fun cancelInProgress() { _inProgress.value = emptyList(); bump() }

    fun removeLastEntity() {
        drawing.entities.lastOrNull()?.let { drawing.removeEntity(it.id); bump() }
    }

    fun clearAll() { drawing.clear(); _inProgress.value = emptyList(); bump() }
}
