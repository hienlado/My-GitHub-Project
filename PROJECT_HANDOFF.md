# PROJECT HANDOFF — RTK_MultiDevice + Cadastral Pipeline

> **Mục đích file này:** Chuyển toàn bộ ngữ cảnh dự án sang một session Claude khác.
> Ở session mới, mở đúng 3 thư mục dưới rồi dán/đính kèm file này là tiếp tục làm được ngay.
> **Ngôn ngữ làm việc: Tiếng Việt.** Người dùng: Truong — kỹ sư Trắc địa Bản đồ (BR-VT / nay TP.HCM).
> Cập nhật: 2026-07-23.

---

## 0. Tóm tắt 30 giây

Hai dự án song song, phục vụ đo đạc **địa chính GNSS-RTK** ở Bà Rịa–Vũng Tàu:

1. **App Android `RTK_MultiDevice`** (`com.hien.rtkmultidevice`) — sổ tay đo RTK: kết nối máy thu (Bluetooth/TCP), NTRIP client + proxy, bản đồ nền + lớp địa chính (online GCS & offline), đo/định vị (stakeout), COGO, CAD editor + xuất DXF, xuất CSV, cấu hình Base.
2. **Pipeline .NET `CadastralTopologyPipeline`** (trong repo NetTopologySuite) — biến MDB địa chính → topology (NetTopologySuite) → GeoJSON/tiles → đẩy lên **GCS** cho app đọc. Có chế độ incremental + đồng bộ MDB đa máy.

Thư mục (đã mount sẵn):
- `D:\Claude AI Project\RTK_MultiDevice` — **app Android** (dự án đang phát triển chính).
- `D:\GitHub Project\NetTopologySuite` — chứa **pipeline .NET** tại `CadastralTopologyPipeline\`.
- `D:\_STORAGE` — **dữ liệu nguồn** (MDB địa chính theo đơn vị hành chính mới, xlsx giữ mã, bản đồ scan).

---

## 1. Người dùng & quy ước

- **Truong** (hienlado@gmail.com), kỹ sư Trắc địa Bản đồ. Muốn học lập trình ứng dụng RTK/GNSS/GIS/Viễn thám/Bentley **từ cơ bản → nâng cao**.
- **Luôn trả lời bằng Tiếng Việt, súc tích, đi thẳng vấn đề.**
- Địa bàn: **BR-VT**, kinh tuyến trục **107°45′ (107.75°)**, múi **3°**, hệ **VN-2000**.

---

## 2. Thiết bị & quyết định RTK (rất quan trọng cho ngữ cảnh)

| Thiết bị | Vai trò | Ghi chú |
|---|---|---|
| **ComNav T30** | **Base** | Bộ lệnh SinoGNSS (kiểu NovAtel): `UNLOGALL`, `FIX POSITION/FIX AUTO`, `LOG COMx RTCM...`, `SAVECONFIG`. Có radio UHF nội bộ + 4G. |
| **Sino M6 Pro** | **Rover** | Họ SinoGNSS, radio UHF 410–470MHz + NTRIP/4G. |
| **STEC SE101817200071** | Rover (đã test) | Cấu hình qua **web 192.168.10.1** (admin/password); máy phát WiFi, điện thoại nối vào là 192.168.10.2. Vẫn SINGLE vì máy **chưa bật nhận RTCM** → cần đặt Working Mode + datalink=external ở web máy (việc còn tồn). |

**Kênh truyền Base→Rover — người dùng dùng CẢ HAI, trước mắt ưu tiên NTRIP:**
- **NTRIP (ưu tiên hiện tại):** hợp địa hình phân cấp sâu/che khuất vì không cần line-of-sight; **cần 4G ở cả base & rover**. Mô hình 3 vai trò: T30 = NTRIP **Server** đẩy lên **Caster** ← M6 Pro = NTRIP **Client** lấy về (qua SIM máy hoặc qua app relay Bluetooth). Base & rover gặp nhau tại **cùng mountpoint**.
  - **RTK2go** (rtk2go.com:2101) chỉ để **test** — chỉ là caster chuyển tiếp, độ chính xác do **base của bạn** quyết định, không do RTK2go; nhược điểm: máy chủ ở Mỹ (độ trễ VN→US→VN), công khai, best-effort → **không dùng cho địa chính chính thức**.
  - **Sản xuất:** tự dựng caster trên **VPS tại VN** (SNIP/BKG/RTKLIB str2str), hoặc dùng **CORS VNGEONET** (VRS toàn quốc, chính thống — khi đó không cần T30 base).
- **Radio UHF (khi khu đo không có sóng 4G/5G):** RTCM đi thẳng radio→radio, **không qua điện thoại**; app chỉ đọc vị trí qua Bluetooth, **tắt NTRIP**.
  - **5km baseline: độ chính xác OK (RTK tốt tới 10–20km); nút thắt là TẦM SÓNG radio.** Radio nội bộ (~1–2W) ở địa hình chia cắt thường chỉ 1–3km. Để chắc 5km: **điện đài ngoài 25–35W + anten cao đặt điểm cao**, hoặc **repeater**; hạ air baud (9600), giảm gói RTCM (giữ MSM4 1074/1084/1094/1124 @1s, 1006/1033 @10s). Lưu ý pháp lý tần số/công suất ở VN.

**Quy tắc `fixQuality` (NMEA GGA):** 0=NO FIX, 1=SINGLE, 2=DGPS, 4=RTK FIXED, 5=RTK FLOAT.

---

## 3. App Android — `D:\Claude AI Project\RTK_MultiDevice`

### 3.1 Stack
Kotlin, Jetpack **Compose**, **Hilt** DI, **osmdroid** (bản đồ), **DataStore** (AppSettings), **Room** (điểm đo), coroutines/StateFlow. Gói: `com.hien.rtkmultidevice`.

### 3.2 Cấu trúc package (`app/src/main/java/com/hien/rtkmultidevice/`)
```
core/cad            CadModel, CadDrawingHolder, CadDxfExporter        (CAD editor + xuất DXF R12)
core/cogo           Cogo.kt                                           (inverse, area/shoelace, point-by-bearing, intersection)
core/coordinate     Vn2000Converter, Vn2000Zone, GaussKruger, WgsEllipsoid, HelmertTransform (7-param)
core/gnss           GnssDataManager (trung tâm NMEA↔RTCM), NmeaVerifier
core/gnss/nmea      parser NMEA
core/gnss/ntrip     NtripClient, NtripConfig, NtripProxyServer (điện thoại làm caster cho máy WiFi), NtripSourcetable
core/connection/bluetooth  BluetoothConnectionImpl (RFCOMM SPP), BluetoothDeviceSource (RTK_DEVICE_KEYWORDS)
core/connection/tcp        kết nối TCP/WiFi
data/datastore      AppSettings (CoordSettings, BaseConfig...), SecureStringCipher
data/db             Room: dao/entity điểm đo, project
export              CsvExporter, ExportManager, PointFileFormat
ui/screens/baseconfig  BaseConfigScreen/ViewModel, BaseDevice  (MỚI — cấu hình Base theo thiết bị)
ui/screens/map      MapScreen/ViewModel, VectorLayerImporter, CadastralLocalSource/CloudSource, CogoDialog, CadDrawPanel/CadMapDraw, CadastralConvert...
ui/screens/survey   SurveyScreen/ViewModel (đo điểm)
ui/screens/stakeout StakeoutScreen/ViewModel (định vị: nearest/next/prev)
ui/screens/project  ProjectScreen/ViewModel (quản lý job, xuất CSV)
ui/screens/{connection,ntrip,gnss,coordsettings,traverse,main}
```
Asset: `app/src/main/assets/cadastral_convert.json` — ánh xạ **60 xã cũ → 24 đơn vị mới** (tiền tố `xa*`/`phuong*`), có composite key cho xã tách (Phước Hưng).

### 3.3 Tính năng đã làm trong các session gần đây
- **Cấu hình Base (mới, device-oriented):** `AppSettings.BaseConfig(mode, name, lat, lon, ellHeight, antennaHeight, avgSeconds, deviceType="COMNAV_T30")`. `BaseDevice` enum (COMNAV_T30/STEC/GENERIC) sinh **hướng dẫn + chuỗi lệnh** theo máy. 3 chế độ vị trí: Điểm đã biết (nhập VN-2000→WGS-84 qua `inverseVn2000`) / Vị trí hiện tại (capture từ fix) / Bình sai TB. Vào từ tab Thiết bị → thẻ Base (`Screen.BaseConfig`).
- **Chiều cao anten:** thêm `antennaHeight` vào `CoordSettings`; `GnssDataManager` dùng `groundAlt = gga.altitude - antennaHeight`.
- **Datum VN-2000↔WGS-84:** 7-param Helmert trong `HelmertTransform` + `VectorLayerImporter`. **Đã REVERT về hệ số gốc** (lần lật dấu RY/RZ làm sai số ngoài thực địa TĂNG 0.5→1.0m). Kết luận: ~0.5m là **sai số dư cố hữu của 7-param**, xử lý bằng chức năng **"Hiệu chỉnh về mốc chuẩn"** của app (localization/site calibration: `calibN/calibE` cộng vào kết quả), KHÔNG phải bug code.
- **Offline:** `CadastralLocalSource` đọc tờ + chỉ mục chủ sử dụng từ bộ nhớ máy, chỉ file `DC*.mdb`; tìm chủ dùng **streaming `android.util.JsonReader`** (file `_owners.json` ~60MB/641k bản ghi → tránh OOM), bỏ dấu bằng `Character.getType()` (không dùng `\p{Mn}`).
- **COGO:** `Cogo.kt` + `CogoDialog` — inverse, diện tích (shoelace, chọn nhiều điểm có thứ tự), điểm theo phương vị-khoảng cách, giao hội; chọn điểm từ dữ liệu.
- **CAD editor:** vẽ point/line/polygon, snap, layer, nhãn, **xuất DXF R12 (AC1009)** (đã verify bằng ezdxf); độc lập, load-on-demand.
- **Xuất CSV:** `CsvExporter.buildAllMeasuredCsv()` + `ProjectViewModel.exportAllMeasuredPoints()` — gộp **tất cả điểm ĐO GNSS thật** từ mọi job (lọc `fixQuality > 0`, loại điểm import/CAD/thủ công). Nút SaveAlt trên `ProjectScreen`.
- **Bluetooth keywords:** `RTK_DEVICE_KEYWORDS` đã thêm STEC (`STEC/SE1018/SE101`) và SinoGNSS/ComNav (`Sino/ComNav/T30/M6/N5/N6`).
- **NTRIP:** `NtripClient` (rover lấy RTCM), `NtripProxyServer` (điện thoại làm caster nội bộ cho máy nối WiFi).

### 3.4 File build/tiện ích (root)
`build_deploy.bat`, `kiem_loi.bat` (bắt lỗi), `logcat_save.bat`, `Git_push_tudong.bat`, `di_chuyen_sang_D.bat`, `PROJECT_PLAN.md`.

---

## 4. Pipeline .NET — `D:\GitHub Project\NetTopologySuite\CadastralTopologyPipeline`

### 4.1 Chức năng
MDB địa chính (OLEDB) → dựng **topology** (NetTopologySuite) → reproject (ProjNet, TOWGS84) → xuất **GeoJSON/Shapefile/tiles** → đẩy **GCS**. Có **incremental** (MD5 hash) + đồng bộ MDB đa máy qua bucket.

### 4.2 File chính
```
Program.cs                      CLI: --batch-inc / --push / --pull / --cloud-batch ...
Config/PipelineOptions.cs       ExportLocalSheets, MdbBucket, FileFilter=DC*.mdb ...
Pipeline/CadastralProcessor.cs  điều phối xử lý 1 MDB
Pipeline/FolderWatcherService.cs, Worker.cs
Topology/TopologyBuilder.cs, TopologyValidator.cs, AttributeJoiner.cs
IO/MdbParcelReader.cs, MdbAttributeReader.cs, DgnReader.cs, GeoJsonExporter.cs, ShapefileExporter.cs, ShapefileMerger.cs, QcReporter.cs, TopologyErrorExporter.cs
Reproject/CoordinateReprojector.cs
Utils/MetadataExtractor.cs      regex ScaleDir: ^(?:TL|TY\s*LE)\s*[-_]?\s*\d+
Utils/RulesFileParser.cs
Cloud/GcsUploader.cs, BigQueryStreamer.cs, MdbSyncService.cs (push/pull MDB qua bucket)
Cloud/service/main.py           tiler idempotent (Cloud Run)
Cloud/function/main.py, function_whereami/main.py
```
Dữ liệu ra: `data/output/_batch_hashes.json`, `_sheet_index.json`, `sheets/_index.json`, `sheets/_owners.json`.

### 4.3 Lệnh incremental & đa máy
- `build_deploy.bat batch-inc` — chỉ xử lý MDB đã đổi (so MD5).
- `--push` đẩy MDB đã cập nhật lên **bucket GCS mới**; máy khác `--pull` để lấy về (thay cho copy thủ công từ `BDDC_24072025` cũ).

### 4.4 Đơn vị hành chính mới (24) — nguồn `D:\_STORAGE`
Đổi tên thư mục theo tiền tố `xa*`/`phuong*` (vd `phuongbaria`, `phuonglonghuong`, `xachauduc`, `xadatdo`, `xalonghai`, `xangaigiao`…). Bảng chuyển (60 cũ→24 mới) tạo từ các file `DS TO BDDC DON VI SAP NHAP_*_GIU MA.xlsx` (layout BA RIA có 3 block → dò cột "Số tờ" tổng quát). Bản sao bảng cho app: `app/.../assets/cadastral_convert.json`.

---

## 5. Hạ tầng Cloud / GCS

- App đọc lớp địa chính (tiles/GeoJSON) từ **GCS**; converter đẩy lên bucket.
- **Sự cố billing (~80 triệu VND):** nguyên nhân **Cloud Logging 74M** từ vòng lặp retry Eventarc/Cloud Run — **đã sửa `Cloud/service/main.py`** (tiler idempotent). Đã mở dispute **case 73365706**.
- **Kiểm tra rò rỉ key:** `gcp-key.json` **KHÔNG** bị commit lên repo nào (an toàn). Repo công khai chỉ lộ **app API key** `rtk-cadastral-2026-x7k9` + URL function + project id (mức rủi ro thấp, nên xoay key khi rảnh).

### ⚠️ 6. RÀNG BUỘC BẢO MẬT (BẮT BUỘC TUÂN THỦ)
- **`gcp-key.json` và mọi `*-key.json` là bí mật — TUYỆT ĐỐI KHÔNG commit/push lên GitHub.** Chúng nằm trong `.gitignore`. (File `gcp-key.json` hiện có trong `CadastralTopologyPipeline\` local — giữ nguyên, đừng đưa lên git.)
- Repo công khai: **`hienlado/My-GitHub-Project`**. Trước khi push, luôn kiểm tra không có secret.

---

## 7. Lỗi đã gặp & cách sửa (để không lặp lại)

1. **Lật dấu RY/RZ Helmert** → sai số thực địa TĂNG → **đã revert về gốc**. ~0.5m là dư số 7-param, xử lý bằng "Hiệu chỉnh về mốc chuẩn", không sửa code.
2. **Tìm chủ crash / OOM:** `_owners.json` ~60MB → **streaming JsonReader**, tìm theo nút bấm (không theo từng ký tự). Regex `\p{Mn}` không hỗ trợ trên vài máy → `Character.getType()` + `try/catch(Throwable)`.
3. **LazyColumn trong AlertDialog** (kết quả tìm chủ) crash → dùng `forEach` thường.
4. **xlsx BA RIA layout 3 block** → dò cột "Số tờ" tổng quát (lấy 2 cột "Số tờ" cuối).

---

## 8. Việc còn tồn / deferred

- [ ] **STEC device config:** vào web `192.168.10.1` đặt Working Mode (Base/Rover) + datalink=external để máy nhận RTCM đã bơm vào (hiện vẫn SINGLE).
- [ ] **Chỉ báo trạng thái RTK** (age of correction, baseline, HDOP) — để dành session sau.
- [ ] **Nút "Gửi lệnh cấu hình Base" cho ComNav T30** — khi đã kiểm chứng cú pháp lệnh với tài liệu T30 (app đã có đường ghi RTCM/serial nên bổ sung được).
- [ ] **Xoay app API key** `rtk-cadastral-2026-x7k9`.
- [ ] **Người dùng cần BUILD lại app** để kiểm chứng các thay đổi chưa compile (Base config, antenna height, CSV export, CAD, COGO...).
- [ ] (NTRIP sản xuất) dựng **caster riêng VPS tại VN** hoặc dùng **VNGEONET**; RTK2go chỉ để test.

---

## 9. Build & chạy nhanh

**App Android:** mở `D:\Claude AI Project\RTK_MultiDevice` bằng Android Studio, hoặc chạy `build_deploy.bat`. Bắt lỗi: `kiem_loi.bat`; log thiết bị: `logcat_save.bat`.

**Pipeline .NET:** `cd D:\GitHub Project\NetTopologySuite\CadastralTopologyPipeline` → `dotnet run -- --batch-inc` (hoặc `build_deploy.bat`). Cần OLEDB (MDB), .NET 8 (net8.0-windows).

---

## 10. Cách dùng file này ở session mới

1. Mở lại 3 thư mục: `RTK_MultiDevice`, `NetTopologySuite`, `_STORAGE`.
2. Đính kèm/dán nội dung file này vào tin nhắn đầu.
3. Nhắc Claude: *"Đọc PROJECT_HANDOFF.md, trả lời Tiếng Việt, tuân thủ mục 6 (bảo mật key)."*
4. Nêu việc muốn làm tiếp (xem mục 8).

> Ghi chú: session mới **không có lịch sử hội thoại cũ**; file này là nguồn ngữ cảnh chính. Chi tiết code cụ thể luôn đọc trực tiếp từ file nguồn trong 3 thư mục.
