package com.hien.rtkmultidevice.ui.screens.stakeout

import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/**
 * StakeoutBeeper — Âm báo dẫn hướng khi cắm mốc (stakeout).
 *
 * CHỈ phát âm trong 2 trường hợp:
 *   1. ĐẾN GẦN mục tiêu   (acceptRadius < d ≤ nearThreshold)
 *        → "bíp" nhịp chậm, trầm — báo đang tới gần.
 *   2. TRONG DUNG SAI     (d ≤ acceptRadius = bán kính chấp nhận)
 *        → "bíp" nhanh, cao, dứt khoát — báo đã vào đúng điểm, dừng lại đo.
 *
 * Ngoài 2 vùng trên (còn xa) → im lặng.
 *
 * (Trạng thái Single/Float/Fixed được phân biệt bằng MÀU trên thanh trạng
 *  thái — không dùng âm thanh.)
 */
class StakeoutBeeper(private val context: Context) {

    companion object {
        /** Khoảng cách bắt đầu báo "đến gần" (mét) — tối thiểu 1.0 m */
        const val NEAR_BASE_DISTANCE = 1.0
    }

    private var toneGenerator: ToneGenerator? = null
    private var beeperJob: Job? = null

    private fun getToneGen(): ToneGenerator? = try {
        toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            .also { toneGenerator = it }
    } catch (e: Exception) { null }

    /**
     * Cập nhật khoảng cách → phát âm tương ứng.
     * @param distanceM    khoảng cách hiện tại đến mục tiêu (mét)
     * @param acceptRadius bán kính dung sai (mét) — d ≤ giá trị này = đã đến nơi
     */
    fun updateDistance(distanceM: Double, acceptRadius: Double, scope: CoroutineScope) {
        beeperJob?.cancel()

        // Vùng "đến gần" rộng hơn dung sai; tối thiểu 1 m để có thời gian phản ứng
        val nearThreshold = maxOf(NEAR_BASE_DISTANCE, acceptRadius * 3.0)

        when {
            // ── (2) Đã vào trong dung sai → tiếng nhanh, cao, dứt khoát ──
            distanceM <= acceptRadius -> {
                beeperJob = scope.launch {
                    while (isActive) {
                        getToneGen()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 90)
                        delay(250)
                    }
                }
            }
            // ── (1) Đến gần mục tiêu → tiếng nhịp chậm, trầm ──
            distanceM <= nearThreshold -> {
                beeperJob = scope.launch {
                    while (isActive) {
                        getToneGen()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                        delay(700)
                    }
                }
            }
            // ── Còn xa → im lặng ──
            else -> { /* không phát âm */ }
        }
    }

    fun stop() {
        beeperJob?.cancel()
        beeperJob = null
        toneGenerator?.release()
        toneGenerator = null
    }
}

/**
 * rememberStakeoutBeeper — Lifecycle-aware beeper, tự release khi dispose.
 */
@Composable
fun rememberStakeoutBeeper(context: Context): StakeoutBeeper {
    val beeper = remember { StakeoutBeeper(context) }
    DisposableEffect(Unit) {
        onDispose { beeper.stop() }
    }
    return beeper
}
