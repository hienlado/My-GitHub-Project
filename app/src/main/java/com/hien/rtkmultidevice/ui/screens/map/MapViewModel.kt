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
        viewModelScope.launch { ensureSheetFrames() }
    }

    /**
     * Nạp chỉ mục khung tờ và CHỜ XONG mới trả về.
     *
     * Bản `loadSheetFramesIfNeeded()` chỉ khởi chạy coroutine rồi trả về ngay —
     * ai gọi xong đọc `_sheetFrames.value` liền sẽ thấy RỖNG. Đó là lý do
     * `loadAdjacentSheets` luôn thoát sớm và sơ hoạ không có tờ giáp biên.
     */
    private suspend fun ensureSheetFrames(): List<CadastralCloudSource.SheetBox> {
        if (_sheetFrames.value.isNotEmpty()) return _sheetFrames.value
        val list = if (_offlineMode.value) CadastralLocalSource.loadIndex(appContext)
                   else CadastralCloudSource.loadIndex()
        if (list.isEmpty()) _cloudMessage.value =
            if (_offlineMode.value) "Chưa có chỉ mục tờ offline (chép sheets/ vào máy)"
            else "Chưa tải được chỉ mục tờ"
        _sheetFrames.value = list
        return list
    }

    // ── "Tôi đang ở thửa nào?" (tra ngược điểm RTK -> xã/tờ/thửa) ──
    private val _whereResult = MutableStateFlow<CadastralCloudSource.WhereResult?>(null)
    val whereResult: StateFlow<CadastralCloudSource.WhereResult?> = _whereResult.asStateFlow()
    fun clearWhereResult() { _whereResult.value = null; _qhResult.value = null }

    // ── Quy hoạch của thửa vừa tra (QHSDD + QHXD, đọc từ sheets/_qh) ──
    private val _qhResult = MutableStateFlow<QhLookup.ThuaQh?>(null)
    val qhResult: StateFlow<QhLookup.ThuaQh?> = _qhResult.asStateFlow()

    /** Ưu tiên slug thật trong WhereResult; dữ liệu cũ không có thì dò theo tên xã. */
    private fun traQuyHoach(r: CadastralCloudSource.WhereResult?) {
        _qhResult.value = null
        if (r == null || !r.found || r.thua.isBlank()) return
        val slug = if (r.commune.isNotBlank()) r.commune
                   else (communeSlugFromName(r.xaName) ?: return)
        viewModelScope.launch {
            _qhResult.value = QhLookup.tra(
                appContext, slug, r.to, r.thua,
                r.dienTich.replace(',', '.').toDoubleOrNull() ?: 0.0)
        }
    }

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
                traQuyHoach(r)
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
            traQuyHoach(r)
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
        // ĐÃ NẠP RỒI thì KHÔNG đọc/parse lại — chỉ điều hướng tới thửa.
        // Đây là chỗ gây lag: mỗi lần bấm "tôi đang ở thửa nào" lại nạp trùng
        // cùng một tờ, số đối tượng nhân lên và bản đồ vẽ lại toàn bộ.
        val key = "$communeSlug/${sp.to}"
        if (vectorLayerHolder.isLoaded(key)) {
            _targetThua.value = sp.thua
            _cloudMessage.value =
                if (sp.thua != null) "Tờ ${sp.to} đã mở → thửa ${sp.thua}"
                else "Tờ ${sp.to} đã mở"
            return
        }

        viewModelScope.launch {
            _cloudLoading.value = true
            val r = if (_offlineMode.value) CadastralLocalSource.loadSheet(appContext, communeSlug, sp.to)
                    else CadastralCloudSource.loadSheet(communeSlug, sp.to)
            when (r) {
                is VectorLayerImporter.ImportResult.Success -> {
                    vectorLayerHolder.addSheet(key, r.layer)   // gộp tờ, chống trùng
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

    /** Thông tin đơn vị đo đạc đã lưu — dùng điền sẵn form biên bản. */
    // Eagerly (KHÔNG dùng WhileSubscribed): giao diện chỉ đọc .value một lần khi mở
    // form, không "lắng nghe" flow — dùng WhileSubscribed thì upstream không chạy
    // và .value mãi là giá trị rỗng ⇒ biên bản mất thông tin đơn vị.
    val reportSettings: StateFlow<com.hien.rtkmultidevice.data.datastore.AppSettings.ReportSettings> =
        appSettings.reportSettingsFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            com.hien.rtkmultidevice.data.datastore.AppSettings.ReportSettings()
        )

    /** Nội dung biên bản điền sẵn từ dữ liệu thửa + cài đặt đơn vị. */
    fun bienBanDefaults(
        feature: com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.VectorFeature
    ): BienBan {
        val rpt = reportSettings.value
        val cal = java.util.Calendar.getInstance()
        return BienBan(
            ngay = cal.get(java.util.Calendar.DAY_OF_MONTH),
            thang = cal.get(java.util.Calendar.MONTH) + 1,
            nam = cal.get(java.util.Calendar.YEAR),
            soThua = feature.soThua.ifBlank { feature.label },
            // Số tờ nằm sẵn trong `nguon` = "slug xã/số tờ" — trước đây bỏ trống
            // rồi bắt người dùng gõ tay, trong khi dữ liệu đã có.
            soTo = feature.nguon.substringAfter('/', "").trim(),
            dienTich = feature.dienTich, loaiDat = feature.loaiDat,
            chuSuDung = feature.chuSuDung,
            donViTen = rpt.donViTen, donViDaiDien = rpt.donViDaiDien,
            donViChucVu = rpt.donViChucVu, donViDiaChi = rpt.donViDiaChi,
            donViVpdd = rpt.donViVpdd, noiCapGcn = rpt.noiCapGcn,
            moc = BienBan.buildMoc(feature.rawPoints.map { it.second to it.first })
        )
    }

    /** Thửa giáp biên để vẽ nền sơ hoạ (chỉ trong các tờ ĐANG mở). */
    fun neighboursOf(
        feature: com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.VectorFeature
    ): List<BienBanSketch.NeighbourParcel> = BienBanSketch.pickNeighbours(
        vectorLayerHolder.layer.value?.features.orEmpty(),
        feature.id,
        feature.rawPoints.map { it.second to it.first }
    )

    /**
     * Nạp thêm các TỜ KỀ BÊN chứa thửa giáp ranh với thửa đang lập biên bản.
     *
     * Vì sao cần: thửa nằm ở RÌA tờ thì các thửa tiếp giáp lại thuộc tờ khác
     * chưa mở ⇒ sơ hoạ bị thiếu ranh phía đó. Dùng chỉ mục khung tờ để tìm tờ
     * nào có bao hình chạm vùng quanh thửa rồi nạp bổ sung (đã có chống trùng).
     *
     * @param onDone gọi lại sau khi nạp xong để dựng lại sơ hoạ.
     */
    fun loadAdjacentSheets(
        feature: com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.VectorFeature,
        onDone : () -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                var idx = ensureSheetFrames()      // CHỜ chỉ mục nạp xong
                // Chế độ Cloud mà không lấy được chỉ mục (mất mạng / đã gỡ billing)
                // thì dùng chỉ mục OFFLINE — trước đây im lặng bỏ qua.
                if (idx.isEmpty() && !_offlineMode.value) {
                    idx = CadastralLocalSource.loadIndex(appContext)
                    _sheetFrames.value = idx
                }
                if (idx.isEmpty()) {
                    _cloudMessage.value = "Sơ hoạ: chưa có chỉ mục tờ → không tìm được tờ giáp biên"
                    return@runCatching
                }

                // Bao hình thửa theo WGS-84 + nới rộng để chạm sang tờ bên
                val gps = feature.geoPoints
                if (gps.isEmpty()) return@runCatching
                val latMin = gps.minOf { it.latitude };  val latMax = gps.maxOf { it.latitude }
                val lonMin = gps.minOf { it.longitude }; val lonMax = gps.maxOf { it.longitude }

                // Bán kính tìm TUYỆT ĐỐI (mét), KHÔNG tỉ lệ theo kích thước thửa.
                // Trước đây nới 1.5 lần bề rộng thửa: thửa 40 m chỉ dò trong ~60 m
                // nên tờ kề có bao hình bắt đầu xa hơn chút là trượt hết.
                val searchM = 250.0
                val dLat = searchM / 111_320.0                       // 1° vĩ ≈ 111.32 km
                val dLon = searchM / (111_320.0 * kotlin.math.cos(Math.toRadians(latMin)))

                val touching = idx.filter { b ->
                    b.lonMax >= lonMin - dLon && b.lonMin <= lonMax + dLon &&
                    b.latMax >= latMin - dLat && b.latMin <= latMax + dLat
                }
                var added = 0
                var skipped = 0
                for (b in touching) {
                    val key = "${b.commune}/${b.to}"
                    if (vectorLayerHolder.isLoaded(key)) { skipped++; continue }
                    val r = if (_offlineMode.value)
                        CadastralLocalSource.loadSheet(appContext, b.commune, b.to)
                    else CadastralCloudSource.loadSheet(b.commune, b.to)
                    if (r is VectorLayerImporter.ImportResult.Success) {
                        vectorLayerHolder.addSheet(key, r.layer); added++
                    }
                }
                // Luôn báo kết quả để CHẨN ĐOÁN được khi sơ hoạ vẫn thiếu ranh
                val nb = neighboursOf(feature).size
                _cloudMessage.value =
                    "Sơ hoạ: ${touching.size} tờ trong bán kính ${searchM.toInt()}m " +
                    "(nạp mới $added, đã có $skipped) → $nb thửa giáp biên"
            }.onFailure {
                _cloudMessage.value = "Sơ hoạ — lỗi nạp tờ kề: ${it.message}"
            }
            onDone()
        }
    }

    /**
     * Vùng quy hoạch quanh thửa, đổi về VN-2000 (N, E) cho trang sơ đồ biên bản.
     *
     * `QhLookup` trả GeoPoint WGS-84 vì nó phục vụ osmdroid; biên bản lại vẽ theo
     * mét như bảng kê mốc giới, nên phải chiếu ngược. Dùng chế độ THEO_TO có nạp
     * tờ kề bên (`loadAdjacentSheets` đã gọi trước khi mở form) nên thửa giáp
     * biên / giáp tờ vẫn đủ vùng quy hoạch xung quanh.
     */
    private suspend fun qhVungChoBienBan(
        keys: Set<String>, lop: String, cm: Double
    ): List<com.hien.rtkmultidevice.report.BienBanQh.Vung> {
        if (keys.isEmpty()) return emptyList()
        val cheDo = if (com.hien.rtkmultidevice.ui.screens.map.QhLookup.coToanXa(appContext, keys))
            QhLookup.TOAN_XA else QhLookup.THEO_TO
        fun ve(ds: List<org.osmdroid.util.GeoPoint>) = ds.mapNotNull {
            VectorLayerImporter.wgs84ToVn2000(it.latitude, it.longitude, cm)
        }
        return QhLookup.vungQuyHoach(appContext, keys, cheDo, lop, cm).mapNotNull { v ->
            val ngoai = ve(v.diem)
            if (ngoai.size < 3) null
            else com.hien.rtkmultidevice.report.BienBanQh.Vung(
                ma = v.ma, ten = v.ten, mau = v.mau,
                ngoai = ngoai,
                lo = v.lo.map { ve(it) }.filter { it.size >= 3 }
            )
        }
    }

    /**
     * Xuất BIÊN BẢN từ nội dung người dùng đã nhập trong form.
     *
     * Ghi nhớ luôn thông tin ĐƠN VỊ ĐO ĐẠC để lần sau tự điền —
     * đó là phần cố định, không việc gì phải gõ lại mỗi biên bản.
     *
     * @param toPdf false = lưu XML (lưu trữ/đối chiếu); true = xuất PDF A4 2 mặt.
     */
    fun exportBienBan(
        feature : com.hien.rtkmultidevice.ui.screens.map.VectorLayerImporter.VectorFeature,
        bb      : BienBan,
        zoom    : Float,
        centerN : Double,
        centerE : Double,
        toPdf   : Boolean
    ) {
        viewModelScope.launch {
            runCatching {
                // Ghi nhớ thông tin đơn vị cho lần sau
                appSettings.saveReportSettings(
                    com.hien.rtkmultidevice.data.datastore.AppSettings.ReportSettings(
                        donViTen = bb.donViTen, donViDaiDien = bb.donViDaiDien,
                        donViChucVu = bb.donViChucVu, donViDiaChi = bb.donViDiaChi,
                        donViVpdd = bb.donViVpdd, noiCapGcn = bb.noiCapGcn
                    )
                )

                val verts = feature.rawPoints.map { it.second to it.first }
                // Lưu vào TẢI XUỐNG — Android 11+ chặn Android/data/<package>
                val base  = "BBMG-${bb.soThua.ifBlank { "thua" }.replace(Regex("[^A-Za-z0-9]"), "_")}"

                if (!toPdf) {
                    ReportStorage.save(
                        appContext, "$base.xml",
                        BienBanXml.toXml(bb).toByteArray(Charsets.UTF_8), "text/xml"
                    )
                    _reportFile.value = "Đã lưu Tải xuống/$base.xml"
                } else {
                    // ── Ưu tiên XML NGƯỜI DÙNG ĐÃ SỬA ───────────────────────
                    // Quy trình là: xuất XML -> sửa tay -> xuất PDF. Trước đây
                    // PDF luôn dựng từ `bb` trong form nên mọi sửa đổi trong XML
                    // bị bỏ qua. `BienBanXml.load` và `ReportStorage.readText`
                    // vốn đã viết sẵn cho việc này nhưng chưa chỗ nào gọi.
                    val xmlCu = ReportStorage.readText(appContext, "$base.xml")
                    val tuXml = xmlCu?.let { BienBanXml.loadText(it) }
                    val data  = tuXml ?: bb

                    val nbs = neighboursOf(feature)
                    val cm  = feature.centralMeridian.takeIf { it != 0.0 } ?: 107.75
                    val keys = setOfNotNull(feature.nguon.takeIf { it.isNotBlank() })
                    val sdd = qhVungChoBienBan(keys, "SDD", cm)
                    val xd  = qhVungChoBienBan(keys, "XD", cm)

                    val pdfTmp = java.io.File(appContext.cacheDir, "$base.pdf")
                    BienBanPdf.export(
                        pdfTmp, data,
                        sketch = { canvas, frame ->
                            BienBanSketch.draw(
                                canvas, frame, verts,
                                BienBanSketch.ParcelLabel(data.soThua, data.dienTich, data.loaiDat),
                                nbs, zoom, centerN, centerE
                            )
                        },
                        qhSdd = sdd, qhXd = xd, qhVerts = verts
                    )
                    ReportStorage.save(appContext, "$base.pdf", pdfTmp.readBytes(), "application/pdf")
                    _reportFile.value =
                        if (tuXml != null) "Đã lưu Tải xuống/$base.pdf (theo $base.xml đã sửa)"
                        else "Đã lưu Tải xuống/$base.pdf (theo nội dung trong form)"
                }
            }.onFailure { _reportFile.value = "Lỗi xuất biên bản: ${it.message}" }
        }
    }

    /**
     * Bật GPS điện thoại làm nguồn vị trí dự phòng khi chưa có máy thu RTK.
     * Gọi khi mở màn Bản đồ. Có RTK là GnssDataManager tự tắt dự phòng.
     */
    fun ensurePositionSource() {
        if (gnssManager.gnssStatus.value.fixQuality <= 0) gnssManager.startPhoneGpsFallback()
    }

    /** true = vị trí đang lấy từ chip điện thoại (không phải máy thu RTK). */
    val usingPhoneGps: StateFlow<Boolean> = gnssManager.usingPhoneGps

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
