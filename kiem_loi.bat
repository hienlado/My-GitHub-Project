@echo off
setlocal
cd /d "%~dp0"

echo ===================================================
echo   KIEM TRA LOI BIEN DICH - ghi log cho Claude doc
echo ===================================================
echo.
echo Dang bien dich (compileDebugKotlin)...
echo Log luu vao: build_log.txt
echo.

call gradlew.bat :app:compileDebugKotlin --console=plain > build_log.txt 2>&1

if %ERRORLEVEL% neq 0 (
    echo.
    echo *** CO LOI BIEN DICH - xem build_log.txt ***
    echo Bao Claude: "doc build_log.txt va sua loi"
) else (
    echo.
    echo *** BIEN DICH OK - khong co loi ***
)
echo.
pause
