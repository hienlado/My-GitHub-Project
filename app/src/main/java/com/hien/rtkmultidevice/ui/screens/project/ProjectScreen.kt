package com.hien.rtkmultidevice.ui.screens.project

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.domain.model.Project

/**
 * ProjectScreen — Danh sách dự án đo đạc.
 *
 * Luồng UX:
 *   - Mở app → ProjectScreen (chọn/tạo dự án)
 *   - Nhấn dự án → mở → chuyển sang GnssScreen để bắt đầu đo
 *   - FAB [+] → dialog tạo dự án mới
 *   - Long press → dialog xác nhận xoá
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectScreen(
    onProjectSelected : (projectId: Int) -> Unit,
    viewModel         : ProjectViewModel = hiltViewModel()
) {
    val projects        by viewModel.projects.collectAsStateWithLifecycle()
    val activeId        by viewModel.activeProjectId.collectAsStateWithLifecycle()
    val showCreate      by viewModel.showCreateDialog.collectAsStateWithLifecycle()
    val error           by viewModel.error.collectAsStateWithLifecycle()
    val exportedFile    by viewModel.exportedFile.collectAsStateWithLifecycle()
    val context          = LocalContext.current

    var deleteTarget by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RTK Field Software", fontSize = 16.sp)
                        Text("Quản lý dự án đo đạc", fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.8f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    // Xuất TẤT CẢ điểm ĐO thực địa của MỌI job ra 1 CSV (loại import/CAD/nhập tay)
                    IconButton(onClick = { viewModel.exportAllMeasuredPoints() }) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "Xuất tất cả điểm đo (mọi job)", tint = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick            = viewModel::openCreateDialog,
                containerColor     = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "Tạo dự án mới", tint = Color.White)
            }
        }
    ) { padding ->
        if (projects.isEmpty()) {
            EmptyProjectsHint(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onCreateClick = viewModel::openCreateDialog
            )
        } else {
            LazyColumn(
                modifier            = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding      = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "${projects.size} dự án",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project    = project,
                        isActive   = project.id == activeId,
                        onClick    = {
                            viewModel.openProject(project.id) {
                                onProjectSelected(project.id)
                            }
                        },
                        onLongClick = { deleteTarget = project },
                        onExport    = { viewModel.exportCsv(project) }
                    )
                }
                item { Spacer(Modifier.height(72.dp)) } // FAB clearance
            }
        }
    }

    // ── Dialog tạo dự án ──────────────────────────────────
    if (showCreate) {
        CreateProjectDialog(
            viewModel = viewModel,
            onDismiss = viewModel::dismissCreateDialog,
            onCreate  = { onProjectSelected(it) }
        )
    }

    // ── Dialog xác nhận xoá ───────────────────────────────
    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title  = { Text("Xoá dự án?") },
            text   = {
                Text("\"${project.name}\" và ${project.pointCount} điểm đo sẽ bị xoá vĩnh viễn.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(project)
                    deleteTarget = null
                }) {
                    Text("Xoá", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Huỷ") }
            }
        )
    }

    // ── Snackbar lỗi ─────────────────────────────────────
    error?.let { msg ->
        LaunchedEffect(msg) {
            viewModel.clearError()
        }
    }

    exportedFile?.let { file ->
        LaunchedEffect(file) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ CSV"))
            viewModel.clearExportedFile()
        }
    }
}

// ════════════════════════════════════════════════════════════
// Sub-composables
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project     : Project,
    isActive    : Boolean,
    onClick     : () -> Unit,
    onLongClick : () -> Unit,
    onExport    : () -> Unit
) {
    val borderColor = if (isActive) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick     = onClick,
                onLongClick = onLongClick
            ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isActive) 2.dp else 0.5.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon trạng thái
            Icon(
                imageVector        = Icons.Default.FolderOpen,
                contentDescription = null,
                tint     = if (isActive) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))

            // Thông tin dự án
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 16.sp
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Đang mở",
                                fontSize = 9.sp,
                                color    = Color.White,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (project.description.isNotEmpty()) {
                    Text(
                        project.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetaChip("${project.pointCount} điểm")
                    MetaChip(project.coordinateSystem.substringAfter("/ ").substringAfter("/ "))
                    MetaChip(project.lastModifiedFormatted)
                }
            }
            IconButton(onClick = onExport) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Xuất CSV"
                )
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyProjectsHint(
    modifier   : Modifier,
    onCreateClick : () -> Unit
) {
    Column(
        modifier              = modifier,
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector        = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier           = Modifier.size(64.dp),
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Chưa có dự án nào",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Nhấn + để tạo dự án đo đạc mới",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreateClick) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Tạo dự án mới")
        }
    }
}

// ════════════════════════════════════════════════════════════
// Dialog tạo dự án mới
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProjectDialog(
    viewModel : ProjectViewModel,
    onDismiss : () -> Unit,
    onCreate  : (projectId: Int) -> Unit
) {
    val name        by viewModel.newName.collectAsStateWithLifecycle()
    val description by viewModel.newDescription.collectAsStateWithLifecycle()
    val zoneWidth   by viewModel.newZoneWidth.collectAsStateWithLifecycle()
    val cm          by viewModel.newCm.collectAsStateWithLifecycle()
    val prefix      by viewModel.newPrefix.collectAsStateWithLifecycle()
    val zones       = if (zoneWidth == 3) viewModel.zones3Deg else viewModel.zones6Deg

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text("Tạo dự án mới") },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Tên dự án
                OutlinedTextField(
                    value         = name,
                    onValueChange = viewModel::onNameChange,
                    label         = { Text("Tên dự án *") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                // Mô tả
                OutlinedTextField(
                    value         = description,
                    onValueChange = viewModel::onDescriptionChange,
                    label         = { Text("Mô tả (tuỳ chọn)") },
                    maxLines      = 2,
                    modifier      = Modifier.fillMaxWidth()
                )

                // Tiền tố mã điểm
                OutlinedTextField(
                    value         = prefix,
                    onValueChange = viewModel::onPrefixChange,
                    label         = { Text("Tiền tố mã điểm (VD: P → P001)") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                // Múi chiếu
                Text("Múi chiếu:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 6).forEach { w ->
                        FilterChip(
                            selected = zoneWidth == w,
                            onClick  = { viewModel.onZoneWidthChange(w) },
                            label    = { Text("Múi ${w}°") }
                        )
                    }
                }

                // Kinh tuyến trục
                Text("Kinh tuyến trục:", style = MaterialTheme.typography.labelMedium)
                FilterChip(
                    selected = cm == 0.0,
                    onClick  = { viewModel.onCmChange(0.0) },
                    label    = { Text("Tự động theo GPS") }
                )
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(zones.size) { idx ->
                        val zone = zones[idx]
                        FilterChip(
                            selected = cm == zone.centralMeridian,
                            onClick  = { viewModel.onCmChange(zone.centralMeridian) },
                            label    = { Text(zone.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = { viewModel.createProject(onCreate) },
                enabled  = name.isNotEmpty()
            ) {
                Text("Tạo")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}
