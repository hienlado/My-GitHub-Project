package com.hien.rtkmultidevice.ui.screens.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.data.datastore.AppSettings
import com.hien.rtkmultidevice.domain.repository.ISurveyPointRepository
import com.hien.rtkmultidevice.ui.screens.map.CogoPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * CogoToolsViewModel — chỉ để tab **Công cụ** có danh sách điểm cho COGO.
 *
 * Vì sao có riêng một ViewModel bé thế này thay vì mượn của màn khác:
 * `hiltViewModel()` gắn theo NavBackStackEntry, nên gọi lại ViewModel của màn
 * Bản đồ / Cắm mốc từ đây sẽ dựng ra **một thực thể thứ hai**, và `init` của nó
 * chạy lần nữa. Đã trả giá đúng bẫy này ở `DeviceInfoScreen` ngày 31/08/2026.
 *
 * ⚠ CHỈ ĐỌC. Không ghi, không có tác dụng phụ trong `init`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CogoToolsViewModel @Inject constructor(
    appSettings : AppSettings,
    surveyRepo  : ISurveyPointRepository
) : ViewModel() {

    /** Điểm của dự án đang mở, đã loại điểm chưa có toạ độ phẳng. */
    val points: StateFlow<List<CogoPoint>> =
        appSettings.activeProjectIdFlow
            .flatMapLatest { id ->
                if (id <= 0) flowOf(emptyList())
                else surveyRepo.getPointsByProject(id)
            }
            .map { list ->
                list.filter { it.northing != 0.0 || it.easting != 0.0 }
                    .map { CogoPoint(it.pointCode, it.northing, it.easting) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
