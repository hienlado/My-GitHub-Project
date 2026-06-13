package com.hien.rtkmultidevice.ui.screens.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * MapTileSource — Các nguồn bản đồ nền hỗ trợ.
 *
 * Lưu ý kỹ thuật — tại sao Google tile cần User-Agent đặc biệt:
 *   osmdroid dùng một userAgentValue toàn cục cho MỌI tile request.
 *   Google Maps tile server (mt0.google.com/vt) block các request có
 *   User-Agent không phải browser. Giải pháp: đặt UA = Chrome Mobile
 *   trong Configuration.getInstance().userAgentValue trước khi MapView init.
 *   (Xem OsmMapView trong MapScreen.kt — đã đặt BROWSER_UA)
 *
 * Tại sao tile không hiển thị khi kết nối WiFi hotspot T30?
 *   Khi phone kết nối WiFi T30 (không có internet), osmdroid dùng WiFi
 *   mặc định → tile download timeout. Cần bật "Use mobile data" trong
 *   WiFi settings Android hoặc sẽ hiện cảnh báo trong UI.
 */
enum class MapTileSource(val label: String, val description: String) {
    OSM_MAPNIK(       "OpenStreetMap",    "Bản đồ đường phố"),
    OPEN_TOPO(        "OpenTopoMap",      "Địa hình + đường đồng mức"),
    ESRI_SATELLITE(   "Esri Satellite",   "Ảnh vệ tinh Esri (không cần key)"),
    CARTO_LIGHT(      "CartoDB Light",    "Bản đồ sáng, nhẹ màu"),
    GOOGLE_NORMAL(    "Google Maps",      "Bản đồ Google (mặc định)"),
    GOOGLE_SATELLITE( "Google Satellite", "Ảnh vệ tinh Google"),
    GOOGLE_HYBRID(    "Google Hybrid",    "Vệ tinh + tên đường Google");

    /** Có hiển thị nhãn cảnh báo unofficial không */
    val isThirdParty get() = this in setOf(GOOGLE_NORMAL, GOOGLE_SATELLITE, GOOGLE_HYBRID)

    fun toOsmdroidSource(): OnlineTileSourceBase = when (this) {

        OSM_MAPNIK -> TileSourceFactory.MAPNIK

        OPEN_TOPO -> XYTileSource(
            "OpenTopoMap", 0, 17, 256, ".png",
            arrayOf(
                "https://a.tile.opentopomap.org/",
                "https://b.tile.opentopomap.org/",
                "https://c.tile.opentopomap.org/"
            ),
            "© OpenTopoMap (CC-BY-SA)"
        )

        ESRI_SATELLITE -> object : OnlineTileSourceBase(
            "EsriSatellite", 0, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/"),
            "© Esri, DigitalGlobe, GeoEye"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                // ArcGIS REST: /tile/{z}/{y}/{x}  (row=Y, col=X)
                return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }

        CARTO_LIGHT -> XYTileSource(
            "CartoDB", 0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/light_all/",
                "https://b.basemaps.cartocdn.com/light_all/",
                "https://c.basemaps.cartocdn.com/light_all/"
            ),
            "© CartoDB"
        )

        // Google tile: dùng nhiều mirror (mt0..mt3) để tránh rate-limit
        GOOGLE_NORMAL -> object : OnlineTileSourceBase(
            "GoogleMaps", 0, 20, 256, ".png",
            arrayOf("https://mt0.google.com/", "https://mt1.google.com/",
                    "https://mt2.google.com/", "https://mt3.google.com/"),
            "© Google"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                // Phân tải đều qua mt0-mt3
                val mirror = "mt${(x + y) and 3}"
                return "https://$mirror.google.com/vt/lyrs=m&x=$x&y=$y&z=$z"
            }
        }

        GOOGLE_SATELLITE -> object : OnlineTileSourceBase(
            "GoogleSatellite", 0, 20, 256, ".jpg",
            arrayOf("https://mt0.google.com/", "https://mt1.google.com/",
                    "https://mt2.google.com/", "https://mt3.google.com/"),
            "© Google"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val mirror = "mt${(x + y) and 3}"
                return "https://$mirror.google.com/vt/lyrs=s&x=$x&y=$y&z=$z"
            }
        }

        GOOGLE_HYBRID -> object : OnlineTileSourceBase(
            "GoogleHybrid", 0, 20, 256, ".jpg",
            arrayOf("https://mt0.google.com/", "https://mt1.google.com/",
                    "https://mt2.google.com/", "https://mt3.google.com/"),
            "© Google"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val mirror = "mt${(x + y) and 3}"
                return "https://$mirror.google.com/vt/lyrs=y&x=$x&y=$y&z=$z"
            }
        }
    }

    companion object {
        /**
         * User-Agent dạng Chrome Mobile — chỉ dùng cho Google tile
         * (Google chặn UA không phải trình duyệt).
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * UA nhận diện ứng dụng — dùng cho OSM/OpenTopo/Carto/Esri.
         *
         * QUAN TRỌNG: OpenStreetMap Tile Usage Policy CHẶN các app dùng
         * UA giả trình duyệt (như BROWSER_UA) hoặc UA mặc định của osmdroid.
         * Đây là nguyên nhân phổ biến khiến bản đồ nền OSM không hiển thị.
         * App phải tự nhận diện bằng UA riêng (tên app + cách liên hệ).
         */
        const val APP_UA = "RTKMultiDevice/1.0 (Android; osmdroid/6.1.20)"

        /** Chọn UA đúng cho từng nguồn tile */
        fun userAgentFor(source: MapTileSource): String =
            if (source.isThirdParty) BROWSER_UA else APP_UA
    }
}
