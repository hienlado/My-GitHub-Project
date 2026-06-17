package com.hien.rtkmultidevice.ui.screens.survey

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FixStateBeeper — Âm báo trạng thái chất lượng định vị khi thu thập điểm.
 *
 * Mục đích: kỹ sư ngoài thực địa biết được trạng thái fix mà KHÔNG cần nhìn
 * màn hình — đặc biệt hữu ích khi đang ngắm tiêu/giữ thăng bằng sào.
 *
 * Quy ước âm thanh (lặp lại định kỳ theo trạng thái hiện tại):
 *   • FIXED (4)  : 2 tiếng "bíp" cao, vui tai — mỗi 3 giây (đạt độ chính xác)
 *   • FLOAT (5)  : 1 tiếng trung bình       — mỗi 2 giây (chưa đạt, chờ FIXED)
 *   • DGPS (2)   : 1 tiếng trầm             — mỗi 1.5 giây (độ chính xác thấp)
 *   • SINGLE (1) : 2 tiếng trầm cảnh báo    — mỗi 1.5 giây (chưa hiệu chỉnh)
 *   • NO FIX (0) : im lặng
 *
 * Ngoài ra, khi trạng thái THAY ĐỔI sẽ phát ngay 1 tiếng đánh dấu chuyển cấp,
 * giúp nhận biết tức thì lúc đạt FIXED hoặc bị tụt xuống FLOAT/SINGLE.
 */
class FixStateBeeper {

    private var toneGen: ToneGenerator? = null
    private var loopJob: Job? = null
    private var lastFix: Int = Int.MIN_VALUE

    private fun gen(): ToneGenerator? = try {
        toneGen ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).also { toneGen = it }
    } catch (e: Exception) { null }

    /**
     * Cập nhật trạng thái fix hiện tại và phát âm tương ứng.
     * @param enabled false → tắt mọi âm thanh (dừng vòng lặp).
     */
    fun update(fixQuality: Int, enabled: Boolean, scope: CoroutineScope) {
        if (!enabled) { stop(); lastFix = Int.MIN_VALUE; return }

        // Trạng thái không đổi → giữ nguyên vòng lặp đang chạy
        if (fixQuality == lastFix && loopJob?.isActive == true) return

        val changed = lastFix != Int.MIN_VALUE && fixQuality != lastFix
        lastFix = fixQuality
        loopJob?.cancel()

        // NO FIX → im lặng
        if (fixQuality == 0) return

        // Cấu hình âm theo trạng thái
        val (tone, beeps, period) = when (fixQuality) {
            4    -> Triple(ToneGenerator.TONE_PROP_BEEP2, 2, 3000L)  // FIXED
            5    -> Triple(ToneGenerator.TONE_PROP_BEEP,  1, 2000L)  // FLOAT
            2    -> Triple(ToneGenerator.TONE_PROP_PROMPT, 1, 1500L) // DGPS
            else -> Triple(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2, 1500L) // SINGLE
        }

        loopJob = scope.launch {
            // Tiếng đánh dấu chuyển cấp ngay khi đổi trạng thái
            if (changed) {
                gen()?.startTone(tone, 120)
                delay(220)
            }
            while (isActive) {
                repeat(beeps) {
                    gen()?.startTone(tone, 90)
                    delay(150)
                }
                delay(period)
            }
        }
    }

    fun stop() {
        loopJob?.cancel(); loopJob = null
        toneGen?.release(); toneGen = null
    }
}

/** rememberFixStateBeeper — tự giải phóng khi composable bị dispose. */
@Composable
fun rememberFixStateBeeper(): FixStateBeeper {
    val beeper = remember { FixStateBeeper() }
    DisposableEffect(Unit) { onDispose { beeper.stop() } }
    return beeper
}
