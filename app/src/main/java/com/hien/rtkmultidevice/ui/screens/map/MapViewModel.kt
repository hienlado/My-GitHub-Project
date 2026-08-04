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
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import com.hien.rtkmultidevice.report.BienBan
import com.hien.rtkmultidevice.report.BienBanPdf
import com.hien.rtkmultidevice.report.BienBanSketch
import com.hien.rtkmultidevice.report.BienBanXml
import com.hien.rtkmultidevice.report.ReportStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val appSettings         : com.hien.rtkmultidevice.data.datastore.AppSettings,
    private val stakeoutTargetHolder: StakeoutTargetHolder,
    private val parcelVerticesHolder: com.hien.rtkmultidevice.ui.screens.stakeout.ParcelVerticesHolder,
    private val vectorLayerHolder   : VectorLayerHolder,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    // ── Chế độ OFFLINE (đọc dữ liệu địa chính từ bộ nhớ máy thay vì Cloud) ──
    private val _offlineMode = MutableStateFlow(CadastralLocalSource.hasData(appContext))
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()
    fun setOfflineMode(on: Boolean) { _offlineMode.value = on; _sheetFrames.value = emptyList() }
    fun hasOfflineData(): Boolean = CadastralLocalSource.hasData(appContext)

    /**
     * Layer CAD dùng chung (VectorLayerHolder) — import ở đây thì màn hình
     * Cắm mốc cũng thấy, và ngược lại. Chỉ unload khi user bấm ✕.
     */
    val vectorLayer: kotlinx.coroutines.flow.StateFlow<VectorLayerImporter.VectorLayer?> =
        vectorLayerHolder.layer

    fun setVectorLayer(layer: VectorLayerImporter.VectorLayer) = vectorLayerHolder.set(layer)
    fun addVectorLayer(layer: VectorLayerImporter.VectorLayer) = vectorLayerHolder.add(layer)
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

    // ── Khung tờ tổng thể (overlay trên osmdroid) ──
    private val _sheetFrames = MutableStateFlow<List<CadastralCloudSource.SheetBox>>(emptyList())
    val sheetFrames: StateFlow<List<CadastralCloudSource.SheetBox>> = _sheetFrames.asStateFlow()

    /** Tải chỉ mục khung tờ 1 lần (cache). */
    fun loadSheetFramesIfNeeded() {
        if (_sheetFrames.value.isNotEmpty()) return
        viewModelScope.launch {
            val list = if (_offlineMode.value) CadastralLocalSource.loadIndex(appContext)
                       else CadastralCloudSource.loadIndex()
            if (list.isEmpty()) _cloudMessage.value =
                if (_offlineMode.value) "Chưa có chỉ mục tờ offline (chép sheets/ vào máy)"
                else "Chưa tải được chỉ mục tờ"
            _sheetFrames.value = list
        }
    }

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
            else {
                val r = if (_offlineMode.value)
                    CadastralLocalSource.whereAmIVn2000(appContext, vn.easting, vn.northing)
                else CadastralCloudSource.whereAmI(vn.easting, vn.northing)
                _whereResult.value = r
                openSheetOf(r)          // mở luôn tờ, khỏi thao tác thêm
            }
            _cloudLoading.value = false
        }
    }

    /**
     * Tra xong vị trí thì MỞ LUÔN tờ bản đồ chứa nó.
     * Trước đây người dùng phải tự nhớ số tờ rồi mở tay — thừa một bước.
     * WhereResult chỉ có TÊN xã nên phải dò ngược ra slug thư mục.
     */
    private fun openSheetOf(r: CadastralCloudSource.WhereResult?) {
        if (r == null || !r.found || r.to.isBlank()) return
        val slug = communeSlugFromName(r.xaName) ?: return
        _targetThua.value = r.thua.ifBlank { null }   // để highlight thửa sau khi tải
        loadCadastralSheet(slug, if (r.thua.isBlank()) r.to else "${r.to}/${r.thua}")
    }

    // ── Lịch sử tìm kiếm (10 mục gần nhất) ────────────────────
    val recentSheets: StateFlow<List<String>> = appSettings.recentSheetsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentOwners: StateFlow<List<String>> = appSettings.recentOwnersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rememberOwnerQuery(q: String) {
        viewModelScope.launch { appSettings.addRecentOwner(q.trim()) }
    }

    /** Dò slug thư mục xã từ tên hiển thị (bỏ dấu, bỏ "Xã/Phường"). */
    private fun communeSlugFromName(name: String): String? {
        if (name.isBlank()) return null
        fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase().replace(Regex("[^a-z0-9]"), "")
        val target = norm(name)
        return CadastralCloudSource.COMMUNES
            .firstOrNull { norm(it.second) == target || target.endsWith(norm(it.second).removePrefix("xa").removePrefix("phuong")) }
            ?.first
    }

    /** Tra theo toạ độ VN-2000 nhập tay (x=Easting, y=Northing). */
    fun whereAmIVn2000(x: Double, y: Double) {
        viewModelScope.launch {
            _cloudLoading.value = true
            val r = if (_offlineMode.value) CadastralLocalSource.whereAmIVn2000(appContext, x, y)
                    else CadastralCloudSource.whereAmI(x, y)
            _whereResult.value = r
            openSheetOf(r)
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
            val r = if (_offlineMode.value) CadastralLocalSource.loadSheet(appContext, communeSlug, sp.to)
                    else CadastralCloudSource.loadSheet(communeSlug, sp.to)
            when (r) {
                is VectorLayerImporter.ImportResult.Success -> {
                    addVectorLayer(r.layer)          // GỘP thêm tờ (mở nhiều tờ cùng lúc)
                    _targetThua.value = sp.thua
                    // Ghi vào lịch sử để lần sau chọn nhanh
                    appSettings.addRecentSheet("$communeSlug|$rawInput")
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

    /**
     * Gửi các đỉnh của một đối tượng vector sang ngăn "Đỉnh thửa" ở Danh sách.
     * KHÔNG lưu vào Room — đây là dữ liệu thiết kế, không phải điểm đo.
     * rawPoints VN-2000: (Easting, Northing) → đổi sang (N, E).
     */
    fun sendVerticesToList(
        feature: com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.VectorFeature
    ) {
        val name = feature.label.ifEmpty { "Thửa ${feature.id}" }
        parcelVerticesHolder.setParcel(
            name      = name,
            points    = feature.rawPoints.map { Pair(it.second, it.first) },
            featureId = feature.id,
            closed    = feature.type == VectorLayerImporter.FeatureType.POLYGON
        )
    }

    // ── Biên bản bàn giao mốc giới ────────────────────────────
    private val _reportFile = MutableStateFlow<String?>(null)
    val reportFile: StateFlow<String?> = _reportFile.asStateFlow()
    fun clearReportFile() { _reportFile.value = null }

    /**
     * Xuất BIÊN BẢN BÀN GIAO MỐC GIỚI cho thửa đang chọn.
     *
     * Quy trình theo yêu cầu: xuất XML để kiểm tra/sửa TRƯỚC, rồi mới xuất PDF.
     * @param toPdf false = chỉ XML; true = đọc lại XML (nếu có) rồi dựng PDF A4 2 mặt.
     */
    fun exportBienBan(
        feature: com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.VectorFeature,
        toPdf  : Boolean
    ) {
        viewModelScope.launch {
            runCatching {
                val rpt = appSettings.reportSettingsFlow.first()
                val cal = java.util.Calendar.getInstance()
                // rawPoints VN-2000: (Easting, Northing) → (N, E)
                val verts = feature.rawPoints.map { it.second to it.first }
                // Lưu vào thư mục TẢI XUỐNG (Downloads) — cùng chỗ với file CSV điểm đo,
                // vì Android/data/<package> bị Android 11+ chặn, người dùng không mở được.
                val base  = "BBMG-${feature.soThua.ifBlank { feature.label }.replace(Regex("[^A-Za-z0-9]"), "_")}"
                val xmlNm = "$base.xml"
                val pdfNm = "$base.pdf"

                if (!toPdf) {
                    val bb = BienBan(
                        ngay = cal.get(java.util.Calendar.DAY_OF_MONTH),
                        thang = cal.get(java.util.Calendar.MONTH) + 1,
                        nam = cal.get(java.util.Calendar.YEAR),
                        soThua = feature.soThua.ifBlank { feature.label },
                        dienTich = feature.dienTich, loaiDat = feature.loaiDat,
                        chuSuDung = feature.chuSuDung,
                        donViTen = rpt.donViTen, donViDaiDien = rpt.donViDaiDien,
                        donViChucVu = rpt.donViChucVu, donViDiaChi = rpt.donViDiaChi,
                        donViVpdd = rpt.donViVpdd, noiCapGcn = rpt.noiCapGcn,
                        moc = BienBan.buildMoc(verts)
                    )
                    ReportStorage.save(
                        appContext, xmlNm,
                        BienBanXml.toXml(bb).toByteArray(Charsets.UTF_8), "text/xml"
                    )
                    _reportFile.value = "Đã lưu Tải xuống/$xmlNm — sửa xong rồi bấm PDF"
                } else {
                    // Đọc lại XML NGƯỜI DÙNG ĐÃ SỬA từ Tải xuống
                    val xmlText = ReportStorage.readText(appContext, xmlNm)
                        ?: throw IllegalStateException("Chưa có $xmlNm — bấm XML trước")
                    val tmp = java.io.File(appContext.cacheDir, xmlNm)
                        .apply { writeText(xmlText, Charsets.UTF_8) }
                    val bb = BienBanXml.load(tmp)
                        ?: throw IllegalStateException(
                            "Không đọc được $xmlNm — ${BienBanXml.lastError ?: "sai định dạng"}"
                        )

                    val others = vectorLayerHolder.layer.value?.features.orEmpty()
                    val nbs = BienBanSketch.pickNeighbours(others, feature.id, verts)
                    val pdfTmp = java.io.File(appContext.cacheDir, pdfNm)
                    BienBanPdf.export(pdfTmp, bb) { canvas, frame ->
                        BienBanSketch.draw(
                            canvas, frame, verts,
                            BienBanSketch.ParcelLabel(bb.soThua, bb.dienTich, bb.loaiDat),
                            nbs
                        )
                    }
                    ReportStorage.save(appContext, pdfNm, pdfTmp.readBytes(), "application/pdf")
                    _reportFile.value = "Đã lưu Tải xuống/$pdfNm"
                }
            }.onFailure { _reportFile.value = "Lỗi xuất biên bản: ${it.message}" }
        }
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
