// PHẢI khai báo import ở đầu file .gradle.kts. Không viết thẳng
// `java.time.LocalDate` trong thân script: `java` đã là thuộc tính của Gradle
// (JavaPluginExtension) nên sẽ báo "Unresolved reference 'time'".
import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)      // Kotlin compiler plugin cho Compose
    alias(libs.plugins.hilt.android)        // Hilt Dependency Injection
    alias(libs.plugins.ksp)                 // Kotlin Symbol Processing (thay kapt)
}

android {
    namespace   = "com.hien.rtkmultidevice"
    compileSdk  = 36

    defaultConfig {
        applicationId         = "com.hien.rtkmultidevice"
        minSdk                = 29
        targetSdk             = 36
        // versionCode = SỐ BUILD (i). Tăng 1 mỗi lần build. Phải là số nguyên
        // tăng dần, nếu không `adb install -r` sẽ báo VERSION_DOWNGRADE.
        versionCode           = 8
        versionName           = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ngày build tự đóng dấu, khỏi sửa tay -> ngăn đằng nào cũng đúng.
        // Ngăn kéo bên hông hiện "Phiên bản 1.0 • build 2.260826" (i.yyMMdd).
        buildConfigField(
            "String", "NGAY_BUILD",
            "\"${LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true      // cần cho buildConfigField NGAY_BUILD ở trên
    }
}

dependencies {
    // ── Core ────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)

    // ── Jetpack Compose ──────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)  // Icon bộ đầy đủ

    // ── Lifecycle / ViewModel ────────────────────────────────
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)        // collectAsStateWithLifecycle

    // ── Navigation Compose ───────────────────────────────────
    implementation(libs.navigation.compose)

    // ── Hilt (Dependency Injection) ──────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── Coroutines ───────────────────────────────────────────
    implementation(libs.coroutines.android)

    // ── Room (Local Database) ────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── DataStore (Lưu cài đặt thay SharedPreferences) ───────
    implementation(libs.datastore.preferences)

    // ── OSM Map (osmdroid) — Phase 7 ────────────────────────
    implementation(libs.osmdroid.android)

    // ── Testing ──────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
