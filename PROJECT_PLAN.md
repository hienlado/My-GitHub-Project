# RTK Field Software — Kế hoạch phát triển toàn diện

> **Mục tiêu:** Xây dựng ứng dụng Android All-in-One thu thập dữ liệu RTK ngoài thực địa,
> tương đương Trimble Access / Leica Captivate nhưng tự xây dựng.

---

## 1. Tổng quan yêu cầu

| Hạng mục | Chi tiết |
|---|---|
| **Kết nối thiết bị** | Bluetooth Classic (SPP/RFCOMM), WiFi / TCP-IP |
| **Chuẩn GNSS** | NMEA 0183 (GGA, GSA, GSV, RMC, VTG) |
| **Hiệu chỉnh RTK** | NTRIP Client (gửi GGA, nhận RTCM 3.x) |
| **Hệ tọa độ** | WGS-84, VN-2000 (múi chiếu 3° và 6°) |
| **Thu thập dữ liệu** | Đo điểm, đo tuyến (Traverse), cắm mốc (Stakeout) |
| **Bản đồ nền** | OSM, Google Maps, Vector tile server tự xây (Google Cloud) |
| **Xuất dữ liệu** | CSV, Excel (.xlsx), DXF, Shapefile (.shp) |
| **Nền tảng** | Android (minSdk 29), Kotlin, Jetpack Compose |

---

## 2. Kiến trúc — MVVM + Clean Architecture

```
app/src/main/java/com/hien/rtkmultidevice/
│
├── core/                          # Không phụ thuộc Android framework
│   ├── connection/
│   │   ├── DeviceConnection.kt    # Interface chung cho mọi loại kết nối
│   │   ├── ConnectionState.kt     # Sealed class trạng thái kết nối
│   │   ├── bluetooth/
│   │   │   ├── BluetoothDeviceSource.kt
│   │   │   └── BluetoothConnectionImpl.kt
│   │   └── tcp/
│   │       └── TcpConnectionImpl.kt
│   ├── gnss/
│   │   ├── nmea/
│   │   │   ├── NmeaParser.kt      # Parse tất cả câu NMEA
│   │   │   ├── GgaData.kt
│   │   │   ├── GsaData.kt
│   │   │   ├── GsvData.kt
│   │   │   └── RmcData.kt
│   │   └── ntrip/
│   │       ├── NtripClient.kt
│   │       └── NtripConfig.kt     # KHÔNG hardcode — lưu DataStore
│   └── coordinate/
│       ├── CoordinateTransformer.kt  # WGS-84 ↔ VN-2000
│       ├── HelmertParams.kt          # Tham số chuyển đổi datum
│       └── ProjectionZone.kt         # Múi chiếu 3°/6°
│
├── data/                          # Implementations của repositories
│   ├── db/
│   │   ├── AppDatabase.kt         # Room database
│   │   ├── entity/
│   │   │   ├── ProjectEntity.kt
│   │   │   ├── SurveyPointEntity.kt
│   │   │   └── TraverseEntity.kt
│   │   └── dao/
│   │       ├── ProjectDao.kt
│   │       └── SurveyPointDao.kt
│   ├── datastore/
│   │   └── AppSettings.kt         # Hilt DI + DataStore Preferences
│   └── repository/
│       ├── ProjectRepositoryImpl.kt
│       ├── DeviceRepositoryImpl.kt
│       └── NtripRepositoryImpl.kt
│
├── domain/                        # Business logic thuần Kotlin
│   ├── model/
│   │   ├── Project.kt
│   │   ├── SurveyPoint.kt         # Điểm đo (tên, tọa độ, độ chính xác)
│   │   ├── TraverseLine.kt
│   │   └── GnssStatus.kt          # Fix type, PDOP, số vệ tinh
│   ├── repository/
│   │   ├── ProjectRepository.kt   # Interface
│   │   └── DeviceRepository.kt    # Interface
│   └── usecase/
│       ├── MeasurePointUseCase.kt
│       ├── AveragePositionUseCase.kt  # Trung bình hoá nhiều epoch
│       ├── StakeoutUseCase.kt
│       ├── CoordinateTransformUseCase.kt
│       └── ExportDataUseCase.kt
│
├── ui/                            # Jetpack Compose
│   ├── navigation/
│   │   └── AppNavGraph.kt
│   ├── screens/
│   │   ├── connection/
│   │   │   ├── ConnectionScreen.kt
│   │   │   └── ConnectionViewModel.kt
│   │   ├── gnss/
│   │   │   ├── GnssScreen.kt      # Màn hình chính: tọa độ live
│   │   │   └── GnssViewModel.kt
│   │   ├── survey/
│   │   │   ├── SurveyScreen.kt    # Đo điểm
│   │   │   ├── TraverseScreen.kt  # Đo tuyến
│   │   │   └── SurveyViewModel.kt
│   │   ├── stakeout/
│   │   │   ├── StakeoutScreen.kt
│   │   │   └── StakeoutViewModel.kt
│   │   ├── map/
│   │   │   ├── MapScreen.kt       # Bản đồ nền + điểm đo
│   │   │   └── MapViewModel.kt
│   │   └── project/
│   │       ├── ProjectScreen.kt   # Quản lý dự án, xuất dữ liệu
│   │       └── ProjectViewModel.kt
│   ├── components/
│   │   ├── GnssStatusBar.kt       # Fix status indicator
│   │   ├── CoordinateDisplay.kt
│   │   └── CompassWidget.kt       # Dùng cho stakeout
│   └── theme/
│       └── AppTheme.kt            # Theme field-friendly (contrast cao)
│
└── export/
    ├── CsvExporter.kt
    ├── DxfExporter.kt
    └── ShapefileExporter.kt
```

---

## 3. Dependencies cần thêm (build.gradle.kts)

```kotlin
// Architecture
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
implementation("com.google.dagger:hilt-android:2.51")
ksp("com.google.dagger:hilt-android-compiler:2.51")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

// Room
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.1.1")

// Navigation
implementation("androidx.navigation:navigation-compose:2.7.7")

// Map
implementation("org.osmdroid:osmdroid-android:6.1.18")
// Hoặc Mapbox / MapLibre cho vector tiles

// Coordinate transformation
implementation("org.locationtech.proj4j:proj4j:1.3.0")

// Export
implementation("org.apache.poi:poi-ooxml:5.2.5")  // Excel
// DXF: tự implement hoặc dùng kabeja
```

---

## 4. Lộ trình phát triển

### Phase 1 — Tái cấu trúc kiến trúc (Tuần 1–2)
- [ ] Chuyển sang Jetpack Compose hoàn toàn (bỏ XML layout)
- [ ] Thêm Hilt dependency injection
- [ ] Thêm Coroutines + Flow, bỏ raw `thread {}`
- [ ] Tách `MainActivity` thành ViewModel + Screen
- [ ] Xoá `BluetoothAdapter.getDefaultAdapter()` → dùng `BluetoothManager`
- [ ] Chuyển NtripConfig từ hardcode → DataStore

### Phase 2 — Quản lý kết nối thiết bị (Tuần 2–3)
- [ ] Màn hình quét Bluetooth (scan + paired devices)
- [ ] Kết nối TCP/WiFi (nhập host:port thủ công)
- [ ] `DeviceConnection` interface thống nhất
- [ ] Tự động nhận diện giao thức NMEA khi kết nối thành công
- [ ] Lưu lịch sử thiết bị đã kết nối (Room DB)

### Phase 3 — GNSS Core (Tuần 3–4)
- [ ] Parse đầy đủ: GGA, GSA, GSV, RMC, VTG
- [ ] `GnssStatusFlow`: StateFlow live tọa độ + Fix type + PDOP + số vệ tinh
- [ ] NTRIP v1 + v2, tự động gửi GGA định kỳ
- [ ] Màn hình GNSS chính: tọa độ live, signal bar vệ tinh, Fix indicator

### Phase 4 — Hệ tọa độ (Tuần 4–5)
- [ ] Chuyển đổi WGS-84 → VN-2000 (tham số Helmert 7 tham số)
- [ ] Hỗ trợ múi chiếu UTM 3° và 6° cho Việt Nam
- [ ] Cài đặt múi chiếu theo vùng làm việc
- [ ] Hiển thị song song WGS-84 và VN-2000

### Phase 5 — Thu thập dữ liệu (Tuần 5–7)
- [ ] Quản lý Dự án (tạo, đổi tên, xoá)
- [ ] Đo điểm: lưu tên điểm, mô tả, tọa độ, thời gian, fix type
- [ ] Trung bình hoá: đo N epoch lấy trung bình (cải thiện độ chính xác)
- [ ] Đo tuyến (Traverse): đo liên tiếp, tạo polyline
- [ ] Xem danh sách điểm đo trong dự án

### Phase 6 — Cắm mốc Stakeout (Tuần 7–8)
- [ ] Nhập điểm thiết kế (thủ công hoặc từ file CSV)
- [ ] Tính azimuth + khoảng cách từ vị trí hiện tại → điểm cắm
- [ ] Màn hình dẫn hướng: mũi tên la bàn + khoảng cách còn lại
- [ ] Cảnh báo khi đến đúng điểm (bán kính chấp nhận được)

### Phase 7 — Bản đồ nền (Tuần 8–10)
- [ ] Hiển thị điểm đo trên OSM (osmdroid)
- [ ] Hỗ trợ vector tile server tự build (MapLibre GL Android)
- [ ] Cấu hình URL tile server (Google Cloud custom)
- [ ] Layer: điểm đo, tuyến đo, điểm thiết kế

### Phase 8 — Xuất dữ liệu (Tuần 10–12)
- [x] Xuất CSV/TXT (UTF-8, header tuỳ chọn, thứ tự trường + dấu phân cách linh hoạt)
  - [x] Điểm đo (SurveyScreen — xuất tất cả hoặc điểm đã chọn)
  - [x] Tuyến đo (TraverseScreen — xuất từ danh sách tuyến hoặc khi đang đo)
- [ ] Xuất Excel .xlsx (Apache POI)
- [ ] Xuất DXF (điểm + tuyến, tương thích AutoCAD/MicroStation)
- [ ] Xuất Shapefile (.shp + .dbf + .prj) cho GIS
- [ ] Chia sẻ file qua Android Share Intent

---

## 5. Bảo mật — NTRIP Credentials

Credentials NTRIP **không được hardcode** trong source code. Thay bằng:

```kotlin
// Lưu bằng EncryptedSharedPreferences hoặc DataStore
val ntripHost = dataStore.data.map { it[NTRIP_HOST_KEY] ?: "" }
val ntripUser = dataStore.data.map { it[NTRIP_USER_KEY] ?: "" }
// Password: dùng Android Keystore để mã hoá
```

---

## 6. Ghi chú kỹ thuật

### Chuyển đổi VN-2000
Tham số Helmert 7-parameter từ WGS-84 sang VN-2000:
- dX = -192.873, dY = -39.382, dZ = -111.202
- rX = -0.33, rY = 1.685, rZ = 1.851, Scale = 0.252 ppm
- Dùng thư viện `proj4j` để tính toán chính xác

### Fix Status màu sắc (chuẩn ngành)
- `FIXED (4)` → Xanh lá ✅
- `FLOAT (5)` → Vàng ⚠️
- `DGPS (2)` → Cam 🟠
- `SINGLE (1)` → Đỏ 🔴
- `INVALID (0)` → Xám ⬛

### Thread safety
Tất cả I/O dùng `Dispatchers.IO`, update UI dùng `Dispatchers.Main`.
Dùng `SharedFlow` để emit NMEA data từ connection layer lên ViewModel.

---

*Cập nhật lần cuối: 2026-05-25*
*Kỹ sư: Trắc địa Bản đồ — ứng dụng thu thập số liệu RTK ngoài thực địa*
