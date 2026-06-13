@echo off
:: Định dạng phông chữ hiển thị tiếng Việt có dấu
chcp 65001 > nul

echo ===================================================
echo   TIẾN TRÌNH TỰ ĐỘNG ĐẨY CODE LÊN GITHUB CỦA HIỀN
echo ===================================================

:: 1. Gom tất cả các file thay đổi
echo 1. Đang gom các file thay đổi (git add)...
"C:\Program Files\Git\bin\git.exe" add .

:: 2. Nhập nội dung ghi chú (Commit Message)
echo.
set /p commit_msg="Nhập nội dung bạn đã thay đổi (Ví dụ: Sua giao dien): "

:: Nếu người dùng không nhập gì, tự động đặt tên theo thời gian
if "%commit_msg%"=="" set commit_msg=Cap nhat code ngay %date% lúc %time%

echo.
echo 2. Đang đóng dấu ghi chú (git commit)...
"C:\Program Files\Git\bin\git.exe" commit -m "%commit_msg%"

:: 3. Đẩy code lên GitHub
echo.
echo 3. Đang đẩy code lên GitHub (git push)...
"C:\Program Files\Git\bin\git.exe" push origin main

echo.
echo ===================================================
echo   ĐÃ HOÀN THÀNH! Code đã an toàn trên GitHub.
echo ===================================================
pause