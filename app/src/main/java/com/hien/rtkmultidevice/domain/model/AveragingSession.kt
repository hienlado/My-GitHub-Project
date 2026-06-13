package com.hien.rtkmultidevice.domain.model

import kotlin.math.sqrt

/**
 * AveragingSession — Phiên đo trung bình nhiều epoch.
 *
 * Nguyên lý: Thu thập N vị trí GNSS liên tiếp → tính trung bình số học
 * → độ lệch chuẩn (Std Dev) → lưu điểm với thống kê chất lượng.
 *
 * Áp dụng: Khi cần độ chính xác cao hơn một lần đo đơn:
 *   - RTK Fixed: trung bình 10-30 epoch → giảm nhiễu mm
 *   - RTK Float: trung bình 60-120 epoch → tiếp cận cm
 *   - SINGLE: không khuyến khích trung bình (nhiễu quá lớn)
 *
 * @param targetEpochs Số epoch mục tiêu (0 = không giới hạn, dừng thủ công)
 */
data class AveragingSession(
    val targetEpochs : Int = 30,
    val samples      : List<EpochSample> = emptyList()
) {
    /** Số epoch đã thu thập */
    val count: Int get() = samples.size

    /** Tiến độ 0.0 → 1.0 (nếu targetEpochs > 0) */
    val progress: Float get() =
        if (targetEpochs > 0) (count.toFloat() / targetEpochs).coerceAtMost(1f) else 0f

    /** Đã đủ epoch mục tiêu chưa */
    val isComplete: Boolean get() =
        targetEpochs > 0 && count >= targetEpochs

    /** --- Trung bình WGS-84 --- */
    val meanLatitude  : Double get() = if (samples.isEmpty()) 0.0 else samples.sumOf { it.latitude  } / count
    val meanLongitude : Double get() = if (samples.isEmpty()) 0.0 else samples.sumOf { it.longitude } / count
    val meanAltitude  : Double get() = if (samples.isEmpty()) 0.0 else samples.sumOf { it.altitude  } / count

    /** --- Trung bình VN-2000 (chỉ tính khi có đủ dữ liệu) --- */
    val validVnSamples get() = samples.filter { it.northing != 0.0 && it.easting != 0.0 }
    val meanNorthing : Double get() = if (validVnSamples.isEmpty()) 0.0 else validVnSamples.sumOf { it.northing } / validVnSamples.size
    val meanEasting  : Double get() = if (validVnSamples.isEmpty()) 0.0 else validVnSamples.sumOf { it.easting  } / validVnSamples.size

    /** --- Độ lệch chuẩn (Std Dev) --- */
    val stdDevNorthing: Double get() = stdDev(validVnSamples.map { it.northing })
    val stdDevEasting : Double get() = stdDev(validVnSamples.map { it.easting  })
    val stdDevAltitude: Double get() = stdDev(samples.map { it.altitude })

    /** --- RMSE 2D (mặt phẳng) --- */
    val rmse2D: Double get() = sqrt(stdDevNorthing * stdDevNorthing + stdDevEasting * stdDevEasting)

    /** Loại fix phổ biến nhất */
    val dominantFixQuality: Int get() = samples.groupBy { it.fixQuality }
        .maxByOrNull { it.value.size }?.key ?: 0

    /** Trung bình HDOP */
    val meanHdop: Double get() = if (samples.isEmpty()) 0.0 else samples.sumOf { it.hdop } / count

    /** Format RMSE 2D để hiển thị */
    val rmse2DFormatted: String get() = when {
        rmse2D < 0.001 -> "<1mm"
        rmse2D < 0.01  -> "${"%.1f".format(rmse2D * 1000)}mm"
        rmse2D < 1.0   -> "${"%.3f".format(rmse2D)}m"
        else           -> "${"%.2f".format(rmse2D)}m"
    }

    companion object {
        private fun stdDev(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val mean = values.sum() / values.size
            val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
            return sqrt(variance)
        }
    }
}

/**
 * EpochSample — Một epoch đo đơn lẻ trong phiên trung bình hoá.
 */
data class EpochSample(
    val latitude    : Double,
    val longitude   : Double,
    val altitude    : Double,
    val northing    : Double = 0.0,
    val easting     : Double = 0.0,
    val fixQuality  : Int,
    val hdop        : Double,
    val satelliteCount: Int,
    val timestampMs : Long = System.currentTimeMillis()
)
