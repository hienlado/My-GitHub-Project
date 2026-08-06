package com.hien.rtkmultidevice.core.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

/**
 * PhoneGpsFallback — Lấy vị trí bằng CHIP GPS CỦA ĐIỆN THOẠI khi chưa có máy thu RTK.
 *
 * Vì sao cần:
 *   Khi làm việc offline / chưa kết nối máy thu, app không có toạ độ nào nên
 *   bản đồ không biết đang ở đâu, không tra được "tôi đang ở thửa nào".
 *   Vị trí điện thoại tuy chỉ chính xác vài mét — KHÔNG dùng để đo — nhưng đủ
 *   để định vị trên bản đồ và mở đúng tờ.
 *
 * Nguyên tắc: chỉ chạy khi KHÔNG có tín hiệu RTK; có RTK là dừng ngay,
 * tuyệt đối không để vị trí điện thoại lẫn vào dữ liệu đo.
 */
class PhoneGpsFallback(private val context: Context) {

    private val tag = "PhoneGpsFallback"
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null

    /** Đang chạy hay không — tránh đăng ký trùng. */
    @Volatile var running = false
        private set

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Bắt đầu nhận vị trí điện thoại.
     * @param onLocation (lat, lon, alt, accuracyMet)
     */
    @SuppressLint("MissingPermission")
    fun start(onLocation: (Double, Double, Double, Float) -> Unit) {
        if (running) return
        if (!hasPermission()) {
            Log.w(tag, "Chưa có quyền vị trí — bỏ qua GPS điện thoại")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        manager = lm

        val l = LocationListener { loc: Location ->
            onLocation(loc.latitude, loc.longitude, loc.altitude, loc.accuracy)
        }
        listener = l

        runCatching {
            // Ưu tiên GPS; thêm NETWORK để có vị trí sớm khi chưa bắt được vệ tinh
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, l, Looper.getMainLooper())

            // Dùng ngay vị trí cuối đã biết cho đỡ phải chờ
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            last?.let { onLocation(it.latitude, it.longitude, it.altitude, it.accuracy) }

            running = true
            Log.d(tag, "Bắt đầu dùng GPS điện thoại (chưa có RTK)")
        }.onFailure { Log.e(tag, "Không bật được GPS điện thoại: ${it.message}") }
    }

    fun stop() {
        listener?.let { runCatching { manager?.removeUpdates(it) } }
        listener = null
        running = false
        Log.d(tag, "Dừng GPS điện thoại")
    }
}
