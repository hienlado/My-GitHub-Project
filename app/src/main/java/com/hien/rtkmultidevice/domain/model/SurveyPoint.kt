package com.hien.rtkmultidevice.domain.model

import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SurveyPoint — Domain model của điểm đo RTK.
 *
 * Chứa đầy đủ thông tin để export và kiểm tra.
 */
data class SurveyPoint(
    val id              : Int    = 0,
    val projectId       : Int,
    val pointCode       : String,

    // WGS-84
    val latitude        : Double,
    val longitude       : Double,
    val altitude        : Double,
    val geoidSeparation : Double = 0.0,

    // VN-2000
    val northing        : Double,
    val easting         : Double,
    val centralMeridian : Double,
    val zoneWidthDeg    : Int    = 3,

    // Chất lượng GNSS
    val fixQuality      : Int,
    val hdop            : Double,
    val pdop            : Double = 0.0,
    val satelliteCount  : Int,

    // Metadata
    val timestamp       : Long   = System.currentTimeMillis(),
    val note            : String = "",
    val orderIndex      : Int    = 0
) {
    val fixLabel: String get() = when (fixQuality) {
        4    -> "RTK FIXED"
        5    -> "RTK FLOAT"
        2    -> "DGPS"
        1    -> "SINGLE"
        else -> "NO FIX"
    }

    val fixColorHex: String get() = when (fixQuality) {
        4    -> "#FF2E7D32"
        5    -> "#FF558B2F"
        2    -> "#FF1565C0"
        1    -> "#FFF57F17"
        else -> "#FFB71C1C"
    }

    val isRtk: Boolean get() = fixQuality == 4 || fixQuality == 5

    val timestampFormatted: String
        get() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }.format(Date(timestamp))

    val zoneName: String
        get() = Vn2000Zone.zoneName(centralMeridian, zoneWidthDeg)

    /** Dòng CSV: pointCode,lat,lon,alt,northing,easting,fix,hdop,sats,timestamp */
    val csvRow: String
        get() = "$pointCode,${"%.8f".format(latitude)},${"%.8f".format(longitude)}," +
                "${"%.4f".format(altitude)},${"%.3f".format(northing)},${"%.3f".format(easting)}," +
                "$fixLabel,${"%.2f".format(hdop)},$satelliteCount,$timestampFormatted"
}
