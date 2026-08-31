package com.hien.rtkmultidevice.ui.screens.deviceinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.data.datastore.AppSettings
import com.hien.rtkmultidevice.domain.model.DeviceInfo
import com.hien.rtkmultidevice.domain.repository.IDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * DeviceInfoViewModel — CHỈ ĐỌC, cho màn Thiết bị ▸ Thông tin.
 *
 * 🔴 VÌ SAO PHẢI CÓ VIEWMODEL RIÊNG — ĐÃ TRẢ GIÁ MỘT LẦN, ĐỪNG LẶP LẠI:
 *
 * Bản đầu màn này dùng lại `ConnectionViewModel` cho gọn. Nhưng `hiltViewModel()`
 * trong Navigation Compose gắn ViewModel vào NavBackStackEntry, nên mở màn
 * Thông tin là Hilt dựng THÊM MỘT ConnectionViewModel MỚI — không phải cái đang
 * giữ kết nối. Cái mới chạy `init { checkPermissions(); refreshWifiDevice() }`,
 * mà `refreshWifiDevice()` GHI ĐÈ `lastPhoneIp` vào DataStore.
 *
 * Hậu quả với máy Sinov M6 (máy thu tự lấy cải chính từ NtripProxyServer chạy
 * trên điện thoại): khi IP điện thoại trong mạng WiFi của máy thu đổi theo DHCP,
 * màn Kết nối vốn phải cảnh báo "Địa chỉ điện thoại đã đổi X → Y, sửa mục RTK
 * Client trên máy thu". Nhưng nếu người dùng ghé màn Thông tin TRƯỚC, IP mới đã
 * bị ghi đè, `last == ip`, cảnh báo KHÔNG hiện nữa. Máy thu vẫn trỏ IP cũ, không
 * nhận được cải chính, và đứng mãi ở SINGLE — không lời giải thích nào.
 *
 * Nguyên tắc rút ra: **màn hình chỉ để XEM thì không được mượn ViewModel của màn
 * hình có tác dụng phụ.** Dùng chung ViewModel chỉ an toàn khi cả hai màn cùng
 * một NavBackStackEntry, hoặc ViewModel đó không làm gì trong `init`.
 */
@HiltViewModel
class DeviceInfoViewModel @Inject constructor(
    deviceRepository : IDeviceRepository,
    appSettings      : AppSettings
) : ViewModel() {

    /** Máy đã từng kết nối thành công (bảng `devices` trong Room). */
    val recentDevices: StateFlow<List<DeviceInfo>> = deviceRepository
        .getRecentDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Tóm tắt NTRIP đang lưu.
     *
     * ⚠ TUYỆT ĐỐI KHÔNG đưa `password` ra. Màn này là chỗ người ta hay chụp màn
     *   hình gửi cho nhau khi nhờ chỉnh máy.
     */
    val ntripTomTat: StateFlow<String> = appSettings.ntripConfigFlow
        .map { c ->
            if (c.host.isBlank()) ""
            else buildString {
                append(c.host).append(':').append(c.port)
                if (c.normalizedMountPoint.isNotBlank())
                    append("  ·  ").append(c.normalizedMountPoint)
                if (c.username.isNotBlank())
                    append("  ·  tài khoản ").append(c.username)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
}
