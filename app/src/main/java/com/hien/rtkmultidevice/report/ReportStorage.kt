package com.hien.rtkmultidevice.report

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * ReportStorage — Lưu biên bản vào thư mục **Tải xuống (Downloads)**.
 *
 * Vì sao không dùng thư mục riêng của ứng dụng:
 *   `getExternalFilesDir()` nằm trong `Android/data/<package>/` — từ Android 11
 *   trình quản lý file KHÔNG cho người dùng mở thư mục này, nên xuất ra đó
 *   coi như file "biến mất".
 *
 * Downloads là nơi app đã dùng để xuất CSV điểm đo, người dùng đã quen —
 * dùng chung một chỗ cho nhất quán.
 */
object ReportStorage {

    /** Ghi file (văn bản hoặc nhị phân) vào Downloads. Trả Uri của file. */
    fun save(context: Context, fileName: String, bytes: ByteArray, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            // Xoá bản cũ cùng tên để "xuất lại" không sinh ra file (1), (2)...
            deleteIfExists(context, fileName)

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Không tạo được file trong Tải xuống")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw java.io.IOException("Không mở được luồng ghi")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val f = File(dir, fileName)
            f.writeBytes(bytes)
            Uri.fromFile(f)
        }
    }

    /**
     * Đọc lại nội dung văn bản của file trong Downloads theo TÊN.
     * Dùng cho bước: người dùng sửa XML ngoài Downloads → app đọc lại để dựng PDF.
     */
    fun readText(context: Context, fileName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findUri(context, fileName) ?: return null
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } else {
            val f = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (f.exists()) f.readText(Charsets.UTF_8) else null
        }
    }.getOrNull()

    /** Tìm Uri của file trong Downloads theo tên hiển thị. */
    fun findUri(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val f = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            return if (f.exists()) Uri.fromFile(f) else null
        }
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(fileName),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
            }
        }
        return null
    }

    private fun deleteIfExists(context: Context, fileName: String) {
        runCatching {
            findUri(context, fileName)?.let { context.contentResolver.delete(it, null, null) }
        }
    }
}
