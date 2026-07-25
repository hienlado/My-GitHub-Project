package com.hien.rtkmultidevice.ui.screens.ntrip

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.gnss.ntrip.NtripConfig
import com.hien.rtkmultidevice.core.gnss.ntrip.NtripMountpointEntry
import com.hien.rtkmultidevice.core.gnss.ntrip.NtripSourcetableFetcher
import com.hien.rtkmultidevice.data.datastore.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * NtripConfigViewModel — Quản lý màn hình cấu hình NTRIP.
 *
 * Load config từ DataStore khi vào màn hình,
 * lưu lại khi user nhấn Save.
 */
@HiltViewModel
class NtripConfigViewModel @Inject constructor(
    private val appSettings: AppSettings,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _host        = MutableStateFlow("")
    private val _port        = MutableStateFlow("2101")
    private val _mountPoint  = MutableStateFlow("")
    private val _username    = MutableStateFlow("")
    private val _password    = MutableStateFlow("")
    private val _isSaving    = MutableStateFlow(false)
    private val _saveSuccess = MutableStateFlow(false)

    // ── Sourcetable browser ─────────────────────────────────
    private val _sourcetableEntries    = MutableStateFlow<List<NtripMountpointEntry>>(emptyList())
    private val _isFetchingSourcetable = MutableStateFlow(false)
    private val _sourcetableError      = MutableStateFlow<String?>(null)

    val host                 : StateFlow<String>                    = _host.asStateFlow()
    val port                 : StateFlow<String>                    = _port.asStateFlow()
    val mountPoint           : StateFlow<String>                    = _mountPoint.asStateFlow()
    val username             : StateFlow<String>                    = _username.asStateFlow()
    val password             : StateFlow<String>                    = _password.asStateFlow()
    val isSaving             : StateFlow<Boolean>                   = _isSaving.asStateFlow()
    val saveSuccess          : StateFlow<Boolean>                   = _saveSuccess.asStateFlow()
    val sourcetableEntries   : StateFlow<List<NtripMountpointEntry>> = _sourcetableEntries.asStateFlow()
    val isFetchingSourcetable: StateFlow<Boolean>                   = _isFetchingSourcetable.asStateFlow()
    val sourcetableError     : StateFlow<String?>                   = _sourcetableError.asStateFlow()

    init {
        // Load cấu hình hiện tại từ DataStore khi ViewModel khởi tạo
        viewModelScope.launch {
            appSettings.ntripConfigFlow.collect { config ->
                _host.value       = config.host
                _port.value       = config.port.toString()
                _mountPoint.value = config.mountPoint
                _username.value   = config.username
                _password.value   = config.password
                return@collect  // Chỉ cần load một lần
            }
        }
    }

    // ── Input handlers ───────────────────────────────────────
    fun onHostChanged(v: String)       { _host.value = v }
    fun onPortChanged(v: String)       { _port.value = v }
    fun onMountPointChanged(v: String) { _mountPoint.value = v }
    fun onUsernameChanged(v: String)   { _username.value = v }
    fun onPasswordChanged(v: String)   { _password.value = v }

    // ── Sourcetable functions ───────────────────────────────

    /**
     * Tải danh sách mountpoint từ NTRIP caster.
     * Dùng host/port/username/password đang nhập hiện tại.
     */
    fun fetchSourcetable() {
        val portInt = _port.value.toIntOrNull() ?: 2101
        if (_host.value.isBlank()) {
            _sourcetableError.value = "Vui lòng nhập địa chỉ Caster trước"
            return
        }
        viewModelScope.launch {
            _isFetchingSourcetable.value = true
            _sourcetableError.value = null
            _sourcetableEntries.value = emptyList()
            val result = NtripSourcetableFetcher.fetch(
                host       = _host.value.trim(),
                port       = portInt,
                username   = _username.value.trim(),
                password   = _password.value,
                appContext = appContext
            )
            result
                .onSuccess { entries ->
                    _sourcetableEntries.value = entries
                    if (entries.isEmpty()) {
                        _sourcetableError.value = "Caster không trả về mountpoint nào"
                    }
                }
                .onFailure { e ->
                    _sourcetableError.value = explainError(e, _host.value.trim(), portInt)
                }
            _isFetchingSourcetable.value = false
        }
    }

    /** Người dùng chọn một mountpoint từ danh sách → điền vào ô Mountpoint. */
    fun selectMountpoint(entry: NtripMountpointEntry) {
        _mountPoint.value = entry.mountpoint
    }

    /**
     * Dịch lỗi kỹ thuật sang gợi ý xử lý cho người đo ngoài thực địa.
     * Phân biệt rõ 3 nhóm: không tới được caster / sai mật khẩu / lỗi khác.
     */
    private fun explainError(e: Throwable, host: String, port: Int): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("after", true) && msg.contains("ms", true) ||
            msg.contains("timeout", true) || msg.contains("ETIMEDOUT", true) ->
                "Không kết nối được tới caster $host:$port (hết thời gian chờ).\n" +
                "• Điện thoại đang nối WiFi của máy thu? → bật Dữ liệu di động (app tự ưu tiên 4G).\n" +
                "• Kiểm tra IP/port caster còn đúng không (IP động có thể đã đổi).\n" +
                "• Mạng có thể chặn port $port — thử 4G nhà mạng khác."
            msg.contains("Unable to resolve host", true) || msg.contains("UnknownHost", true) ->
                "Không phân giải được tên miền '$host' — kiểm tra chính tả hoặc đang mất Internet."
            msg.contains("ECONNREFUSED", true) || msg.contains("refused", true) ->
                "Caster $host từ chối kết nối ở port $port — nhiều khả năng SAI PORT hoặc dịch vụ đang tắt."
            msg.contains("401") || msg.contains("Unauthorized", true) ->
                "Sai tên đăng nhập / mật khẩu caster (mạng đã thông)."
            msg.contains("ENETUNREACH", true) || msg.contains("Network is unreachable", true) ->
                "Không có đường ra Internet — bật Dữ liệu di động hoặc nối WiFi có Internet."
            else -> "Lỗi tải sourcetable: $msg"
        }
    }

    /** Xóa danh sách sourcetable (khi đóng dialog). */
    fun clearSourcetable() {
        _sourcetableEntries.value = emptyList()
        _sourcetableError.value   = null
    }

    /**
     * Lưu cấu hình vào DataStore.
     * suspend → chạy trên coroutine, không block UI.
     */
    fun saveConfig(onSaved: () -> Unit) {
        val portInt = _port.value.toIntOrNull() ?: 2101
        val config  = NtripConfig(
            host       = _host.value.trim(),
            port       = portInt,
            mountPoint = _mountPoint.value.trim().trimStart('/'),
            username   = _username.value.trim(),
            password   = _password.value
        )

        viewModelScope.launch {
            _isSaving.value = true
            appSettings.saveNtripConfig(config)
            _isSaving.value = false
            _saveSuccess.value = true
            onSaved()
        }
    }
}
