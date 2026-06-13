package com.hien.rtkmultidevice.core.gnss.nmea

import com.hien.rtkmultidevice.domain.model.SatelliteInfo

/**
 * NmeaParser — Bộ phân tích câu NMEA 0183 đầy đủ.
 *
 * Hỗ trợ:
 *   ✅ GGA — Vị trí + Fix quality (Phase 1)
 *   ✅ GSA — DOP + Active satellites (Phase 1)
 *   ✅ GSV — Satellites in view + SNR (Phase 3)
 *   ✅ RMC — Vị trí + Tốc độ + Hướng + Ngày (Phase 3)
 *   ✅ VTG — Tốc độ + Hướng chi tiết (Phase 3)
 *   ✅ Checksum verification
 */
object NmeaParser {

    // ════════════════════════════════════════════════════════
    // GGA
    // ════════════════════════════════════════════════════════

    /**
     * Parse $GNGGA / $GPGGA.
     * Cấu trúc: $xxGGA,time,lat,N/S,lon,E/W,fix,sats,hdop,alt,M,geoid,M,,*cs
     */
    fun parseGga(sentence: String): GgaData? {
        if (!sentence.contains("GGA")) return null
        if (!verifyChecksum(sentence)) return null

        return runCatching {
            val nmea  = extractNmea(sentence)          // bỏ binary garbage trước '$'
            val parts = nmea.substringBefore('*').split(",")
            if (parts.size < 10) return null

            val rawLat         = parts[2]
            val latDir         = parts[3]
            val rawLon         = parts[4]
            val lonDir         = parts[5]
            val fixQuality     = parts[6].toIntOrNull() ?: 0
            val satelliteCount = parts[7].toIntOrNull() ?: 0
            val hdop           = parts[8].toDoubleOrNull() ?: 0.0
            val altitude       = parts[9].toDoubleOrNull() ?: 0.0
            val geoidSep       = parts.getOrNull(11)?.toDoubleOrNull() ?: 0.0

            if (rawLat.isEmpty() || rawLon.isEmpty()) return null

            GgaData(
                latitude        = convertLatitude(rawLat, latDir),
                longitude       = convertLongitude(rawLon, lonDir),
                altitude        = altitude,
                satelliteCount  = satelliteCount,
                fixQuality      = fixQuality,
                hdop            = hdop,
                utcTime         = parts[1],
                geoidSeparation = geoidSep
            )
        }.getOrNull()
    }

    // ════════════════════════════════════════════════════════
    // GSA
    // ════════════════════════════════════════════════════════

    /**
     * Parse $GNGSA / $GPGSA.
     * Cấu trúc: $xxGSA,mode,fixmode,sv...,pdop,hdop,vdop*cs
     */
    fun parseGsa(sentence: String): GsaData? {
        if (!sentence.contains("GSA")) return null
        if (!verifyChecksum(sentence)) return null

        return runCatching {
            val nmea  = extractNmea(sentence)
            val parts = nmea.substringBefore('*').split(",")
            if (parts.size < 17) return null

            GsaData(
                selectionMode = parts[1].firstOrNull() ?: 'A',
                fixMode       = parts[2].toIntOrNull() ?: 1,
                satelliteIds  = (3..14).mapNotNull { parts.getOrNull(it)?.toIntOrNull() },
                pdop          = parts.getOrNull(15)?.toDoubleOrNull() ?: 0.0,
                hdop          = parts.getOrNull(16)?.toDoubleOrNull() ?: 0.0,
                vdop          = parts.getOrNull(17)?.toDoubleOrNull() ?: 0.0
            )
        }.getOrNull()
    }

    // ════════════════════════════════════════════════════════
    // GSV — Phase 3
    // ════════════════════════════════════════════════════════

    /**
     * Parse $GPGSV / $GLGSV / $GAGSV / $GBGSV / $GNGSV.
     *
     * Cấu trúc:
     *   $xxGSV,numMsg,msgNum,numSV,[prn,el,az,snr,]...*cs
     *   Tối đa 4 nhóm (prn,el,az,snr) trong một câu.
     *
     * Prefix xác định constellation:
     *   GP=GPS, GL=GLONASS, GA=Galileo, GB=BeiDou, GQ=QZSS, GN=Mixed
     */
    fun parseGsv(sentence: String): GsvData? {
        if (!sentence.contains("GSV")) return null
        if (!verifyChecksum(sentence)) return null

        return runCatching {
            val nmea           = extractNmea(sentence)
            val constellation  = detectConstellation(nmea)
            val parts          = nmea.substringBefore('*').split(",")
            if (parts.size < 4) return null

            val totalMessages  = parts[1].toIntOrNull() ?: 1
            val messageNumber  = parts[2].toIntOrNull() ?: 1
            val totalSatellites = parts[3].toIntOrNull() ?: 0

            // Đọc các nhóm vệ tinh (bắt đầu từ index 4, mỗi nhóm 4 field)
            val satellites = mutableListOf<SatelliteInfo>()
            var idx = 4
            while (idx + 3 <= parts.size) {
                val prn       = parts.getOrNull(idx)?.toIntOrNull()     ?: break
                val elevation = parts.getOrNull(idx + 1)?.toIntOrNull() ?: 0
                val azimuth   = parts.getOrNull(idx + 2)?.toIntOrNull() ?: 0
                val snr       = parts.getOrNull(idx + 3)?.toIntOrNull() ?: 0

                if (prn > 0) {
                    satellites.add(
                        SatelliteInfo(
                            prn           = prn,
                            elevation     = elevation,
                            azimuth       = azimuth,
                            snr           = snr,
                            constellation = constellation
                        )
                    )
                }
                idx += 4
            }

            GsvData(
                totalMessages   = totalMessages,
                messageNumber   = messageNumber,
                totalSatellites = totalSatellites,
                satellites      = satellites,
                constellation   = constellation
            )
        }.getOrNull()
    }

    // ════════════════════════════════════════════════════════
    // RMC — Phase 3
    // ════════════════════════════════════════════════════════

    /**
     * Parse $GNRMC / $GPRMC (Recommended Minimum Specific GNSS Data).
     *
     * Cấu trúc:
     *   $xxRMC,time,status,lat,N/S,lon,E/W,knots,course,date,magvar,dir,mode*cs
     */
    fun parseRmc(sentence: String): RmcData? {
        if (!sentence.contains("RMC")) return null
        if (!verifyChecksum(sentence)) return null

        return runCatching {
            val nmea  = extractNmea(sentence)
            val parts = nmea.substringBefore('*').split(",")
            if (parts.size < 9) return null

            val status    = parts[2].firstOrNull() ?: 'V'
            val rawLat    = parts[3]
            val latDir    = parts[4]
            val rawLon    = parts[5]
            val lonDir    = parts[6]
            val knots     = parts[7].toDoubleOrNull() ?: 0.0
            val course    = parts[8].toDoubleOrNull() ?: 0.0
            val date      = parts.getOrNull(9) ?: ""

            RmcData(
                utcTime     = parts[1],
                status      = status,
                latitude    = if (rawLat.isNotEmpty()) convertLatitude(rawLat, latDir) else 0.0,
                longitude   = if (rawLon.isNotEmpty()) convertLongitude(rawLon, lonDir) else 0.0,
                speedKnots  = knots,
                courseTrue  = course,
                date        = date
            )
        }.getOrNull()
    }

    // ════════════════════════════════════════════════════════
    // VTG — Phase 3
    // ════════════════════════════════════════════════════════

    /**
     * Parse $GNVTG / $GPVTG (Course Over Ground and Ground Speed).
     *
     * Cấu trúc:
     *   $xxVTG,courseTrue,T,courseMag,M,knots,N,kmh,K,mode*cs
     */
    fun parseVtg(sentence: String): VtgData? {
        if (!sentence.contains("VTG")) return null
        if (!verifyChecksum(sentence)) return null

        return runCatching {
            val nmea    = extractNmea(sentence)
            val parts   = nmea.substringBefore('*').split(",")
            if (parts.size < 8) return null

            val courseTrue  = parts[1].toDoubleOrNull() ?: 0.0
            val courseMag   = parts[3].toDoubleOrNull() ?: 0.0
            val knots       = parts[5].toDoubleOrNull() ?: 0.0
            val kmh         = parts[7].toDoubleOrNull() ?: 0.0
            val mode        = parts.getOrNull(9)?.firstOrNull() ?: 'A'

            VtgData(
                courseTrue      = courseTrue,
                courseMagnetic  = courseMag,
                speedKnots      = knots,
                speedKmh        = kmh,
                mode            = mode
            )
        }.getOrNull()
    }

    // ════════════════════════════════════════════════════════
    // Checksum
    // ════════════════════════════════════════════════════════

    /**
     * Xác minh checksum NMEA: XOR tất cả bytes giữa '$' và '*'.
     */
    fun verifyChecksum(sentence: String): Boolean {
        return runCatching {
            val start       = sentence.indexOf('$')
            val asteriskIdx = sentence.indexOf('*')
            if (start < 0 || asteriskIdx < 0 || asteriskIdx >= sentence.length - 2) return false

            val data     = sentence.substring(start + 1, asteriskIdx)
            val checksum = sentence.substring(asteriskIdx + 1, asteriskIdx + 3).toInt(16)
            val computed = data.fold(0) { acc, c -> acc xor c.code }
            computed == checksum
        }.getOrDefault(false)
    }

    // ════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════

    /**
     * Xác định constellation từ 2 ký tự sau '$'.
     *   $GP → GPS, $GL → GLONASS, $GA → Galileo, $GB → BeiDou, $GQ → QZSS
     */
    private fun detectConstellation(sentence: String): SatelliteInfo.Constellation {
        val prefix = sentence.substringAfter('$').take(2).uppercase()
        return when (prefix) {
            "GP" -> SatelliteInfo.Constellation.GPS
            "GL" -> SatelliteInfo.Constellation.GLONASS
            "GA" -> SatelliteInfo.Constellation.GALILEO
            "GB" -> SatelliteInfo.Constellation.BEIDOU
            "GQ" -> SatelliteInfo.Constellation.QZSS
            else -> SatelliteInfo.Constellation.UNKNOWN
        }
    }

    /** Vĩ độ: ddmm.mmmm → độ thập phân */
    private fun convertLatitude(raw: String, direction: String): Double {
        if (raw.length < 4) return 0.0
        val degrees = raw.substring(0, 2).toDouble()
        val minutes = raw.substring(2).toDouble()
        val result  = degrees + minutes / 60.0
        return if (direction.equals("S", ignoreCase = true)) -result else result
    }

    /** Kinh độ: dddmm.mmmm → độ thập phân */
    private fun convertLongitude(raw: String, direction: String): Double {
        if (raw.length < 5) return 0.0
        val degrees = raw.substring(0, 3).toDouble()
        val minutes = raw.substring(3).toDouble()
        val result  = degrees + minutes / 60.0
        return if (direction.equals("W", ignoreCase = true)) -result else result
    }

    /**
     * Trích xuất câu NMEA thực sự từ raw string.
     *
     * Vấn đề: nhiều thiết bị GNSS (đặc biệt CHCNav/ComNav) gửi binary data
     * proprietary ngay trước câu NMEA trên CÙNG một dòng (không có \n phân cách).
     * Ví dụ: [80 bytes binary]$GPGGA,152223.00,...*43
     *
     * Nếu parse toàn bộ string bằng split(","), binary garbage có nhiều comma
     * sẽ làm lệch toàn bộ field index → parse sai (fix=0, sats=1 thay vì fix=1, sats=42).
     *
     * Giải pháp: tìm vị trí '$' đầu tiên và trả về từ đó.
     * verifyChecksum() đã dùng cách này nên checksum vẫn đúng.
     */
    private fun extractNmea(raw: String): String {
        val start = raw.indexOf('$')
        return if (start > 0) raw.substring(start) else raw
    }
}
