package com.hien.rtkmultidevice.ui.screens.stakeout

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.gnss.GnssDataManager
import com.hien.rtkmultidevice.domain.model.GnssStatus
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import com.hien.rtkmultidevice.domain.repository.IProjectRepository
import com.hien.rtkmultidevice.domain.repository.ISurveyPointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * StakeoutViewModel — Logic màn hình cắm mốc (Stakeout).
 *
 * Nguyên lý hoạt động:
 *   1. Lấy vị trí hiện tại từ GnssDataManager → toạ độ VN-2000 (X, Y)
 *   2. User nhập điểm thiết kế (target) bằng toạ độ VN-2000
 *   3. Tính toán liên tục:
 *      - ΔN = target.northing - current.northing
 *      - ΔE = target.easting  - current.easting
 *      - khoảng_cách = √(ΔN² + ΔE²)
 *      - azimuth = atan2(ΔE, ΔN) × 180/π  (0° = Bắc, chiều kim đồng hồ)
 *   4. UI hiển thị mũi tên quay theo azimuth + khoảng cách còn lại
 *   5. Khi khoảng_cách ≤ acceptRadius → phát tín hiệu đã đến nơi
 *
 * Tất cả tính toán dùng toạ độ phẳng VN-2000 (mét) → đơn giản, chính xác
 * cho khoảng cách thực địa trong phạm vi một múi chiếu.
 */
@HiltViewModel
class StakeoutViewModel @Inject constructor(
    private val gnssManager   : GnssDataManager,
    private val projectRepo   : IProjectRepository,
    private val surveyRepo    : ISurveyPointRepository,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val targetHolder  : StakeoutTargetHolder,
    private val vectorLayerHolder: com.hien.rtkmultidevice.ui.screens.map.VectorLayerHolder
) : ViewModel() {

    // ── Live GNSS ──────────────────────────────────────────────
    val gnssStatus: StateFlow<GnssStatus> = gnssManager.gnssStatus

    // ── Target input ───────────────────────────────────────────
    /** X (Northing) điểm thiết kế — nhập thủ công */
    private val _targetNorthing = MutableStateFlow("")
    val targetNorthing: StateFlow<String> = _targetNorthing.asStateFlow()

    /** Y (Easting) điểm thiết kế — nhập thủ công */
    private val _targetEasting = MutableStateFlow("")
    val targetEasting: StateFlow<String> = _targetEasting.asStateFlow()

    /** Tên điểm thiết kế (để hiển thị) */
    private val _targetName = MutableStateFlow("")
    val targetName: StateFlow<String> = _targetName.asStateFlow()

    /** Bán kính chấp nhận (mét) — khi đến đây xem là đã cắm được */
    private val _acceptRadius = MutableStateFlow(0.05)   // 5 cm mặc định
    val acceptRadius: StateFlow<Double> = _acceptRadius.asStateFlow()

    /**
     * Tuyến đang định vị (null = chế độ định vị điểm).
     * Khi có tuyến: app tính khoảng cách VUÔNG GÓC từ vị trí hiện tại đến tuyến,
     * lý trình của chân vuông góc và phía (trái/phải tuyến).
     */
    private val _targetLine = MutableStateFlow<LineTarget?>(null)
    val targetLine: StateFlow<LineTarget?> = _targetLine.asStateFlow()

    /**
     * Feature CAD đang stakeout (chia sẻ qua holder) — Survey/Stakeout map
     * dùng để highlight + vẽ nhãn số hiệu đỉnh từ Bảng toạ độ.
     */
    val activeStakeFeatureId: StateFlow<Int?> = targetHolder.activeFeatureId

    // ── Init: nhận điểm thiết kế từ MapScreen ─────────────────
    // QUAN TRỌNG (fix crash): khối init này PHẢI nằm SAU khai báo
    // _targetNorthing/_targetEasting/_targetName. Kotlin khởi tạo property
    // theo thứ tự khai báo — trước đây init nằm ĐẦU class, gọi setManualTarget()
    // khi các MutableStateFlow còn null → NullPointerException → crash app
    // đúng lúc chọn điểm từ bản đồ vector để cắm mốc.
    //
    // Ưu tiên 1: StakeoutTargetHolder (an toàn với mọi tên điểm)
    // Ưu tiên 2: nav args cũ (tương thích ngược)
    init {
        val held     = targetHolder.consume()
        val heldLine = targetHolder.consumeLine()
        when {
            held != null     -> setManualTarget(held.name, held.northing, held.easting, held.featureId)
            heldLine != null -> setLineTarget(heldLine.name, heldLine.vertices, heldLine.featureId)
            else -> {
                val rawN    = savedStateHandle.get<String>("targetN")
                val rawE    = savedStateHandle.get<String>("targetE")
                val rawName = savedStateHandle.get<String>("targetName")
                val n = rawN?.toDoubleOrNull()
                val e = rawE?.toDoubleOrNull()
                if (n != null && e != null) {
                    val decodedName = try {
                        java.net.URLDecoder.decode(rawName ?: "", "UTF-8")
                    } catch (_: Exception) { rawName ?: "" }
                    setManualTarget(decodedName, n, e)
                }
            }
        }
    }

    /** Điểm đã lưu trong DB của dự án */
    private val _dbPoints = MutableStateFlow<List<SurveyPoint>>(emptyList())

    /** Điểm import từ file (CSV/TXT/DXF/SHP) — giữ riêng để không bị DB ghi đè */
    private val _importedPoints = MutableStateFlow<List<SurveyPoint>>(emptyList())

    /** Danh sách điểm để chọn nhanh = điểm DB + điểm import */
    val savedPoints: StateFlow<List<SurveyPoint>> =
        combine(_dbPoints, _importedPoints) { db, imp -> db + imp }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Layer vector (DXF/SHP) — DÙNG CHUNG với màn hình Định vị CAD qua
     * VectorLayerHolder: chuyển màn hình KHÔNG mất bản vẽ; chỉ unload
     * khi user chủ động bấm ✕.
     */
    val importedLayer: StateFlow<VectorLayerImporter.VectorLayer?> = vectorLayerHolder.layer

    fun clearImportedLayer() {
        vectorLayerHolder.clear()
        _importedPoints.value = emptyList()
    }

    /** Áp dụng layer đã căn chỉnh (đổi KTT / dịch ΔN-ΔE) từ CoordAlignDialog */
    fun applyAlignedLayer(layer: VectorLayerImporter.VectorLayer) {
        vectorLayerHolder.set(layer)
    }

    // ── Stakeout result (tính liên tục) ────────────────────────
    // Ưu tiên chế độ TUYẾN nếu _targetLine != null, ngược lại chế độ ĐIỂM.
    val stakeoutResult: StateFlow<StakeoutResult> = combine(
        gnssManager.gnssStatus,
        combine(_targetNorthing, _targetEasting, _acceptRadius) { n, e, r -> Triple(n, e, r) },
        _targetLine
    ) { gnss, (targetN, targetE, radius), line ->
        if (line != null && line.vertices.size >= 2) {
            computeLineResult(gnss, line, radius)
        } else {
            computeResult(gnss, targetN, targetE, radius)
        }
    }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = StakeoutResult.NoTarget
        )

    // ── Error ──────────────────────────────────────────────────
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ────────────────────────────────────────────────────────────
    // Actions
    // ────────────────────────────────────────────────────────────

    fun onTargetNorthingChange(v: String) { _targetNorthing.value = v }
    fun onTargetEastingChange(v: String)  { _targetEasting.value  = v }
    fun onTargetNameChange(v: String)     { _targetName.value     = v }

    fun onAcceptRadiusChange(v: String) {
        _acceptRadius.value = v.toDoubleOrNull()?.coerceIn(0.01, 100.0) ?: _acceptRadius.value
    }

    /**
     * Tải danh sách điểm đã lưu từ một dự án để chọn nhanh target.
     */
    /** KTT gợi ý khi import vector — lấy từ dự án, mặc định 107°45' */
    private var hintCm: Double = VectorLayerImporter.DEFAULT_CM

    fun loadSavedPointsForProject(projectId: Int) {
        viewModelScope.launch {
            projectRepo.getProjectById(projectId)?.centralMeridian
                ?.takeIf { it > 0.0 }?.let { hintCm = it }
            surveyRepo.getPointsByProject(projectId).collect { points ->
                _dbPoints.value = points
            }
        }
    }

    /**
     * Chọn điểm đã lưu làm target.
     */
    fun selectSavedPoint(point: SurveyPoint) {
        // Cảnh báo nếu VN-2000 chưa được tính (northing ≈ 0)
        if (point.northing == 0.0 && point.easting == 0.0) {
            _error.value = "Điểm \"${point.pointCode}\" chưa có tọa độ VN-2000 " +
                           "(cần cấu hình múi chiếu trong Cài đặt tọa độ)."
            return
        }
        android.util.Log.d("StakeoutVM",
            "selectSavedPoint: ${point.pointCode} N=${point.northing} E=${point.easting} " +
            "lat=${point.latitude} lon=${point.longitude}"
        )
        // QUAN TRỌNG: dùng Locale.US để đảm bảo "." làm decimal separator
        // Nếu dùng locale vi_VN, "%.3f".format(1186788.455) → "1186788,455"
        // → replace(",","") → "1186788455" → parse thành 1.18 tỷ thay vì 1.18 triệu!
        _targetNorthing.value = "%.3f".format(java.util.Locale.US, point.northing)
        _targetEasting.value  = "%.3f".format(java.util.Locale.US, point.easting)
        _targetName.value     = point.pointCode
        // Điểm đã lưu không thuộc đối tượng CAD → tắt nhãn số hiệu đỉnh
        targetHolder.setActiveFeature(null)
    }

    /**
     * Đặt target theo toạ độ nhập tay. Tự thoát chế độ định vị tuyến.
     * @param featureId Id feature CAD chứa điểm (nếu chọn từ đối tượng vector)
     *                  — để vẽ nhãn số hiệu đỉnh; null = nhập tay/từ danh sách.
     */
    fun setManualTarget(name: String, northing: Double, easting: Double, featureId: Int? = null) {
        _targetLine.value     = null
        _targetName.value     = name
        _targetNorthing.value = "%.3f".format(java.util.Locale.US, northing)
        _targetEasting.value  = "%.3f".format(java.util.Locale.US, easting)
        targetHolder.setActiveFeature(featureId)
    }

    /**
     * Đặt TUYẾN để định vị — app sẽ hiển thị khoảng cách vuông góc đến tuyến,
     * lý trình chân vuông góc và phía trái/phải.
     */
    fun setLineTarget(name: String, vertices: List<Pair<Double, Double>>, featureId: Int? = null) {
        if (vertices.size < 2) {
            _error.value = "Tuyến cần ít nhất 2 đỉnh"
            return
        }
        _targetLine.value     = LineTarget(name, vertices, featureId)
        _targetName.value     = name
        _targetNorthing.value = ""
        _targetEasting.value  = ""
        targetHolder.setActiveFeature(featureId)
    }

    /** Xoá target hiện tại (cả điểm lẫn tuyến) */
    fun clearTarget() {
        _targetLine.value     = null
        _targetNorthing.value = ""
        _targetEasting.value  = ""
        _targetName.value     = ""
        targetHolder.setActiveFeature(null)
    }

    fun clearError() { _error.value = null }

    /**
     * Import danh sách điểm thiết kế từ file CSV/TXT hoặc DXF/SHP.
     *
     * Hỗ trợ:
     *   • DXF/SHP  — dùng VectorLayerImporter, trích POINT entities → điểm thiết kế
     *   • CSV export của app (có header "point_code,latitude,...")
     *   • TXT gọn: MãĐiểm\tNorthing\tEasting\tH (tab-separated, dòng # là comment)
     *   • CSV tổng quát: code,northing,easting[,elevation][,note]
     *
     * Điểm import được thêm vào `savedPoints` để chọn nhanh trong PointPickerDialog.
     */
    fun importPointsFromCsv(uri: Uri) {
        viewModelScope.launch {
            // Lấy tên file thật để xác định loại
            val fileName: String = context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cur ->
                    if (cur.moveToFirst()) {
                        val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cur.getString(idx) else null
                    } else null
                }
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "unknown"

            val ext = fileName.substringAfterLast('.', "").lowercase()

            // Dispatch: DXF/SHP → VectorLayerImporter; còn lại → CSV parser
            if (ext == "dxf" || ext == "shp") {
                importFromVectorFile(uri, fileName)
                return@launch
            }

            // ── CSV / TXT parser (giữ nguyên logic cũ) ──────────────
            try {
                val lines = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.readLines()
                    ?: run { _error.value = "Không đọc được file"; return@launch }

                val imported = mutableListOf<SurveyPoint>()

                // Phát hiện định dạng: App CSV export hay TXT/CSV tự do
                // App CSV header: project_name,point_code,latitude,longitude,altitude_m,
                //                 geoid_separation_m,northing_m,easting_m,...
                // TXT tự do: code\tnorthing\teasting[,altitude][,note]  (tab hoặc comma)
                val firstNonComment = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: ""
                val isAppCsvFormat = firstNonComment.contains("point_code", ignoreCase = true) &&
                                     firstNonComment.contains("northing_m", ignoreCase = true)

                // Xác định vị trí cột cho từng định dạng
                // App CSV: col[1]=point_code, col[2]=lat, col[3]=lon, col[4]=alt, col[6]=northing, col[7]=easting
                // TXT tự do: col[0]=code, col[1]=northing, col[2]=easting, col[3]=alt
                var lineNum = 0
                for (line in lines) {
                    lineNum++
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                    // Skip header dòng đầu
                    if (lineNum == 1 && (trimmed.contains("point_code", ignoreCase = true) ||
                                         trimmed.contains("northing", ignoreCase = true) ||
                                         trimmed.contains("code", ignoreCase = true))) continue

                    val parts = if (trimmed.contains('\t')) trimmed.split('\t')
                                else trimmed.split(',')

                    val code: String
                    val northing: Double
                    val easting:  Double
                    val altitude: Double
                    val note:     String

                    if (isAppCsvFormat && parts.size >= 8) {
                        // App CSV format: 0=project,1=code,2=lat,3=lon,4=alt,5=geoid,6=northing,7=easting
                        code     = parts[1].trim().trim('"').ifEmpty { continue }
                        northing = parts[6].trim().replace(" ","").toDoubleOrNull() ?: continue
                        easting  = parts[7].trim().replace(" ","").toDoubleOrNull() ?: continue
                        altitude = parts[4].trim().replace(" ","").toDoubleOrNull() ?: 0.0
                        note     = parts.getOrNull(16)?.trim()?.trim('"') ?: ""
                    } else if (parts.size >= 3) {
                        // TXT tự do: 0=code, 1=northing, 2=easting, [3=alt], [4=note]
                        code     = parts[0].trim().trim('"').ifEmpty { continue }
                        northing = parts[1].trim().replace(" ","").replace(",","").toDoubleOrNull() ?: continue
                        easting  = parts[2].trim().replace(" ","").replace(",","").toDoubleOrNull() ?: continue
                        altitude = parts.getOrNull(3)?.trim()?.toDoubleOrNull() ?: 0.0
                        note     = parts.getOrNull(4)?.trim()?.trim('"') ?: ""
                    } else continue

                    // Bỏ qua nếu northing/easting không hợp lý (< 100 nghĩa là đang dùng độ decimal)
                    if (northing < 100.0 || easting < 100.0) continue

                    imported.add(
                        SurveyPoint(
                            projectId      = -1,   // -1 = điểm import tạm thời
                            pointCode      = code,
                            latitude       = 0.0,
                            longitude      = 0.0,
                            altitude       = altitude,
                            northing       = northing,
                            easting        = easting,
                            centralMeridian = 0.0,
                            fixQuality     = 0,
                            hdop           = 0.0,
                            satelliteCount = 0,
                            note           = note
                        )
                    )
                }

                if (imported.isEmpty()) {
                    _error.value = "Không tìm thấy điểm hợp lệ trong file"
                    return@launch
                }

                // Gộp với danh sách hiện tại (không trùng mã)
                val existing = savedPoints.value.map { it.pointCode }.toSet()
                val newPoints = imported.filter { it.pointCode !in existing }
                _importedPoints.value = _importedPoints.value + newPoints

                _error.value = "✓ Import thành công ${newPoints.size}/${imported.size} điểm"
            } catch (e: Exception) {
                _error.value = "Lỗi import: ${e.message}"
            }
        }
    }

    /**
     * Import điểm thiết kế từ file DXF hoặc SHP.
     *
     * Cách xử lý:
     *   1. Dùng VectorLayerImporter để parse DXF/SHP
     *   2. Trích tất cả POINT entities (có toạ độ VN-2000 hoặc WGS-84)
     *   3. Nếu không có POINT, thử dùng endpoint của POLYLINE làm điểm thiết kế
     *   4. Gộp vào savedPoints (không trùng mã)
     */
    private suspend fun importFromVectorFile(uri: Uri, fileName: String) {
        _error.value = "⏳ Đang đọc file vector…"
        val result = withContext(Dispatchers.IO) {
            VectorLayerImporter.importFromUri(context, uri, fileName, hintCm)
        }
        when (result) {
            is VectorLayerImporter.ImportResult.Error -> {
                _error.value = "Lỗi đọc DXF/SHP: ${result.message}"
            }
            is VectorLayerImporter.ImportResult.Success -> {
                val layer = result.layer
                // CHỈ lưu layer (vào holder dùng chung) — KHÔNG đổ toàn bộ điểm DXF
                // vào danh sách điểm nữa (gây rối, khó tìm). User chọn trực tiếp
                // đối tượng trên màn hình đồ hoạ → Định vị điểm / Định vị tuyến.
                vectorLayerHolder.set(layer)
                val coordSys = when (layer.coordSystem) {
                    VectorLayerImporter.CoordSystem.VN2000_3DEG -> "VN-2000 3° (KTT ${layer.detectedCm}°)"
                    VectorLayerImporter.CoordSystem.VN2000_6DEG -> "VN-2000 6°"
                    VectorLayerImporter.CoordSystem.WGS84        -> "WGS-84"
                    else                                          -> "Không rõ"
                }
                _error.value = "✓ ${layer.featureCount} đối tượng — $coordSys. " +
                               "Chạm trực tiếp đối tượng trên bản đồ để định vị điểm/tuyến."
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Tính toán Stakeout
    // ────────────────────────────────────────────────────────────

    private fun computeResult(
        gnss    : GnssStatus,
        targetN : String,
        targetE : String,
        radius  : Double
    ): StakeoutResult {
        // Strip khoảng trắng và dấu phân cách nghìn trước khi parse
        // ("2 325 478.123" → "2325478.123" | "2,325,478.123" → "2325478.123")
        fun String.parseCoord(): Double? =
            this.replace(" ", "").replace(",", "").toDoubleOrNull()

        val tN = targetN.parseCoord() ?: return StakeoutResult.NoTarget
        val tE = targetE.parseCoord() ?: return StakeoutResult.NoTarget

        val vn = gnss.vn2000 ?: return StakeoutResult.NoGnss

        // Cảnh báo nếu tọa độ có vẻ là độ thập phân WGS-84 thay vì mét VN-2000
        if (tN < 1_000.0 || tE < 1_000.0) {
            return StakeoutResult.InvalidCoord
        }

        // LOG đầy đủ để debug khoảng cách sai
        android.util.Log.d("StakeoutVM",
            "computeResult:" +
            "\n  target  N=$tN  E=$tE" +
            "\n  current N=${vn.northing}  E=${vn.easting}" +
            "\n  gnss lat=${gnss.latitude}  lon=${gnss.longitude}  fix=${gnss.fixQuality}"
        )

        val dN = tN - vn.northing  // dương = cần đi về hướng Bắc
        val dE = tE - vn.easting   // dương = cần đi về hướng Đông

        val distance = sqrt(dN * dN + dE * dE)

        // Azimuth: 0° = Bắc, chiều kim đồng hồ
        // atan2(y, x) trong toán học → atan2(ΔE, ΔN) cho azimuth địa lý
        val azimuthRad = atan2(dE, dN)
        val azimuthDeg = (azimuthRad * 180.0 / PI + 360.0) % 360.0

        val arrived = distance <= radius

        return StakeoutResult.Active(
            distanceM      = distance,
            azimuthDeg     = azimuthDeg,
            dNorthingM     = dN,
            dEastingM      = dE,
            currentNorthing = vn.northing,
            currentEasting  = vn.easting,
            targetNorthing  = tN,
            targetEasting   = tE,
            arrived         = arrived,
            acceptRadius    = radius
        )
    }

    /**
     * Định vị TUYẾN — tính khoảng cách vuông góc từ vị trí hiện tại đến tuyến.
     *
     * Thuật toán: chiếu vuông góc điểm hiện tại lên TỪNG đoạn của tuyến
     * (clamp t ∈ [0,1] để xử lý ngoài phạm vi đoạn), chọn đoạn có khoảng
     * cách nhỏ nhất. Kết quả:
     *   • distanceM  = khoảng cách vuông góc (đến chân vuông góc)
     *   • azimuthDeg = hướng đi đến chân vuông góc (la bàn dẫn hướng)
     *   • stationM   = lý trình của chân vuông góc tính từ đầu tuyến
     *   • sideLabel  = vị trí hiện tại nằm Trái/Phải tuyến (theo chiều tuyến)
     */
    private fun computeLineResult(
        gnss   : GnssStatus,
        line   : LineTarget,
        radius : Double
    ): StakeoutResult {
        val vn = gnss.vn2000 ?: return StakeoutResult.NoGnss
        val pN = vn.northing
        val pE = vn.easting

        var bestDist    = Double.MAX_VALUE
        var bestFootN   = 0.0
        var bestFootE   = 0.0
        var bestStation = 0.0
        var bestCross   = 0.0
        var cumLen      = 0.0

        for (i in 0 until line.vertices.size - 1) {
            val (n1, e1) = line.vertices[i]
            val (n2, e2) = line.vertices[i + 1]
            val dN = n2 - n1
            val dE = e2 - e1
            val len2 = dN * dN + dE * dE
            if (len2 < 1e-9) continue
            val segLen = sqrt(len2)

            // Tham số chiếu t ∈ [0,1] trên đoạn
            val t = (((pN - n1) * dN + (pE - e1) * dE) / len2).coerceIn(0.0, 1.0)
            val fN = n1 + t * dN
            val fE = e1 + t * dE
            val d  = sqrt((pN - fN) * (pN - fN) + (pE - fE) * (pE - fE))

            if (d < bestDist) {
                bestDist    = d
                bestFootN   = fN
                bestFootE   = fE
                bestStation = cumLen + t * segLen
                // Tích chéo: >0 → điểm nằm bên PHẢI theo chiều tuyến
                bestCross   = dN * (pE - e1) - dE * (pN - n1)
            }
            cumLen += segLen
        }

        if (bestDist == Double.MAX_VALUE) return StakeoutResult.NoTarget

        val dN2 = bestFootN - pN
        val dE2 = bestFootE - pE
        val azimuthDeg = (atan2(dE2, dN2) * 180.0 / PI + 360.0) % 360.0

        return StakeoutResult.Active(
            distanceM       = bestDist,
            azimuthDeg      = azimuthDeg,
            dNorthingM      = dN2,
            dEastingM       = dE2,
            currentNorthing = pN,
            currentEasting  = pE,
            targetNorthing  = bestFootN,
            targetEasting   = bestFootE,
            arrived         = bestDist <= radius,
            acceptRadius    = radius,
            lineName        = line.name,
            stationM        = bestStation,
            sideLabel       = if (bestCross > 0) "Phải tuyến" else "Trái tuyến"
        )
    }
}

// ════════════════════════════════════════════════════════════
// StakeoutResult — Kết quả tính toán cắm mốc
// ════════════════════════════════════════════════════════════

/**
 * StakeoutResult — Sealed class kết quả tính toán.
 *
 * NoTarget : Chưa nhập điểm thiết kế
 * NoGnss   : Chưa có tín hiệu GNSS / chưa có VN-2000
 * Active   : Đang dẫn hướng
 */
sealed class StakeoutResult {

    /** Chưa có điểm thiết kế */
    data object NoTarget : StakeoutResult()

    /** Chưa có vị trí GPS / VN-2000 */
    data object NoGnss : StakeoutResult()

    /** Tọa độ thiết kế không hợp lệ (nhập độ decimal thay vì mét VN-2000) */
    data object InvalidCoord : StakeoutResult()

    /** Đang dẫn hướng — có đầy đủ thông tin */
    data class Active(
        /** Khoảng cách còn lại (mét) */
        val distanceM       : Double,
        /** Hướng đến điểm thiết kế (độ, 0°=Bắc, chiều kim đồng hồ) */
        val azimuthDeg      : Double,
        /** ΔX (Northing offset, dương = Bắc) */
        val dNorthingM      : Double,
        /** ΔY (Easting offset, dương = Đông) */
        val dEastingM       : Double,
        /** Toạ độ hiện tại */
        val currentNorthing : Double,
        val currentEasting  : Double,
        /** Toạ độ điểm thiết kế */
        val targetNorthing  : Double,
        val targetEasting   : Double,
        /** Đã đến nơi (distance ≤ acceptRadius) */
        val arrived         : Boolean,
        /** Bán kính chấp nhận (mét) */
        val acceptRadius    : Double,
        /** Tên tuyến đang định vị (null = chế độ định vị điểm) */
        val lineName        : String? = null,
        /** Lý trình (m) của chân vuông góc tính từ đầu tuyến */
        val stationM        : Double? = null,
        /** Vị trí hiện tại nằm Trái/Phải tuyến (theo chiều tuyến) */
        val sideLabel       : String? = null
    ) : StakeoutResult() {

        /** Đang ở chế độ định vị tuyến? */
        val isLineMode: Boolean get() = lineName != null

        /** Định dạng khoảng cách để hiển thị */
        val distanceFormatted: String get() = when {
            distanceM < 1.0  -> "%.3f m".format(distanceM)
            distanceM < 100.0 -> "%.2f m".format(distanceM)
            else              -> "%.1f m".format(distanceM)
        }

        /** Định dạng azimuth để hiển thị */
        val azimuthFormatted: String get() = "%.2f°".format(azimuthDeg)

        /** Nhãn hướng la bàn (N, NE, E, SE, S, SW, W, NW) */
        val compassLabel: String get() {
            val sectors = listOf("Bắc","ĐB","Đông","ĐN","Nam","TN","Tây","TB")
            val idx = ((azimuthDeg + 22.5) / 45.0).toInt() % 8
            return sectors[idx]
        }

        /** Offset theo hướng X (Bắc+/Nam−) */
        val northingOffsetFormatted: String get() {
            val abs = "%.3f".format(kotlin.math.abs(dNorthingM))
            return if (dNorthingM >= 0) "Bắc +$abs m" else "Nam −$abs m"
        }

        /** Offset theo hướng Y (Đông+/Tây−) */
        val eastingOffsetFormatted: String get() {
            val abs = "%.3f".format(kotlin.math.abs(dEastingM))
            return if (dEastingM >= 0) "Đông +$abs m" else "Tây −$abs m"
        }
    }
}
