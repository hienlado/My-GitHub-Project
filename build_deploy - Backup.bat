@echo off
set ADB=C:\Users\HPPC\AppData\Local\Android\Sdk\platform-tools\adb.exe

cd /d "%~dp0"

echo [1/3] Building APK...
call gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 (
    echo BUILD FAILED!
    pause
    exit /b 1
)
echo BUILD OK

echo [2/3] Installing APK...
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% neq 0 (
    echo INSTALL FAILED! Check USB connection and USB Debugging.
    pause
    exit /b 1
)
echo INSTALL OK

echo [3/3] Starting app...
"%ADB%" shell am start -n com.hien.rtkmultidevice/.MainActivity
timeout /t 2 > nul

echo.
echo Streaming logcat - Press Ctrl+C to stop
"%ADB%" logcat -c
"%ADB%" logcat StakeoutVM:D SurveyVM:D GnssDataManager:D NtripClient:D *:S
