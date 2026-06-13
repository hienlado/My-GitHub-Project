// Top-level build file — khai báo plugins cho toàn dự án
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose)      apply false
    alias(libs.plugins.hilt.android)        apply false
    alias(libs.plugins.ksp)                 apply false
}
