package com.hien.rtkmultidevice

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — bắt buộc phải có @HiltAndroidApp để Hilt
 * sinh ra code DI (Dependency Injection) cho toàn ứng dụng.
 *
 * Đăng ký trong AndroidManifest.xml:
 *   android:name=".RTKApplication"
 */
@HiltAndroidApp
class RTKApplication : Application()
