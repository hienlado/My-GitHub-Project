@echo off
:: Hiển thị tiếng Việt có dấu
chcp 65001 > nul
cd /d "%~dp0"

echo ===================================================
echo   KIEM TRA LOI BIEN DICH - ghi log cho Claude doc
echo ===================================================
echo.
echo Dang bien dich (compileDebugKotlin)...
echo Log se duoc luu vao: build_log.txt
echo.

:: Bien dich Kotlin va ghi TOAN BO output (ca loi) ra build_log.txt
call gradlew.bat :app:compileDebugKotlin --console=plain > build_log.txt 2>&1

if %ERRORLEVEL% neq 0 (
    echo.
    echo *** CO LOI BIEN DICH - xem build_log.txt ***
    echo Hay bao Claude: "doc build_log.txt va sua loi"
) else (
    echo.
    echo *** BIEN DICH OK - khong co loi ***
)
echo.
pause
