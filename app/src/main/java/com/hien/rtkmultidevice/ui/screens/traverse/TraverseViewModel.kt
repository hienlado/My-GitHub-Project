package com.hien.rtkmultidevice.ui.screens.traverse

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.export.ExportManager
import com.hien.rtkmultidevice.export.PointFileFormat
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.Traverse
import com.hien.rtkmultidevice.domain.model.TraversePoint
import com.hien.rtkmultidevice.domain.repository.IProjectRepository
import com.hien.rtkmultidevice.domain.repository.ITraverseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TraverseViewModel @Inject constructor(
    savedStateHandle     : SavedStateHandle,
    private val gnssManager     : GnssDataManager,
    private val traverseRepo    : ITraverseRepository,
    private val projectRepo     : IProjectRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val projectId: Int = savedStateHandle["projectId"] ?: -1

    // ── Live GNSS ──────────────────────────────────────────
    val gnssStatus: StateFlow<GnssStatus> = gnssManager.gnssStatus

    // ── Tất cả tuyến của dự án ─────────────────────────────
    val traverses: StateFlow<List<Traverse>> = traverseRepo
        .getTraversesByProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Tuyến đang mở/chỉnh sửa ────────────────────────────
    private val _activeTraverse = MutableStateFlow<Traverse?>(null)
    val activeTraverse: StateFlow<Traverse?> = _activeTraverse.asStateFlow()

    // ── Form state ─────────────────────────────────────────
    private val _traverseName = MutableStateFlow("")
    val traverseName: StateFlow<String> = _traverseName.asStateFlow()

    private val _pointCode = MutableStateFlow("")
    val pointCode: StateFlow<String> = _pointCode.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Actions ────────────────────────────────────────────

    fun onTraverseNameChange(v: String) { _traverseName.value = v }
    fun onPointCodeChange(v: String)    { _pointCode.value = v.uppercase().take(20) }

    /** Tạo tuyến mới */
    fun createTraverse(name: String, isClosed: Boolean = false) {
        if (name.isBlank()) { _error.value = "Tên tuyến không được để trống"; return }
        viewModelScope.launch {
            val t = Traverse(
                projectId = projectId,
                name      = name.trim(),
                isClosed  = isClosed
            )
            val id = traverseRepo.createTraverse(t)
            _activeTraverse.value = traverseRepo.getTraverseById(id)
            _traverseName.value = ""
            _pointCode.value    = nextPointCode()
            _feedback.value = "Đã tạo tuyến \"${name.trim()}\""
        }
    }

    /** Mở tuyến đã có để thêm điểm */
    fun openTraverse(traverse: Traverse) {
        _activeTraverse.value = traverse
        _pointCode.value = nextPointCode(traverse)
    }

    /** Đóng tuyến — quay về danh sách */
    fun closeTraverse() { _activeTraverse.value = null }

    /** Toggle tuyến mở/đóng */
    fun toggleClosed() {
        val t = _activeTraverse.value ?: return
        viewModelScope.launch {
            val updated = t.copy(isClosed = !t.isClosed, updatedAt = System.currentTimeMillis())
            traverseRepo.updateTraverse(updated)
            _activeTraverse.value = traverseRepo.getTraverseById(t.id)
        }
    }

    /** Đo và thêm điểm vào tuyến đang mở */
    fun addPoint() {
        val traverse = _activeTraverse.value ?: run {
            _error.value = "Chưa mở tuyến đo"; return
        }
        val gnss = gnssStatus.value
        if (!gnss.hasFix) { _error.value = "Chưa có tín hiệu GNSS"; return }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val code  = _pointCode.value.ifEmpty { nextPointCode(traverse) }
                val order = traverseRepo.getNextOrderIndex(traverse.id)
                val vn    = gnss.vn2000

                val point = TraversePoint(
                    traverseId      = traverse.id,
                    orderIndex      = order,
                    pointCode       = code,
                    latitude        = gnss.latitude,
                    longitude       = gnss.longitude,
                    altitude        = gnss.altitude,
                    geoidSep        = gnss.geoidSeparation,
                    northing        = vn?.northing ?: 0.0,
                    easting         = vn?.easting  ?: 0.0,
                    centralMeridian = vn?.centralMeridian ?: 0.0,
                    zoneWidthDeg    = vn?.zoneWidthDeg    ?: 3,
                    fixQuality      = gnss.fixQuality,
                    hdop            = gnss.hdop,
                    satelliteCount  = gnss.satelliteCount
                )
                traverseRepo.addPoint(traverse.id, point)

                // Refresh active traverse
                _activeTraverse.value = traverseRepo.getTraverseById(traverse.id)
                _feedback.value = "Đã thêm điểm $code (${gnss.fixLabel})"
                _pointCode.value = nextPointCode(_activeTraverse.value)

            } catch (e: Exception) {
                _error.value = "Lỗi: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    /** Xoá điểm cuối cùng trong tuyến */
    fun removeLastPoint() {
        val traverse = _activeTraverse.value ?: return
        val lastPoint = traverse.points.lastOrNull() ?: return
        viewModelScope.launch {
            traverseRepo.deletePoint(lastPoint)
            _activeTraverse.value = traverseRepo.getTraverseById(traverse.id)
            _feedback.value = "Đã xoá điểm ${lastPoint.pointCode}"
        }
    }

    /** Xoá toàn bộ tuyến */
    fun deleteTraverse(traverse: Traverse) {
        viewModelScope.launch {
            traverseRepo.deleteTraverse(traverse)
            if (_activeTraverse.value?.id == traverse.id) _activeTraverse.value = null
        }
    }

    fun clearFeedback() { _feedback.value = null }
    fun clearError()    { _error.value = null }

    // ── Export tuyến ra .csv/.txt ──────────────────────────

    private val _exportResult = MutableStateFlow<ExportManager.ExportResult?>(null)
    val exportResult: StateFlow<ExportManager.ExportResult?> = _exportResult.asStateFlow()

    /** Xuất tuyến ra file theo định dạng linh hoạt (thứ tự trường, phân cách, csv/txt) */
    fun exportTraverse(traverse: Traverse, fmt: PointFileFormat) {
        viewModelScope.launch {
            // Lấy bản đầy đủ từ DB (danh sách tuyến có thể chưa load points)
            val full = traverseRepo.getTraverseById(traverse.id) ?: traverse
            _exportResult.value = ExportManager.exportTraverse(context, full, fmt)
        }
    }

    /** Tạo share Intent từ kết quả export thành công */
    fun createShareIntent(result: ExportManager.ExportResult.Success): Intent =
        ExportManager.createShareIntent(
            context, result.uri,
            if (result.fileName.endsWith(".csv")) "text/csv" else "text/plain"
        )

    fun clearExportResult() { _exportResult.value = null }

    // ── Helpers ───────────────────────────────────────────

    private fun nextPointCode(traverse: Traverse? = _activeTraverse.value): String {
        val t = traverse ?: return "T01"
        val count = t.pointCount + 1
        return "%s%02d".format(Locale.US, t.name.take(2).uppercase(), count)
    }
}
