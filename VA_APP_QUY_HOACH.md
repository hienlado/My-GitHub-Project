# Bản vá app RTK — hiện quy hoạch khi tra thửa

> File mới `QhLookup.kt` **tôi đã ghi thẳng vào cây mã nguồn** rồi:
> `app\src\main\java\com\hien\rtkmultidevice\ui\screens\map\QhLookup.kt`
>
> Còn lại 4 sửa đổi nhỏ dưới đây, tổng cộng ~25 dòng. Làm theo đúng thứ tự.

---

## 1 · `CadastralCloudSource.kt` — thêm slug xã vào `WhereResult`

`_qh` đánh chỉ mục theo **slug** (`xakimlong`), còn `WhereResult` chỉ trả tên hiển thị
(*"Xã Kim Long"*). App đang có hàm `communeSlugFromName()` dò ngược, nhưng nó khớp theo chuỗi
đã bỏ dấu — mong manh. Slug thật **đã có sẵn ở cả hai nguồn**, chỉ đang bị vứt đi.

⚠️ **Thêm `commune` vào CUỐI danh sách tham số**, không phải giữa. Hàm `whereAmI()` gọi
`WhereResult` theo **thứ tự vị trí**, chèn vào giữa là `xaName` âm thầm nhận nhầm giá trị.

### 1a. Sửa data class

```kotlin
data class WhereResult(
    val found: Boolean,
    val xaName: String = "",
    val to: String = "",
    val thua: String = "",
    val dienTich: String = "",
    val tenChu: String = "",
    val message: String = "",
    val commune: String = ""        // ← THÊM DÒNG NÀY (slug thư mục, vd "xakimlong")
)
```

### 1b. Trong `whereAmI()` — Cloud Function đã trả sẵn `"xa"`

Tìm dòng `if (o.optString("result") == "found")` rồi sửa lời gọi ngay dưới nó:

```kotlin
            if (o.optString("result") == "found")
                WhereResult(true, o.optString("xaName"), o.optString("to"), o.optString("thua"),
                    o.optString("dienTich"), o.optString("tenChu"),
                    commune = o.optString("xa"))            // ← THÊM
```

*(Đã kiểm: `Cloud/function_whereami/main.py` dòng 77 trả `"xa": e["commune"]` — dữ liệu có sẵn.)*

---

## 2 · `CadastralLocalSource.kt` — nhánh offline

Trong `whereAmIVn2000()`, chỗ `return@withContext CadastralCloudSource.WhereResult(` — thêm 1 dòng:

```kotlin
                        return@withContext CadastralCloudSource.WhereResult(
                            found = true,
                            xaName = box.communeName,
                            to = box.to,
                            thua = f.soThua.ifBlank { f.label },
                            dienTich = f.dienTich,
                            tenChu = f.chuSuDung,
                            commune = box.commune           // ← THÊM
                        )
```

---

## 3 · `MapViewModel.kt` — tra quy hoạch sau khi biết thửa

### 3a. Thêm state, đặt ngay dưới khối `_whereResult` (khoảng dòng 112)

```kotlin
    // ── Quy hoạch của thửa vừa tra (QHSDD + QHXD) ─────────────
    private val _qhResult = MutableStateFlow<QhLookup.ThuaQh?>(null)
    val qhResult: StateFlow<QhLookup.ThuaQh?> = _qhResult.asStateFlow()

    /**
     * Tra 2 lớp quy hoạch của thửa. Ưu tiên slug thật trong WhereResult;
     * dữ liệu cũ không có thì lùi về dò theo tên xã.
     */
    private fun traQuyHoach(r: CadastralCloudSource.WhereResult?) {
        _qhResult.value = null
        if (r == null || !r.found || r.thua.isBlank()) return
        val slug = if (r.commune.isNotBlank()) r.commune
                   else (communeSlugFromName(r.xaName) ?: return)
        viewModelScope.launch {
            _qhResult.value = QhLookup.tra(
                appContext, slug, r.to, r.thua,
                r.dienTich.replace(',', '.').toDoubleOrNull() ?: 0.0)
        }
    }
```

### 3b. Sửa `clearWhereResult()` — xoá cả kết quả quy hoạch

```kotlin
    fun clearWhereResult() { _whereResult.value = null; _qhResult.value = null }
```

### 3c. Gọi ở **hai** chỗ — sau mỗi dòng `_whereResult.value = r`

Có đúng 2 chỗ: trong `whereAmINow()` (~dòng 128) và `whereAmIVn2000()` (~dòng 176).

```kotlin
                _whereResult.value = r
                traQuyHoach(r)              // ← THÊM
                openSheetOf(r)
```

---

## 4 · `CadastralCloudDialog.kt` — hiện trong hộp thoại

Trong `WhereAmIButton`, thêm 1 dòng lấy state và thay khối `text = { ... }`:

```kotlin
    val result by viewModel.whereResult.collectAsStateWithLifecycle()
    val qh by viewModel.qhResult.collectAsStateWithLifecycle()      // ← THÊM
```

```kotlin
            text = {
                if (r.found) {
                    val sb = StringBuilder()
                        .append(r.xaName).append('\n')
                        .append("Tờ ${r.to} — Thửa ${r.thua}\n")
                        .append("Diện tích: ${r.dienTich} m²\n")
                        .append("Chủ: ${r.tenChu}")
                    qh?.let { q ->
                        if (q.coDuLieu) {
                            sb.append("\n\n")
                            sb.append(QhLookup.khoi("QH sử dụng đất (TT08/2024)", q.sdd))
                            sb.append(QhLookup.khoi("QH xây dựng (TT16/2025)", q.xd))
                            sb.append("Dữ liệu tham khảo — không thay thế trích lục có dấu.")
                        }
                    }
                    Text(sb.toString())
                } else Text(r.message)
            },
```

Hộp thoại có thể dài — bọc `Text` trong `Column(Modifier.verticalScroll(rememberScrollState()))`
nếu thấy tràn. **Đừng dùng `LazyColumn` trong `AlertDialog`** — đã crash một lần rồi.

---

## 5 · Kết quả mong đợi

```
Xã Kim Long
Tờ 54 — Thửa 42
Diện tích: 2841.6 m²
Chủ: ...

QH sử dụng đất (TT08/2024)
  • Đất trồng cây lâu năm — 2.159,6 m² (76%)
  • Đất công trình giao thông — 653,6 m² (23%)
QH xây dựng (TT16/2025)
  • Đất trồng trọt khác — 2.216,4 m² (78%)
  • Đất giao thông — 568,3 m² (20%)
Dữ liệu tham khảo — không thay thế trích lục có dấu.
```

## 6 · Nghiệm thu

1. Chưa chép `_qh` vào máy → phần quy hoạch **không hiện**, phần địa chính vẫn chạy như cũ.
   (`QhLookup.tra` trả `null` khi thiếu file, không ném lỗi.)
2. Chép `_qh` vào `Android\data\com.hien.rtkmultidevice\files\cadastral\sheets\_qh\` → hiện đủ.
3. Chạm thửa **vắt qua đường quy hoạch** → phải thấy **≥ 2 dòng** ở cả hai loại.
   44,2 % số thửa rơi vào trường hợp này; chỉ thấy 1 dòng là đang cắt bớt.
4. Đối chiếu chéo: `SDD` có `Đất công trình giao thông` *n* % thì `XD` phải có `Đất giao thông`
   xấp xỉ *n* % trên **cùng thửa** — hai nguồn độc lập, lệch vài % là bình thường.
5. Thửa Kim Long tờ 54 số 42 phải ra đúng như mục 5 ở trên.

## 7 · Chưa làm trong bản vá này

- **Chạm thửa trên bản đồ** (khác với nút "tôi đang ở thửa nào") chưa gọi `traQuyHoach`.
  Chỗ đó đi qua `VertexTableDialog`, cần biết xã/tờ đang mở — làm sau khi bản vá này chạy được.
- **Lớp nền quy hoạch** (`<tờ>.qh.geojson`) chưa bật. Xem `sheets\_qh\HUONG_DAN_APP.md` mục 3.
