package com.hien.rtkmultidevice.ui.screens.project

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hien.rtkmultidevice.core.coordinate.Vn2000Zone
import com.hien.rtkmultidevice.data.datastore.AppSettings
import com.hien.rtkmultidevice.domain.model.Project
import com.hien.rtkmultidevice.domain.repository.IProjectRepository
import com.hien.rtkmultidevice.domain.repository.ISurveyPointRepository
import com.hien.rtkmultidevice.export.CsvExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ProjectViewModel — Quản lý danh sách và tạo dự án đo đạc.
 */
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val projectRepo : IProjectRepository,
    private val surveyRepo  : ISurveyPointRepository,
    private val appSettings : AppSettings,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    // ── Danh sách dự án ─────────────────────────────────────
    val projects: StateFlow<List<Project>> = projectRepo.getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Dự án đang hoạt động ────────────────────────────────
    val activeProjectId: StateFlow<Int> = appSettings.activeProjectIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    // ── Dialog tạo dự án mới ─────────────────────────────────
    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    // Form fields
    private val _newName        = MutableStateFlow("")
    val newName: StateFlow<String> = _newName.asStateFlow()

    private val _newDescription = MutableStateFlow("")
    val newDescription: StateFlow<String> = _newDescription.asStateFlow()

    private val _newZoneWidth   = MutableStateFlow(3)
    val newZoneWidth: StateFlow<Int> = _newZoneWidth.asStateFlow()

    private val _newCm          = MutableStateFlow(0.0)   // 0.0 = auto
    val newCm: StateFlow<Double> = _newCm.asStateFlow()

    private val _newPrefix      = MutableStateFlow("P")
    val newPrefix: StateFlow<String> = _newPrefix.asStateFlow()

    val zones3Deg = Vn2000Zone.ZONES_3DEG
    val zones6Deg = Vn2000Zone.ZONES_6DEG

    // ── Error / feedback ────────────────────────────────────
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _exportedFile = MutableStateFlow<File?>(null)
    val exportedFile: StateFlow<File?> = _exportedFile.asStateFlow()

    // ────────────────────────────────────────────────────────
    // Actions
    // ────────────────────────────────────────────────────────

    fun openCreateDialog() {
        _newName.value        = ""
        _newDescription.value = ""
        _newZoneWidth.value   = 3
        _newCm.value          = 0.0
        _newPrefix.value      = "P"
        _showCreateDialog.value = true
    }

    fun dismissCreateDialog() { _showCreateDialog.value = false }

    fun onNameChange(v: String)        { _newName.value = v }
    fun onDescriptionChange(v: String) { _newDescription.value = v }
    fun onZoneWidthChange(v: Int)      { _newZoneWidth.value = v }
    fun onCmChange(v: Double)          { _newCm.value = v }
    fun onPrefixChange(v: String)      { _newPrefix.value = v.uppercase().take(5) }

    fun createProject(onCreated: (projectId: Int) -> Unit) {
        val name = _newName.value.trim()
        if (name.isEmpty()) {
            _error.value = "Tên dự án không được để trống"
            return
        }
        viewModelScope.launch {
            val id = projectRepo.createProject(
                Project(
                    name            = name,
                    description     = _newDescription.value.trim(),
                    zoneWidthDeg    = _newZoneWidth.value,
                    centralMeridian = _newCm.value,
                    pointPrefix     = _newPrefix.value.ifEmpty { "P" }
                )
            )
            appSettings.setActiveProject(id)
            _showCreateDialog.value = false
            onCreated(id)
        }
    }

    fun openProject(projectId: Int, onOpened: () -> Unit) {
        viewModelScope.launch {
            appSettings.setActiveProject(projectId)
            onOpened()
        }
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            projectRepo.deleteProject(project)
            // Nếu đang mở dự án này → clear active
            if (activeProjectId.value == project.id) {
                appSettings.clearActiveProject()
            }
        }
    }

    fun exportCsv(project: Project) {
        viewModelScope.launch {
            try {
                val points = surveyRepo.getPointsByProject(project.id).first()
                val csv = CsvExporter.buildProjectCsv(project, points)
                val exportDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "exports"
                )
                exportDir.mkdirs()
                val safeName = project.name
                    .trim()
                    .ifEmpty { "project_${project.id}" }
                    .replace(Regex("""[\\/:*?"<>|]+"""), "_")
                val file = File(exportDir, "${safeName}_${System.currentTimeMillis()}.csv")
                file.writeText("\uFEFF$csv", Charsets.UTF_8)
                _exportedFile.value = file
            } catch (e: Exception) {
                _error.value = "Lỗi xuất CSV: ${e.message}"
            }
        }
    }

    /**
     * Xuất TẤT CẢ điểm ĐO thực địa từ MỌI job thành 1 CSV.
     * CHỈ lấy điểm đo GNSS (fixQuality > 0). Loại điểm import & nhập tay (fixQuality = 0);
     * điểm CAD không nằm trong DB nên đã tự loại.
     */
    fun exportAllMeasuredPoints() {
        viewModelScope.launch {
            try {
                val allProjects = projectRepo.getAllProjects().first()
                val entries = buildList {
                    allProjects.forEach { proj ->
                        surveyRepo.getPointsByProject(proj.id).first()
                            .filter { it.fixQuality > 0 }
                            .forEach { pt -> add(proj to pt) }
                    }
                }
                if (entries.isEmpty()) {
                    _error.value = "Không có điểm đo thực địa nào để xuất"
                    return@launch
                }
                val csv = CsvExporter.buildAllMeasuredCsv(entries)
                val exportDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "exports"
                )
                exportDir.mkdirs()
                val file = File(exportDir, "all_measured_points_${System.currentTimeMillis()}.csv")
                file.writeText("﻿$csv", Charsets.UTF_8)
                _exportedFile.value = file
            } catch (e: Exception) {
                _error.value = "Lỗi xuất CSV: ${e.message}"
            }
        }
    }

    fun clearExportedFile() {
        _exportedFile.value = null
    }

    fun clearError() { _error.value = null }
}
