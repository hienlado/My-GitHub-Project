package com.hien.rtkmultidevice.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.hien.rtkmultidevice.domain.model.Project
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import com.hien.rtkmultidevice.domain.model.Traverse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ExportManager — Xuất dữ liệu điểm đo ra file và chia sẻ.
 *
 * Hỗ trợ 2 format:
 *   • CSV đầy đủ  — tất cả fields (WGS-84 + VN-2000 + metadata)
 *   • TXT gọn     — pointCode, Northing, Easting, H (dùng trong field)
 *
 * Lưu file:
 *   • Android 10+ (API 29): dùng MediaStore.Downloads — không cần permission
 *   • Android 9-  (API 28): lưu vào Downloads qua File API (cần WRITE_EXTERNAL_STORAGE)
 *
 * Chia sẻ:
 *   • Intent.ACTION_SEND với FileProvider URI — hoạt động với mọi app (email, Drive, Zalo...)
 */
object ExportManager {

    enum class Format(val extension: String, val mimeType: String, val label: String) {
        CSV_FULL("csv", "text/csv", "CSV đầy đủ (WGS-84 + VN-2000)"),
        TXT_FIELD("txt", "text/plain", "TXT khảo sát (Mã điểm, N, E, H)")
    }

    sealed class ExportResult {
        data class Success(val uri: Uri, val fileName: String, val rowCount: Int) : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    /**
     * Xuất dữ liệu dự án ra file và trả về URI.
     * Gọi từ coroutine (Dispatchers.IO nội bộ).
     */
    suspend fun export(
        context  : Context,
        project  : Project,
        points   : List<SurveyPoint>,
        format   : Format = Format.CSV_FULL
    ): ExportResult = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext ExportResult.Error("Dự án chưa có điểm đo nào")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName  = project.name.replace(Regex("[^a-zA-Z0-9_\\-ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠàáâãèéêìíòóôõùúăđĩũơƯăẮẶẤẦẨẪẬắặấầẩẫậ]"), "_")
        val fileName  = "${safeName}_${timestamp}.${format.extension}"
        val content   = buildContent(project, points, format)

        return@withContext try {
            val uri = saveToDownloads(context, fileName, content, format.mimeType)
            ExportResult.Success(uri, fileName, points.size)
        } catch (e: Exception) {
            ExportResult.Error("Lỗi xuất file: ${e.message}")
        }
    }

    /**
     * Xuất theo định dạng linh hoạt (PointFileFormat):
     * thứ tự trường + dấu phân cách + đuôi .csv/.txt do người dùng chọn.
     */
    suspend fun exportFlexible(
        context : Context,
        project : Project,
        points  : List<SurveyPoint>,
        fmt     : PointFileFormat
    ): ExportResult = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext ExportResult.Error("Không có điểm nào để xuất")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName  = project.name.replace(Regex("[^a-zA-Z0-9_\\-ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠàáâãèéêìíòóôõùúăđĩũơƯăẮẶẤẦẨẪẬắặấầẩẫậ]"), "_")
        val fileName  = "${safeName}_${timestamp}.${fmt.extension}"
        val content   = PointFileCodec.build(points, fmt)

        return@withContext try {
            val uri = saveToDownloads(context, fileName, content, fmt.mimeType)
            ExportResult.Success(uri, fileName, points.size)
        } catch (e: Exception) {
            ExportResult.Error("Lỗi xuất file: ${e.message}")
        }
    }

    /**
     * Xuất tuyến đo (Traverse) ra file .csv/.txt theo định dạng linh hoạt.
     * Điểm xuất theo orderIndex; nếu tuyến đóng, KHÔNG lặp lại điểm đầu.
     */
    suspend fun exportTraverse(
        context  : Context,
        traverse : Traverse,
        fmt      : PointFileFormat
    ): ExportResult = withContext(Dispatchers.IO) {
        if (traverse.points.isEmpty()) return@withContext ExportResult.Error("Tuyến chưa có điểm nào")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName  = traverse.name.replace(Regex("[^a-zA-Z0-9_\\-ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠàáâãèéêìíòóôõùúăđĩũơƯăẮẶẤẦẨẪẬắặấầẩẫậ]"), "_")
        val fileName  = "Tuyen_${safeName}_${timestamp}.${fmt.extension}"
        val sorted    = traverse.points.sortedBy { it.orderIndex }
        val content   = PointFileCodec.buildTraverse(sorted, fmt)

        return@withContext try {
            val uri = saveToDownloads(context, fileName, content, fmt.mimeType)
            ExportResult.Success(uri, fileName, sorted.size)
        } catch (e: Exception) {
            ExportResult.Error("Lỗi xuất file: ${e.message}")
        }
    }

    /**
     * Tạo Intent chia sẻ file — mở share sheet để gửi qua email, Drive, Zalo...
     */
    fun createShareIntent(context: Context, uri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Private helpers ──────────────────────────────────────

    private fun buildContent(project: Project, points: List<SurveyPoint>, format: Format): String {
        return when (format) {
            Format.CSV_FULL   -> CsvExporter.buildProjectCsv(project, points)
            Format.TXT_FIELD  -> buildFieldTxt(project, points)
        }
    }

    /**
     * Format TXT gọn cho sử dụng ngoài hiện trường:
     *   Mã điểm   Northing(m)   Easting(m)   H_ell(m)   Fix   Ghi chú
     */
    private fun buildFieldTxt(project: Project, points: List<SurveyPoint>): String {
        val sb = StringBuilder()
        sb.appendLine("# Dự án: ${project.name}")
        sb.appendLine("# Xuất: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}")
        sb.appendLine("# Tổng điểm: ${points.size}")
        sb.appendLine("#")
        sb.appendLine("# Mã_điểm\tNorthing(m)\tEasting(m)\tH_ell(m)\tFix\tHDOP\tVệ_tinh\tGhi_chú")
        sb.appendLine("#" + "-".repeat(100))
        points.forEach { p ->
            sb.appendLine(
                "${p.pointCode}\t" +
                "${"%.3f".format(p.northing)}\t" +
                "${"%.3f".format(p.easting)}\t" +
                "${"%.4f".format(p.altitude)}\t" +
                "${p.fixLabel}\t" +
                "${"%.2f".format(p.hdop)}\t" +
                "${p.satelliteCount}\t" +
                p.note
            )
        }
        return sb.toString()
    }

    /**
     * Lưu file vào thư mục Downloads.
     *
     * Android 10+ (API 29): dùng MediaStore.Downloads — không cần WRITE permission.
     * Android 9-  (API 28): dùng File API vào public Downloads.
     */
    @Suppress("DEPRECATION")
    private fun saveToDownloads(context: Context, fileName: String, content: String, mimeType: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: MediaStore approach
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Không thể tạo file trong Downloads")

            resolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Không thể mở output stream")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            // Android 9-: File API
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val file = File(downloadsDir, fileName)
            file.writeText(content, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }
}
