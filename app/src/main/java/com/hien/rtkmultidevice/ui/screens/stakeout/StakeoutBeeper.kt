package com.hien.rtkmultidevice.ui.screens.stakeout

import android.content.Context
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.media.AudioManager
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/**
 * StakeoutBeeper — Phát âm thanh cảnh báo khi tiếp cận điểm thiết kế.
 *
 * Ngưỡng và âm thanh:
 *   • distance > 1.0m    : im lặng
 *   • 0.1m < d ≤ 1.0m   : beep chậm (1 tiếng / 2s) — TONE_CDMA_ALERT_CALL_GUARD
 *   • 0.05m < d ≤ 0.1m  : beep nhanh (1 tiếng / 0.5s) — TONE_PROP_BEEP
 *   • d ≤ 0.05m          : beep liên tục tần số cao — đã đến nơi
 */
class StakeoutBeeper(private val context: Context) {

    // Ngưỡng khoảng cách (mét)
    companion object {
        const val THRESHOLD_NEAR   = 1.0    // bắt đầu beep
        const val THRESHOLD_CLOSE  = 0.1    // beep nhanh hơn
        const val THRESHOLD_ARRIVE = 0.05   // đã đến nơi — beep tần số cao
    }

    private var toneGenerator: ToneGenerator? = null
    private var beeperJob: Job? = null

    private fun getToneGen(): ToneGenerator? {
        return try {
            toneGenerator ?: ToneGenerator(
                AudioManager.STREAM_NOTIFICATION, 85
            ).also { toneGenerator = it }
        } catch (e: Exception) { null }
    }

    /**
     * Cập nhật khoảng cách và phát âm thanh tương ứng.
     * Gọi mỗi khi nhận được vị trí GNSS mới.
     */
    fun updateDistance(distanceM: Double, scope: CoroutineScope) {
        beeperJob?.cancel()

        when {
            distanceM <= THRESHOLD_ARRIVE -> {
                // Đã đến nơi — beep nhanh liên tục
                beeperJob = scope.launch {
                    while (isActive) {
                        getToneGen()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 80)
                        delay(200)
                    }
                }
            }
            distanceM <= THRESHOLD_CLOSE -> {
                // Gần target — beep nhanh mỗi 500ms
                beeperJob = scope.launch {
                    while (isActive) {
                        getToneGen()?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                        delay(500)
                    }
                }
            }
            distanceM <= THRESHOLD_NEAR -> {
                // Tiếp cận — beep chậm mỗi 2 giây
                beeperJob = scope.launch {
                    while (isActive) {
                        getToneGen()?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 80)
                        delay(2000)
                    }
                }
            }
            else -> {
                // Còn xa — im lặng
            }
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
 * rememberStakeoutBeeper — Lifecycle-aware beeper.
 * Tự release khi composable bị dispose.
 */
@Composable
fun rememberStakeoutBeeper(context: Context): StakeoutBeeper {
    val beeper = remember { StakeoutBeeper(context) }
    DisposableEffect(Unit) {
        onDispose { beeper.stop() }
    }
    return beeper
}
