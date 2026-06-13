package com.hien.rtkmultidevice.core.gnss

import com.hien.rtkmultidevice.core.connection.DeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * NmeaVerifier — Xác minh thiết bị có phát dữ liệu NMEA hợp lệ sau kết nối.
 *
 * Vấn đề thực tế:
 *   Đôi khi kết nối BT thành công về mặt socket nhưng thiết bị
 *   không phát NMEA (sai baudrate, sai profile, thiết bị không phải GNSS).
 *   Cần xác minh trước khi báo "kết nối thành công" cho user.
 *
 * Quy trình:
 *   1. Lắng nghe nmeaFlow() trong N giây
 *   2. Nếu nhận được ít nhất 1 câu NMEA hợp lệ → thành công
 *   3. Nếu timeout → báo lỗi "Không nhận được tín hiệu NMEA"
 *
 * object → Pure utility class, không cần inject.
 */
object NmeaVerifier {

    /** Timeout chờ tín hiệu NMEA đầu tiên */
    private val VERIFY_TIMEOUT = 8.seconds

    /** Prefix nhận diện câu NMEA hợp lệ */
    private val VALID_NMEA_PREFIXES = listOf(
        "\$GNGGA", "\$GPGGA",   // Fix data
        "\$GNRMC", "\$GPRMC",   // Recommended minimum
        "\$GNGSA", "\$GPGSA",   // DOP + satellites
        "\$GNGSV", "\$GPGSV",   // Satellites in view
        "\$GNVTG", "\$GPVTG",   // Course over ground
        "\$GNGLL", "\$GPGLL"    // Geographic position
    )

    /**
     * Xác minh kết nối có NMEA data trong thời hạn [VERIFY_TIMEOUT].
     *
     * @return VerifyResult.Success nếu nhận được NMEA
     *         VerifyResult.Timeout nếu hết thời gian chờ
     *         VerifyResult.Error nếu có lỗi kết nối
     */
    suspend fun verify(connection: DeviceConnection): VerifyResult =
        withContext(Dispatchers.IO) {
            try {
                // Lấy dòng NMEA đầu tiên hợp lệ trong vòng VERIFY_TIMEOUT giây
                val firstValidLine = connection.nmeaFlow()
                    .timeout(VERIFY_TIMEOUT)
                    .first { line -> isValidNmea(line) }

                VerifyResult.Success(firstValidLine)

            } catch (_: TimeoutCancellationException) {
                VerifyResult.Timeout(
                    "Không nhận được tín hiệu NMEA sau ${VERIFY_TIMEOUT.inWholeSeconds} giây.\n" +
                    "Kiểm tra lại: thiết bị đã bật chưa, đúng Bluetooth device chưa?"
                )
            } catch (e: Exception) {
                VerifyResult.Error(e.message ?: "Lỗi đọc dữ liệu từ thiết bị")
            }
        }

    /**
     * Kiểm tra nhanh một dòng có phải NMEA hợp lệ không.
     * Dùng cả ở NmeaParser để lọc dữ liệu nhiễu.
     */
    fun isValidNmea(line: String): Boolean =
        line.startsWith('$') && VALID_NMEA_PREFIXES.any { line.startsWith(it) }

    /**
     * Kết quả xác minh NMEA.
     */
    sealed class VerifyResult {
        /** Nhận được NMEA hợp lệ, kèm câu đầu tiên */
        data class Success(val firstSentence: String) : VerifyResult()

        /** Hết thời gian chờ */
        data class Timeout(val message: String) : VerifyResult()

        /** Lỗi kết nối */
        data class Error(val message: String) : VerifyResult()
    }
}
