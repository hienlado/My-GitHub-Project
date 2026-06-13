package com.hien.rtkmultidevice.core.gnss.nmea

import com.hien.rtkmultidevice.domain.model.SatelliteInfo

/**
 * GsvData — Dữ liệu một câu $xxGSV (Satellites in View).
 *
 * Một chu kỳ GSV gồm nhiều câu liên tiếp (tối đa 4 vệ tinh/câu).
 * VD: nếu có 12 vệ tinh → cần 3 câu GSV.
 *
 * Ví dụ câu GSV:
 *   $GPGSV,3,1,12,01,75,050,45,03,25,310,38,08,62,120,42,14,10,280,30*7B
 *   │       │ │ │                                                       │
 *   │       │ │ └─ Tổng số vệ tinh nhìn thấy = 12                      │
 *   │       │ └─── Số thứ tự câu này = 1                               │
 *   │       └───── Tổng số câu trong chu kỳ = 3                        └─ Checksum
 *
 * Mỗi nhóm vệ tinh: PRN, elevation(°), azimuth(°), SNR(dBHz)
 */
data class GsvData(

    /** Tổng số câu GSV trong chu kỳ này */
    val totalMessages: Int,

    /** Số thứ tự câu này (1-based) */
    val messageNumber: Int,

    /** Tổng số vệ tinh nhìn thấy trong chu kỳ */
    val totalSatellites: Int,

    /** Danh sách vệ tinh trong câu này (tối đa 4) */
    val satellites: List<SatelliteInfo>,

    /** Hệ thống vệ tinh xác định từ prefix câu */
    val constellation: SatelliteInfo.Constellation,

    /** True nếu đây là câu cuối của chu kỳ */
    val isLastMessage: Boolean = messageNumber == totalMessages
)
