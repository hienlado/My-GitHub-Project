package com.hien.rtkmultidevice.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.Project
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import com.hien.rtkmultidevice.domain.repository.IProjectRepository
import com.hien.rtkmultidevice.domain.repository.ISurveyPointRepository
import com.hien.rtkmultidevice.ui.screens.stakeout.StakeoutTargetHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MapViewModel — Dữ liệu cho màn hình bản đồ OSM.
 *
 * Cung cấp:
 *   - Vị trí GNSS live để cập nhật marker "You Are Here"
 *   - Danh sách điểm đã lưu của dự án để vẽ markers lên bản đồ
 *   - Thông tin dự án hiện tại
 *   - Khả năng lưu điểm đo ngay từ màn hình bản đồ (FAB "Đo điểm")
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val gnssManager : GnssDataManager,
    private val projectRepo : IProjectRepository,
    private val surveyRepo  : ISurveyPointRepository,
    private val stakeoutTargetHolder: StakeoutTargetHolder,
    private val vectorLayerHolder   : VectorLayerHolder
) : ViewModel() {

    /**
     * Layer CAD dùng chung (VectorLayerHolder) — import ở đây thì màn hình
     * Cắm mốc cũng thấy, và ngược lại. Chỉ unload khi user bấm ✕.
     */
    val vectorLayer: kotlinx.coroutines.flow.StateFlow<VectorLayerImporter.VectorLayer?> =
        vectorLayerHolder.layer

    fun setVectorLayer(layer: VectorLayerImporter.VectorLayer) = vectorLayerHolder.set(layer)
    fun clearVectorLayer() = vectorLayerHolder.clear()

    // ── Tải bản đồ địa chính theo TỜ từ Cloud (GCS) ──────────────────────
    private val _cloudLoading = MutableStateFlow(false)
    val cloudLoading: StateFlow<Boolean> = _cloudLoading.asStateFlow()

    private val _cloudMessage = MutableStateFlow<String?>(null)
    val cloudMessage: StateFlow<String?> = _cloudMessage.asStateFlow()

    fun clearCloudMessage() { _cloudMessage.value = null }

    // Thửa cần điều hướng tới sau khi tải tờ (để highlight/định vị). null = không.
    private val _targetThua = MutableStateFlow<String?>(null)
    val targetThua: StateFlow<String?> = _targetThua.asStateFlow()
    fun clearTargetThua() { _targetThua.value = null }

    // ── "Tôi đang ở thửa nào?" (tra ngược điểm RTK -> xã/tờ/thửa) ──
    private val _whereResult = MutableStateFlow<CadastralCloudSource.WhereResult?>(null)
    val whereResult: StateFlow<CadastralCloudSource.WhereResult?> = _whereResult.asStateFlow()
    fun clearWhereResult() { _whereResult.value = null }

    fun whereAmINow(lat: Double, lon: Double) {
        if ((lat == 0.0 && lon == 0.0) || lat.isNaN() || lon.isNaN()) {
            _cloudMessage.value = "Chưa có định vị RTK"
            return
        }
        viewModelScope.launch {
            _cloudLoading.value = true
            val vn = com.hien.rtkmultidevice.core.coordinate.Vn2000Converter
                .convert(lat, lon, 0.0, CadastralCloudSource.CENTRAL_MERIDIAN)
            if (vn == null) _cloudMessage.value = "Không đổi được toạ độ VN-2000"
            else _whereResult.value = CadastralCloudSource.whereAmI(vn.easting, vn.northing)
            _cloudLoading.value = false
        }
    }

    /** Tra theo toạ độ VN-2000 nhập tay (x=Easting, y=Northing). */
    fun whereAmIVn2000(x: Double, y: Double) {
        viewModelScope.launch {
            _cloudLoading.value = true
            _whereResult.value = CadastralCloudSource.whereAmI(x, y)
            _cloudLoading.value = false
        }
    }

    /**
     * Tải 1 tờ bản đồ địa chính (VN-2000) rồi (nếu có) điều hướng tới thửa.
     * @param communeSlug ví dụ "nghiathanh".
     * @param rawInput chuỗi "tờ/thửa": 122/90, 122.90, 122-90, hoặc chỉ 122.
     */
    fun loadCadastralSheet(communeSlug: String, rawInput: String) {
        val sp = CadastralCloudSource.parse(rawInput)
        if (sp == null) {
            _cloudMessage.value = "Nhập chưa đúng — ví dụ 122/90 (tờ 122, thửa 90)"
            return
        }
        viewModelScope.launch {
            _cloudLoading.value = true
            when (val r = CadastralCloudSource.loadSheet(communeSlug, sp.to)) {
                is VectorLayerImporter.ImportResult.Success -> {
                    setVectorLayer(r.layer)
                    _targetThua.value = sp.thua
                    _cloudMessage.value =
                        if (sp.thua != null) "Đã tải tờ ${sp.to} → thửa ${sp.thua}"
                        else "Đã tải tờ ${sp.to}"
                }
                is VectorLayerImporter.ImportResult.Error ->
                    _cloudMessage.value = r.message
            }
            _cloudLoading.value = false
        }
    }

    /**
     * Đặt điểm thiết kế vào holder TRƯỚC khi navigate sang StakeoutScreen.
     * StakeoutViewModel sẽ đọc qua holder.consume() — không truyền qua route string.
     */
    fun prepareStakeout(northing: Double, easting: Double, name: String, featureId: Int? = null) {
        stakeoutTargetHolder.set(name, northing, easting, featureId)
    }

    /**
     * Đặt TUYẾN vào holder trước khi navigate — Stakeout sẽ vào chế độ
     * định vị tuyến (khoảng cách vuông góc đến linestring).
     * rawPoints VN-2000: (Easting, Northing) → đổi sang (N, E).
     */
    fun prepareStakeoutLine(feature: com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.VectorFeature) {
        val name = feature.label.ifEmpty { "VEC-${feature.id}" }
        stakeoutTargetHolder.setLine(
            name,
            feature.rawPoints.map { Pair(it.second, it.first) },
            featureId = feature.id   // để Stakeout highlight tuyến trên bản đồ
        )
    }

    // ── GNSS live ─────────────────────────────────────────────
    val gnssStatus: StateFlow<GnssStatus> = gnssManager.gnssStatus

    // ── Dự án hiện tại ─────────────────────────────────────────
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    // ── Điểm đã đo ────────────────────────────────────────────
    private val _savedPoints = MutableStateFlow<List<SurveyPoint>>(emptyList())
    val savedPoints: StateFlow<List<SurveyPoint>> = _savedPoints.asStateFlow()

    // ── Điểm được chọn để xem chi tiết ───────────────────────
    private val _selectedPoint = MutableStateFlow<SurveyPoint?>(null)
    val selectedPoint: StateFlow<SurveyPoint?> = _selectedPoint.asStateFlow()

    // ── Follow GPS (bản đồ tự di chuyển theo vị trí) ─────────
    private val _followGps = MutableStateFlow(true)
    val followGps: StateFlow<Boolean> = _followGps.asStateFlow()

    // ── Form đo điểm (FAB "Đo điểm") ─────────────────────────
    private val _pointCode     = MutableStateFlow("")
    private val _note          = MutableStateFlow("")
    private val _isSaving      = MutableStateFlow(false)
    private val _savedFeedback = MutableStateFlow<String?>(null)
    private val _measureError  = MutableStateFlow<String?>(null)

    val pointCode     : StateFlow<String>  = _pointCode.asStateFlow()
    val note          : StateFlow<String>  = _note.asStateFlow()
    val isSaving      : StateFlow<Boolean> = _isSaving.asStateFlow()
    val savedFeedback : StateFlow<String?> = _savedFeedback.asStateFlow()
    val measureError  : StateFlow<String?> = _measureError.asStateFlow()

    private var currentProjectId: Int = -1

    // ────────────────────────────────────────────────────────
    // Init / Load
    // ────────────────────────────────────────────────────────

    /**
     * Tải dữ liệu cho dự án — gọi từ composable sau khi nhận projectId.
     */
    fun loadProject(projectId: Int) {
        if (projectId <= 0) return
        currentProjectId = projectId

        viewModelScope.launch {
            projectRepo.observeProject(projectId).collect { proj ->
                _project.value = proj
                // Khởi tạo mã điểm từ dự án (chỉ khi chưa nhập gì)
                if (proj != null && _pointCode.value.isEmpty()) {
                    _pointCode.value = proj.nextPointCode
                }
            }
        }
        viewModelScope.launch {
            surveyRepo.getPointsByProject(projectId).collect { points ->
                _savedPoints.value = points
            }
        }
    }

    // ────────────────────────────────────────────────────────
    // Map Actions
    // ────────────────────────────────────────────────────────

    fun selectPoint(point: SurveyPoint?)  { _selectedPoint.value = point }
    fun toggleFollowGps()                 { _followGps.value = !_followGps.value }
    fun enableFollowGps()                 { _followGps.value = true }

    /** Khi user kéo bản đồ → tắt auto-follow */
    fun onMapScrolled() {
        if (_followGps.value) _followGps.value = false
    }

    // ────────────────────────────────────────────────────────
    // Measure Point Actions
    // ────────────────────────────────────────────────────────

    fun onPointCodeChange(v: String) { _pointCode.value = v.uppercase().take(20) }
    fun onNoteChange(v: String)      { _note.value = v }

    /**
     * Lưu điểm đo hiện tại vào cơ sở dữ liệu.
     * Điều kiện: phải có fix GNSS và đang trong ngữ cảnh dự án.
     */
    fun savePoint() {
        val gnss = gnssStatus.value
        val proj = _project.value ?: run {
            _measureError.value = "Chưa tải được dự án"
            return
        }
        if (!gnss.hasFix) {
            _measureError.value = "Chưa có tín hiệu GPS — không thể lưu điểm"
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val vn   = gnss.vn2000
                val code = _pointCode.value.ifEmpty { proj.nextPointCode }

                // Kiểm tra mã điểm trùng
                if (surveyRepo.isCodeExists(currentProjectId, code)) {
                    _measureError.value = "Mã điểm \"$code\" đã tồn tại"
                    return@launch
                }

                val point = SurveyPoint(
                    projectId       = currentProjectId,
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

                surveyRepo.savePoint(point)
                projectRepo.incrementPointIndex(currentProjectId)

                _savedFeedback.value = code

                // Tạo mã điểm kế tiếp
                val updated = projectRepo.getProjectById(currentProjectId)
                _pointCode.value = updated?.nextPointCode ?: ""
                _note.value = ""

            } catch (e: Exception) {
                _measureError.value = "Lỗi lưu điểm: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearSavedFeedback() { _savedFeedback.value = null }
    fun clearMeasureError()  { _measureError.value = null }
}
