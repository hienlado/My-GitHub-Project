package com.hien.rtkmultidevice.ui.screens.map

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hien.rtkmultidevice.core.cad.CadDrawingHolder
import com.hien.rtkmultidevice.core.cad.CadDxfExporter
import com.hien.rtkmultidevice.core.cad.CadType

/** Nút bật/tắt chế độ vẽ CAD (đặt ở thanh công cụ bất kỳ màn nào). */
@Composable
fun CadDrawButton(modifier: Modifier = Modifier) {
    val on by CadDrawingHolder.drawMode.collectAsStateWithLifecycle()
    IconButton(onClick = { CadDrawingHolder.toggleMode() }, modifier = modifier) {
        Icon(
            Icons.Default.Edit, contentDescription = "Vẽ CAD",
            tint = if (on) androidx.compose.ui.graphics.Color(0xFFFFEB3B)
                   else androidx.compose.ui.graphics.Color.White
        )
    }
}

/**
 * Bảng điều khiển VẼ CAD — TỰ HIỆN khi ở chế độ vẽ, tự ẩn khi tắt.
 * Đặt 1 dòng ở bất kỳ màn nào có bản đồ:  CadDrawPanel(modifier)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadDrawPanel(modifier: Modifier = Modifier) {
    val on by CadDrawingHolder.drawMode.collectAsStateWithLifecycle()
    if (!on) return

    val context = LocalContext.current
    val tool by CadDrawingHolder.tool.collectAsStateWithLifecycle()
    val snap by CadDrawingHolder.snapEnabled.collectAsStateWithLifecycle()
    val layer by CadDrawingHolder.layer.collectAsStateWithLifecycle()
    val ip by CadDrawingHolder.inProgress.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("") }
    var layerField by remember { mutableStateOf(layer) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                val dxf = CadDxfExporter.export(CadDrawingHolder.drawing)
                context.contentResolver.openOutputStream(uri)?.use { it.write(dxf.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(context, "Đã xuất DXF", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Lỗi xuất: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // Hàng 1: kiểu đối tượng + snap + đóng
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(CadType.POINT to "Điểm", CadType.LINE to "Đường", CadType.POLYGON to "Vùng")
                    .forEach { (t, name) ->
                        FilterChip(selected = tool == t, onClick = { CadDrawingHolder.setTool(t) }, label = { Text(name) })
                        Spacer(Modifier.width(6.dp))
                    }
                FilterChip(selected = snap, onClick = { CadDrawingHolder.setSnap(!snap) }, label = { Text("Snap") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { CadDrawingHolder.setMode(false) }) {
                    Icon(Icons.Default.Close, contentDescription = "Đóng vẽ")
                }
            }

            // Hàng 2: nhãn + lớp
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Nhãn (tuỳ chọn)") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = layerField, onValueChange = { layerField = it },
                    label = { Text("Lớp") }, singleLine = true, modifier = Modifier.width(110.dp)
                )
            }

            // Hàng 3: thao tác
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { CadDrawingHolder.undoVertex() }, enabled = ip.isNotEmpty()) {
                    Icon(Icons.Default.Undo, null, Modifier.width(18.dp)); Spacer(Modifier.width(4.dp)); Text("Đỉnh")
                }
                Button(onClick = {
                    if (layerField.isNotBlank() && layerField != layer) CadDrawingHolder.setLayer(layerField.trim())
                    val ok = CadDrawingHolder.finish(label.trim())
                    if (!ok) Toast.makeText(context, "Đường cần ≥2 đỉnh, vùng cần ≥3 đỉnh", Toast.LENGTH_SHORT).show()
                    else label = ""
                }) { Text("Hoàn tất") }
                OutlinedButton(onClick = { CadDrawingHolder.removeLastEntity() }) {
                    Icon(Icons.Default.Delete, null, Modifier.width(18.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (CadDrawingHolder.drawing.isEmpty())
                        Toast.makeText(context, "Chưa có đối tượng để xuất", Toast.LENGTH_SHORT).show()
                    else exportLauncher.launch("ban_ve_cad.dxf")
                }) { Text("Xuất DXF") }
            }
        }
    }
}
