package com.hien.rtkmultidevice.core.cad

/**
 * Mô hình dữ liệu CAD tối giản cho trình vẽ trên bản đồ.
 * Toạ độ đỉnh lưu theo VN-2000 (N=Northing, E=Easting, mét) — chuẩn để xuất DXF và cắm mốc.
 * (Toạ độ WGS-84 để vẽ trên osmdroid được suy ra khi hiển thị, không lưu ở model.)
 */

/** Đỉnh CAD (VN-2000). */
data class CadVertex(val n: Double, val e: Double)

enum class CadType { POINT, LINE, POLYGON }

/** Một đối tượng CAD: điểm / đường / vùng, thuộc một lớp, có thể có nhãn. */
data class CadEntity(
    val id: Int,
    val type: CadType,
    val vertices: List<CadVertex>,
    val layer: String,
    val label: String = ""
)

/** Lớp CAD (tên + màu ACI AutoCAD + hiện/ẩn). */
data class CadLayer(
    val name: String,
    val colorAci: Int = 7,   // 1=đỏ 2=vàng 3=lục 4=lơ 5=lam 6=tím 7=trắng/đen
    var visible: Boolean = true
)

/** Bản vẽ CAD = danh sách lớp + danh sách đối tượng. */
class CadDrawing {
    val layers = mutableListOf(CadLayer("0"))
    val entities = mutableListOf<CadEntity>()
    private var seq = 0

    fun nextId(): Int = ++seq

    fun ensureLayer(name: String, colorAci: Int = 7): CadLayer =
        layers.firstOrNull { it.name == name } ?: CadLayer(name, colorAci).also { layers.add(it) }

    fun add(type: CadType, vertices: List<CadVertex>, layer: String, label: String = ""): CadEntity {
        ensureLayer(layer)
        val e = CadEntity(nextId(), type, vertices, layer, label)
        entities.add(e)
        return e
    }

    fun removeEntity(id: Int) { entities.removeAll { it.id == id } }

    fun clear() { entities.clear() }

    fun isEmpty() = entities.isEmpty()
}
