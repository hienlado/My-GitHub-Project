package com.hien.rtkmultidevice.ui.screens.survey

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * SaveBeeper — Âm báo xác nhận đã lưu điểm đo.
 *
 * Khi người dùng nhấn "Lưu" và điểm được ghi thành công, phát một tiếng
 * "bíp" ngắn dứt khoát để kỹ sư ngoài thực địa biết điểm đã lưu mà KHÔNG
 * cần nhìn màn hình.
 *
 * (Trạng thái fix Single/Float/Fixed đã được phân biệt bằng MÀU trên thanh
 *  trạng thái — không cần âm báo riêng cho từng trạng thái.)
 */
class SaveBeeper {

    private var toneGen: ToneGenerator? = null

    private fun gen(): ToneGenerator? = try {
        toneGen ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95).also { toneGen = it }
    } catch (e: Exception) { null }

    /** Phát 1 tiếng xác nhận đã lưu điểm (nếu [enabled]). */
    fun beepSaved(enabled: Boolean) {
        if (!enabled) return
        // Tiếng ngắn, cao, rõ — xác nhận thao tác thành công
        gen()?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    fun stop() {
        toneGen?.release(); toneGen = null
    }
}

/** rememberSaveBeeper — tự giải phóng khi composable bị dispose. */
@Composable
fun rememberSaveBeeper(): SaveBeeper {
    val beeper = remember { SaveBeeper() }
    DisposableEffect(Unit) { onDispose { beeper.stop() } }
    return beeper
}
