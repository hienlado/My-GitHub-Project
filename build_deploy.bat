@echo off
:: Hiển thị tiếng Việt có dấu
chcp 65001 > nul
set GIT="C:\Program Files\Git\bin\git.exe"
set ADB=C:\Users\HPPC\AppData\Local\Android\Sdk\platform-tools\adb.exe
cd /d "%~dp0"

echo ===================================================
echo   BUILD -^> PUSH GITHUB -^> CAI DAT -^> LOGCAT
echo ===================================================

:: 1. BUILD TRUOC — ghi log ra build_log.txt de Claude doc neu loi
echo.
echo [1/5] Dang build APK (log luu vao build_log.txt)...
call gradlew.bat assembleDebug --console=plain > build_log.txt 2>&1
if %ERRORLEVEL% neq 0 (
    echo *** BUILD FAILED - xem build_log.txt ***
    echo Code CHUA duoc day len GitHub. Hay bao Claude doc build_log.txt va sua loi.
    pause
    exit /b 1
)
echo BUILD OK

:: 2. Build OK moi gom + commit
echo.
echo [2/5] Dang gom thay doi (git add)...
%GIT% add .
set /p commit_msg="Nhap noi dung thay doi (Enter de tu dat theo thoi gian): "
if "%commit_msg%"=="" set commit_msg=Cap nhat code ngay %date% luc %time%
%GIT% commit -m "%commit_msg%"

:: 3. Day len GitHub
echo.
echo [3/5] Dang day len GitHub (git push)...
%GIT% push origin main

:: 4. Cai dat APK len may
echo.
echo [4/5] Dang cai APK...
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% neq 0 (
    echo INSTALL FAILED! Kiem tra ket noi USB va USB Debugging.
    pause
    exit /b 1
)
echo INSTALL OK

:: 5. Chay app + xem logcat
echo.
echo [5/5] Dang khoi dong app...
"%ADB%" shell am start -n com.hien.rtkmultidevice/.MainActivity
timeout /t 2 > nul
echo.
echo Streaming logcat - Nhan Ctrl+C de dung
"%ADB%" logcat -c
"%ADB%" logcat StakeoutVM:D SurveyVM:D GnssDataManager:D NtripClient:D *:S
