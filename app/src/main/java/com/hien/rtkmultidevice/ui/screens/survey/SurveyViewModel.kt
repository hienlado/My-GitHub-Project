package com.hien.rtkmultidevice.ui.screens.survey

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.data.datastore.AppSettings
import com.hien.rtkmultidevice.domain.model.AveragingSession
import com.hien.rtkmultidevice.domain.model.EpochSample
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.Project
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.hien.rtkmultidevice.domain.repository.IProjectRepository
import com.hien.rtkmultidevice.domain.repository.ISurveyPointRepository
import com.hien.rtkmultidevice.export.ExportManager
import com.hien.rtkmultidevice.export.PointFileCodec
import com.hien.rtkmultidevice.export.PointFileFormat
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerHolder
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
import com.hien.rtkmultidevice.ui.screens.stakeout.StakeoutTargetHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SurveyViewModel — Quản lý logic màn hình thu thập điểm đo RTK.
 *
 * Luồng hoạt động:
 *   1. Nhận projectId từ SavedStateHandle (navigation argument)
 *   2. Observe GnssStatus live từ GnssDataManager
 *   3. Observe Project (tên, nextPointCode) từ repository
 *   4. User nhấn "Lưu" → tạo SurveyPoint từ gnss hiện tại → lưu DB
 *   5. Tự động tăng mã điểm (P001 → P002 → ...) sau mỗi lần lưu
 */
@HiltViewModel
class SurveyViewModel @Inject constructor(
    savedStateHandle           : SavedStateHandle,
    private val gnssManager    : GnssDataManager,
    private val projectRepo    : IProjectRepository,
    private val surveyRepo     : ISurveyPointRepository,
    private val stakeHolder    : StakeoutTargetHolder,
    private val vectorLayerHolder: VectorLayerHolder,
    private val appSettings    : AppSettings,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── Cài đặt thu thập điểm: âm báo fix + ràng buộc lưu ──────
    val surveySettings: StateFlow<AppSettings.SurveySettings> = appSettings.surveySettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.SurveySettings())

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setSurveySoundEnabled(enabled) }
    }

    fun setRequireFixed(required: Boolean) {
        viewModelScope.launch { appSettings.setSurveyRequireFixed(required) }
    }

    /** projectId được truyền qua navigation argument "survey/{projectId}" */
    val projectId: Int = savedStateHandle["projectId"] ?: -1

    // ── Live GNSS ──────────────────────────────────────────────
    val gnssStatus: StateFlow<GnssStatus> = gnssManager.gnssStatus

    /**
     * Feature CAD đang được chọn để stakeout (chia sẻ từ Stakeout/Map qua holder)
     * — bản đồ Survey highlight đối tượng + hiện nhãn số hiệu đỉnh (Bảng toạ độ).
     */
    val stakeFeatureId: StateFlow<Int?> = stakeHolder.activeFeatureId

    /**
     * Layer CAD (DXF/SHP) DÙNG CHUNG giữa các màn hình (Map/Stakeout/Survey).
     * Import ở màn hình nào cũng thấy ở màn hình khác — gồm cả đối tượng
     * đang stakeout để hiện nhãn số hiệu đỉnh trên bản đồ Survey.
     */
    val sharedVectorLayer: StateFlow<VectorLayerImporter.VectorLayer?> = vectorLayerHolder.layer

    fun setSharedVectorLayer(layer: VectorLayerImporter.VectorLayer) = vectorLayerHolder.set(layer)

    // ── Dự án hiện tại ─────────────────────────────────────────
    val project: StateFlow<Project?> = projectRepo.observeProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Danh sách điểm đã lưu ──────────────────────────────────
    val savedPoints: StateFlow<List<SurveyPoint>> = surveyRepo.getPointsByProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Form state ─────────────────────────────────────────────
    private val _pointCode = MutableStateFlow("")
    val pointCode: StateFlow<String> = _pointCode.asStateFlow()

    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * Mã điểm vừa lưu thành công.
     * UI dùng để hiển thị snackbar/feedback rồi gọi clearSavedFeedback().
     */
    private val _savedFeedback = MutableStateFlow<String?>(null)
    val savedFeedback: StateFlow<String?> = _savedFeedback.asStateFlow()

    // ── Error ──────────────────────────────────────────────────
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Lấy nextPointCode từ dự án khi lần đầu load
        viewModelScope.launch {
            val proj = project.filterNotNull().first()
            if (_pointCode.value.isEmpty()) {
                _pointCode.value = proj.nextPointCode
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────

    fun onPointCodeChange(v: String) { _pointCode.value = v.uppercase().take(20) }
    fun onNoteChange(v: String)      { _note.value = v }

    /**
     * Lưu điểm đo hiện tại vào cơ sở dữ liệu.
     *
     * Điều kiện: phải có fix (fixQuality > 0).
     * Sau khi lưu: tự động tăng nextPointIndex, tạo mã điểm mới.
     */
    fun savePoint() {
        val gnss = gnssStatus.value
        val proj = project.value ?: return

        if (!gnss.hasFix) {
            _error.value = "Chưa có tín hiệu GPS — không thể lưu điểm"
            return
        }

        // Ràng buộc: nếu bật "chỉ lưu khi FIXED" mà chưa đạt FIXED (4) → chặn
        if (surveySettings.value.requireFixed && gnss.fixQuality != 4) {
            _error.value = "Chỉ cho lưu điểm khi đạt RTK FIXED. " +
                           "Hiện tại: ${gnss.fixLabel}. Tắt ràng buộc trong Cài đặt nếu cần."
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val vn   = gnss.vn2000
                val code = _pointCode.value.ifEmpty { proj.nextPointCode }

                // Kiểm tra mã điểm trùng
                if (surveyRepo.isCodeExists(projectId, code)) {
                    _error.value = "Mã điểm \"$code\" đã tồn tại trong dự án"
                    return@launch
                }

                val point = SurveyPoint(
                    projectId       = projectId,
                    pointCode       = code,
                    latitude        = gnss.latitude,
                    longitude       = gnss.longitude,
                    altitude        = gnss.altitude,
                    geoidSeparation = gnss.geoidSeparation,
                    northing        = vn?.northing ?: 0.0,
                    easting         = vn?.easting  ?: 0.0,
                    centralMeridian = vn?.centralMeridian ?: proj.centralMeridian,
                    zoneWidthDeg    = vn?.zoneWidthDeg    ?: proj.zoneWidthDeg,
                    fixQuality      = gnss.fixQuality,
                    hdop            = gnss.hdop,
                    pdop            = gnss.pdop,
                    satelliteCount  = gnss.satelliteCount,
                    note            = _note.value.trim()
                )

                // LOG để debug Stakeout distance issue
                android.util.Log.d("SurveyVM",
                    "savePoint: $code lat=${gnss.latitude} lon=${gnss.longitude} " +
                    "N=${point.northing} E=${point.easting} " +
                    "vn2000=${gnss.vn2000 != null} fix=${gnss.fixQuality}"
                )
                surveyRepo.savePoint(point)
                projectRepo.incrementPointIndex(projectId)

                // Thông báo lưu thành công
                _savedFeedback.value = code

                // Tạo mã điểm tiếp theo
                val updated = projectRepo.getProjectById(projectId)
                _pointCode.value = updated?.nextPointCode ?: ""
                _note.value = ""

            } catch (e: Exception) {
                _error.value = "Lỗi lưu điểm: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deletePoint(point: SurveyPoint) {
        viewModelScope.launch { surveyRepo.deletePoint(point) }
    }

    // ─────────────────────────────────────────────────────────
    // Quản lý danh sách điểm (Add / Edit / Delete nhiều / Đảo XY)
    // ─────────────────────────────────────────────────────────

    /**
     * Thêm điểm thủ công bằng toạ độ VN-2000.
     * Lat/Lon tính ngược qua Gauss-Krüger để hiển thị được trên bản đồ.
     */
    fun addManualPoint(code: String, northing: Double, easting: Double, elevation: Double, note: String) {
        viewModelScope.launch {
            try {
                val finalCode = code.trim().uppercase().take(20)
                if (finalCode.isEmpty()) { _error.value = "Mã điểm trống"; return@launch }
                if (surveyRepo.isCodeExists(projectId, finalCode)) {
                    _error.value = "Mã điểm \"$finalCode\" đã tồn tại"; return@launch
                }
                val proj = project.value
                val cm   = proj?.centralMeridian ?: 105.0
                val geo  = VectorLayerImporter.inverseVn2000(northing, easting, cm)
                surveyRepo.savePoint(SurveyPoint(
                    projectId       = projectId,
                    pointCode       = finalCode,
                    latitude        = geo?.latitude  ?: 0.0,
                    longitude       = geo?.longitude ?: 0.0,
                    altitude        = elevation,
                    northing        = northing,
                    easting         = easting,
                    centralMeridian = cm,
                    zoneWidthDeg    = proj?.zoneWidthDeg ?: 3,
                    fixQuality      = 0,
                    hdop            = 0.0,
                    satelliteCount  = 0,
                    note            = note.trim()
                ))
                _savedFeedback.value = "$finalCode (nhập tay)"
            } catch (e: Exception) {
                _error.value = "Lỗi thêm điểm: ${e.message}"
            }
        }
    }

    /** Cập nhật điểm (Properties): sửa mã, toạ độ, độ cao, ghi chú */
    fun updatePointEdited(point: SurveyPoint, code: String, northing: Double, easting: Double, elevation: Double, note: String) {
        viewModelScope.launch {
            try {
                val finalCode = code.trim().uppercase().take(20)
                if (finalCode.isEmpty()) { _error.value = "Mã điểm trống"; return@launch }
                if (finalCode != point.pointCode && surveyRepo.isCodeExists(projectId, finalCode)) {
                    _error.value = "Mã điểm \"$finalCode\" đã tồn tại"; return@launch
                }
                val coordChanged = northing != point.northing || easting != point.easting
                val cm = point.centralMeridian.takeIf { it > 0.0 }
                    ?: project.value?.centralMeridian ?: 105.0
                val geo = if (coordChanged) VectorLayerImporter.inverseVn2000(northing, easting, cm) else null
                surveyRepo.updatePoint(point.copy(
                    pointCode = finalCode,
                    northing  = northing,
                    easting   = easting,
                    altitude  = elevation,
                    latitude  = geo?.latitude  ?: point.latitude,
                    longitude = geo?.longitude ?: point.longitude,
                    note      = note.trim()
                ))
                _savedFeedback.value = "$finalCode (đã cập nhật)"
            } catch (e: Exception) {
                _error.value = "Lỗi cập nhật: ${e.message}"
            }
        }
    }

    /** Xoá nhiều điểm đã chọn */
    fun deletePoints(points: List<SurveyPoint>) {
        if (points.isEmpty()) return
        viewModelScope.launch {
            try {
                points.forEach { surveyRepo.deletePoint(it) }
                _savedFeedback.value = "Đã xoá ${points.size} điểm"
            } catch (e: Exception) {
                _error.value = "Lỗi xoá: ${e.message}"
            }
        }
    }

    /**
     * Đảo X↔Y (Northing↔Easting) cho các điểm chọn.
     * Dùng khi file import có thứ tự trường ngược với quy ước.
     * Lat/Lon được tính lại theo toạ độ mới.
     */
    fun swapXY(points: List<SurveyPoint>) {
        if (points.isEmpty()) return
        viewModelScope.launch {
            try {
                val defaultCm = project.value?.centralMeridian ?: 105.0
                points.forEach { p ->
                    val cm  = p.centralMeridian.takeIf { it > 0.0 } ?: defaultCm
                    val geo = VectorLayerImporter.inverseVn2000(p.easting, p.northing, cm)
                    surveyRepo.updatePoint(p.copy(
                        northing  = p.easting,
                        easting   = p.northing,
                        latitude  = geo?.latitude  ?: p.latitude,
                        longitude = geo?.longitude ?: p.longitude
                    ))
                }
                _savedFeedback.value = "Đã đảo X↔Y ${points.size} điểm"
            } catch (e: Exception) {
                _error.value = "Lỗi đảo XY: ${e.message}"
            }
        }
    }

    /**
     * Import điểm từ file CSV/TXT theo định dạng người dùng chọn
     * (thứ tự trường + dấu phân cách). Điểm trùng mã bị bỏ qua.
     */
    fun importPointsFlexible(uri: Uri, fmt: PointFileFormat) {
        viewModelScope.launch {
            try {
                val lines = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readLines()
                    ?: run { _error.value = "Không đọc được file"; return@launch }

                val parsed = PointFileCodec.parse(lines, fmt)
                if (parsed.isEmpty()) {
                    _error.value = "Không tìm thấy điểm hợp lệ — kiểm tra thứ tự trường (${fmt.orderLabel}) và dấu phân cách"
                    return@launch
                }

                val proj = project.value
                val cm   = proj?.centralMeridian ?: 105.0
                var added = 0
                parsed.forEach { pp ->
                    val code = pp.pointId.trim().uppercase().take(20)
                    if (code.isEmpty() || surveyRepo.isCodeExists(projectId, code)) return@forEach
                    val geo  = VectorLayerImporter.inverseVn2000(pp.northing, pp.easting, cm)
                    val note = listOf(pp.code, pp.comment).filter { it.isNotBlank() }.joinToString(" | ")
                    surveyRepo.savePoint(SurveyPoint(
                        projectId       = projectId,
                        pointCode       = code,
                        latitude        = geo?.latitude  ?: 0.0,
                        longitude       = geo?.longitude ?: 0.0,
                        altitude        = pp.elevation,
                        northing        = pp.northing,
                        easting         = pp.easting,
                        centralMeridian = cm,
                        zoneWidthDeg    = proj?.zoneWidthDeg ?: 3,
                        fixQuality      = 0,
                        hdop            = 0.0,
                        satelliteCount  = 0,
                        note            = note.ifBlank { "[Import]" }
                    ))
                    added++
                }
                _savedFeedback.value = "Import $added/${parsed.size} điểm"
            } catch (e: Exception) {
                _error.value = "Lỗi import: ${e.message}"
            }
        }
    }

    /**
     * Export theo định dạng linh hoạt.
     * @param selected null = xuất tất cả; khác null = chỉ xuất điểm đã chọn
     */
    fun exportPointsFlexible(fmt: PointFileFormat, selected: List<SurveyPoint>? = null) {
        val proj = project.value ?: run {
            _exportResult.value = ExportManager.ExportResult.Error("Chưa tải được thông tin dự án")
            return
        }
        viewModelScope.launch {
            _isExporting.value = true
            val pts = selected ?: surveyRepo.getPointsByProject(projectId).first()
            _exportResult.value = ExportManager.exportFlexible(context, proj, pts, fmt)
            _isExporting.value = false
        }
    }

    fun clearSavedFeedback() { _savedFeedback.value = null }
    fun clearError()         { _error.value = null }

    // ─────────────────────────────────────────────────────────
    // Trung bình hoá đo điểm (Average Position)
    // ─────────────────────────────────────────────────────────

    private val _averagingSession = MutableStateFlow<AveragingSession?>(null)
    val averagingSession: StateFlow<AveragingSession?> = _averagingSession.asStateFlow()

    private val _isAveraging = MutableStateFlow(false)
    val isAveraging: StateFlow<Boolean> = _isAveraging.asStateFlow()

    /** Số epoch mục tiêu — người dùng có thể chỉnh */
    private val _targetEpochs = MutableStateFlow(30)
    val targetEpochs: StateFlow<Int> = _targetEpochs.asStateFlow()

    private var averagingJob: Job? = null

    fun setTargetEpochs(n: Int) { _targetEpochs.value = n.coerceIn(5, 300) }

    /**
     * Bắt đầu phiên đo trung bình.
     * Thu thập mẫu mỗi giây cho đến khi đủ [targetEpochs] hoặc user dừng thủ công.
     * Chỉ thu mẫu khi có fix (fixQuality > 0).
     */
    fun startAveraging() {
        if (_isAveraging.value) return
        _isAveraging.value = true
        _averagingSession.value = AveragingSession(targetEpochs = _targetEpochs.value)

        averagingJob = viewModelScope.launch {
            while (isActive) {
                val gnss = gnssStatus.value
                if (gnss.hasFix) {
                    val sample = EpochSample(
                        latitude       = gnss.latitude,
                        longitude      = gnss.longitude,
                        altitude       = gnss.altitude,
                        northing       = gnss.vn2000?.northing ?: 0.0,
                        easting        = gnss.vn2000?.easting  ?: 0.0,
                        fixQuality     = gnss.fixQuality,
                        hdop           = gnss.hdop,
                        satelliteCount = gnss.satelliteCount
                    )
                    val current = _averagingSession.value ?: break
                    val updated = current.copy(samples = current.samples + sample)
                    _averagingSession.value = updated

                    // Tự dừng khi đủ epoch mục tiêu
                    if (updated.isComplete) break
                }
                delay(1_000L)  // 1 mẫu/giây
            }
            _isAveraging.value = false
        }
    }

    /** Dừng thu mẫu (thủ công hoặc tự động sau khi đủ epoch) */
    fun stopAveraging() {
        averagingJob?.cancel()
        averagingJob = null
        _isAveraging.value = false
    }

    /**
     * Lưu điểm từ kết quả trung bình hoá.
     * Sử dụng giá trị trung bình thay vì vị trí tức thời.
     */
    fun saveAveragedPoint() {
        val session = _averagingSession.value ?: run {
            _error.value = "Chưa có dữ liệu trung bình"
            return
        }
        if (session.count < 2) {
            _error.value = "Cần ít nhất 2 epoch để tính trung bình"
            return
        }
        // Ràng buộc chỉ-FIXED áp dụng cho cả điểm trung bình (theo fix chủ đạo)
        if (surveySettings.value.requireFixed && session.dominantFixQuality != 4) {
            _error.value = "Chỉ cho lưu điểm khi đạt RTK FIXED. " +
                           "Phiên trung bình chủ yếu ở trạng thái khác FIXED."
            return
        }
        val proj = project.value ?: return

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val code = _pointCode.value.ifEmpty { proj.nextPointCode }
                if (surveyRepo.isCodeExists(projectId, code)) {
                    _error.value = "Mã điểm \"$code\" đã tồn tại"
                    return@launch
                }
                // Lưu vn2000 từ trung bình (nếu có), fallback về WGS-84 convert
                val vn = gnssStatus.value.vn2000
                val point = SurveyPoint(
                    projectId       = projectId,
                    pointCode       = code,
                    latitude        = session.meanLatitude,
                    longitude       = session.meanLongitude,
                    altitude        = session.meanAltitude,
                    geoidSeparation = gnssStatus.value.geoidSeparation,
                    northing        = if (session.meanNorthing != 0.0) session.meanNorthing else vn?.northing ?: 0.0,
                    easting         = if (session.meanEasting  != 0.0) session.meanEasting  else vn?.easting  ?: 0.0,
                    centralMeridian = vn?.centralMeridian ?: proj.centralMeridian,
                    zoneWidthDeg    = vn?.zoneWidthDeg    ?: proj.zoneWidthDeg,
                    fixQuality      = session.dominantFixQuality,
                    hdop            = session.meanHdop,
                    pdop            = gnssStatus.value.pdop,
                    satelliteCount  = gnssStatus.value.satelliteCount,
                    note            = buildString {
                        append(_note.value.trim())
                        if (isNotEmpty()) append(" | ")
                        append("Avg ${session.count}ep RMSE=${session.rmse2DFormatted}")
                    }
                )
                android.util.Log.d("SurveyVM",
                    "saveAveragedPoint: $code N=${point.northing} E=${point.easting} " +
                    "epochs=${session.count} RMSE=${session.rmse2DFormatted}")
                surveyRepo.savePoint(point)
                projectRepo.incrementPointIndex(projectId)
                _savedFeedback.value = "$code (TB ${session.count} epoch)"

                // Reset sau khi lưu
                _averagingSession.value = null
                val updated = projectRepo.getProjectById(projectId)
                _pointCode.value = updated?.nextPointCode ?: ""
                _note.value = ""

            } catch (e: Exception) {
                _error.value = "Lỗi lưu điểm: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    /** Huỷ phiên đo trung bình, không lưu */
    fun cancelAveraging() {
        stopAveraging()
        _averagingSession.value = null
    }

    // ─────────────────────────────────────────────────────────
    // Export
    // ─────────────────────────────────────────────────────────

    /** Kết quả export: null = chưa export, Success/Error */
    private val _exportResult = MutableStateFlow<ExportManager.ExportResult?>(null)
    val exportResult: StateFlow<ExportManager.ExportResult?> = _exportResult.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    /**
     * Xuất toàn bộ điểm của dự án ra file.
     * @param format CSV_FULL hoặc TXT_FIELD
     */
    fun exportPoints(format: ExportManager.Format = ExportManager.Format.CSV_FULL) {
        val proj = project.value ?: run {
            _exportResult.value = ExportManager.ExportResult.Error("Chưa tải được thông tin dự án")
            return
        }
        viewModelScope.launch {
            _isExporting.value = true
            val points = surveyRepo.getPointsByProject(projectId).first()
            _exportResult.value = ExportManager.export(context, proj, points, format)
            _isExporting.value = false
        }
    }

    /** Tạo share Intent từ kết quả export thành công */
    fun createShareIntent(result: ExportManager.ExportResult.Success): Intent =
        ExportManager.createShareIntent(
            context, result.uri,
            // Dùng fileName (URI MediaStore không chứa đuôi file)
            if (result.fileName.endsWith(".csv")) "text/csv" else "text/plain"
        )

    fun clearExportResult() { _exportResult.value = null }
}
