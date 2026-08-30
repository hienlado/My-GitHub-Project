package com.hien.rtkmultidevice.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.ui.screens.coordsettings.CoordSettingsViewModel
import com.hien.rtkmultidevice.ui.screens.coordsettings.ReportInfoCard

/**
 * SettingsScreen — Dự án ▸ Cài đặt.
 *
 * Hiện chứa **thông tin đơn vị đo đạc** (in trên Biên bản), vừa dời từ màn
 * "Hệ toạ độ" sang. Lý do dời: đó là thông tin pháp nhân của đơn vị — tên, đại
 * diện, chức vụ, địa chỉ, VPĐD — không phải tham số toán học của phép chiếu.
 * Để lẫn trong "Hệ toạ độ" thì muốn sửa tên công ty phải vào menu múi chiếu.
 *
 * Dùng LẠI `CoordSettingsViewModel` chứ không viết ViewModel mới: state
 * `report` + `updateReport()` + `saveReport()` đã nằm sẵn ở đó và ghi vào cùng
 * một DataStore. Viết VM thứ hai cho cùng bộ khoá là mời gọi hai bản chép
 * lệch nhau.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack : () -> Unit,
    viewModel      : CoordSettingsViewModel = hiltViewModel()
) {
    val report by viewModel.report.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReportInfoCard(
                report   = report,
                onChange = { viewModel.updateReport(it) },
                onSave   = { viewModel.saveReport() }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
