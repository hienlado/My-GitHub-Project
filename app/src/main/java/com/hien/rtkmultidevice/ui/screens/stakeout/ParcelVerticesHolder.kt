package com.hien.rtkmultidevice.ui.screens.stakeout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ParcelVerticesHolder — Giữ ĐỈNH THỬA lấy từ bản đồ địa chính.
 *
 * Vì sao tách riêng, không nhập vào danh sách điểm đo:
 *   Đỉnh thửa là dữ liệu THIẾT KẾ (lấy từ bản đồ), không phải điểm ĐO ngoài thực địa.
 *   Trộn chung sẽ làm bẩn dữ liệu đo GNSS và dễ bị xuất nhầm vào CSV kết quả đo.
 *   Vì vậy để ở singleton riêng: hiện thành một ngăn riêng trong Danh sách,
 *   dùng để định vị (điểm/tuyến) nhưng KHÔNG lưu vào Room và KHÔNG xuất chung.
 *
 * Dùng chung giữa Bản đồ → Danh sách → Cắm mốc.
 */
@Singleton
class ParcelVerticesHolder @Inject constructor() {

    /**
     * Một đỉnh thửa.
     * @param index Số thứ tự đỉnh (khớp cột "#" trong Bảng toạ độ đỉnh)
     */
    data class Vertex(
        val index    : Int,
        val northing : Double,
        val easting  : Double
    ) {
        val label: String get() = "Đ$index"
    }

    /** Tên thửa/đối tượng đang giữ (VD "Tờ 12 - Thửa 45"). */
    private val _parcelName = MutableStateFlow("")
    val parcelName: StateFlow<String> = _parcelName.asStateFlow()

    private val _vertices = MutableStateFlow<List<Vertex>>(emptyList())
    val vertices: StateFlow<List<Vertex>> = _vertices.asStateFlow()

    /** Id feature trên lớp vector — để highlight lại trên bản đồ. */
    private val _featureId = MutableStateFlow<Int?>(null)
    val featureId: StateFlow<Int?> = _featureId.asStateFlow()

    /** true nếu đỉnh tạo thành vùng khép kín (thửa) — cho phép định vị cả đường bao. */
    private val _isClosed = MutableStateFlow(false)
    val isClosed: StateFlow<Boolean> = _isClosed.asStateFlow()

    /**
     * Nạp đỉnh của một thửa. Thay thế toàn bộ tập cũ —
     * mỗi lần chỉ làm việc với MỘT thửa cho khỏi rối.
     *
     * @param points danh sách (Northing, Easting) theo đúng thứ tự đỉnh
     */
    fun setParcel(
        name: String,
        points: List<Pair<Double, Double>>,
        featureId: Int? = null,
        closed: Boolean = false
    ) {
        _parcelName.value = name
        _featureId.value  = featureId
        _isClosed.value   = closed
        _vertices.value = points.mapIndexed { i, (n, e) ->
            Vertex(index = i + 1, northing = n, easting = e)
        }
    }

    fun clear() {
        _parcelName.value = ""
        _vertices.value   = emptyList()
        _featureId.value  = null
        _isClosed.value   = false
    }

    /** Đường bao khép kín để định vị cả chu vi (nối đỉnh cuối về đỉnh đầu). */
    fun boundaryVertices(): List<Pair<Double, Double>> {
        val v = _vertices.value
        if (v.size < 2) return emptyList()
        val list = v.map { it.northing to it.easting }
        return if (_isClosed.value && list.first() != list.last()) list + list.first() else list
    }
}
