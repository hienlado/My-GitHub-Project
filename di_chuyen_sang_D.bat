@echo off
setlocal
set SRC=C:\Users\HPPC\AndroidStudioProjects\RTK_MultiDevice
set DST=D:\Claude AI Project\RTK_MultiDevice

echo ===================================================
echo   DI CHUYEN DU AN RTK SANG D:\Claude AI Project
echo ===================================================
echo Nguon: %SRC%
echo Dich : %DST%
echo.
echo LUU Y QUAN TRONG:
echo   - Hay DONG Android Studio truoc khi chay.
echo   - Script chi SAO CHEP (an toan). Bo qua cache: build, .gradle, .kotlin
echo     (cac thu muc nay tu tao lai khi mo du an).
echo   - Giu nguyen .git de khong mat lien ket GitHub.
echo.
pause

echo.
echo Dang sao chep... (co the mat vai phut)
robocopy "%SRC%" "%DST%" /E /XD "%SRC%\build" "%SRC%\.gradle" "%SRC%\.kotlin" /R:1 /W:1 /NFL /NDL

echo.
if %ERRORLEVEL% LSS 8 (
    echo === SAO CHEP THANH CONG ===
    echo.
    echo Buoc tiep theo:
    echo   1. Mo D:\Claude AI Project\RTK_MultiDevice kiem tra du file.
    echo   2. Neu day du: XOA thu muc cu %SRC%
    echo   3. Mo lai du an trong Android Studio tu vi tri MOI.
    echo   4. Trong Cowork/Claude: ket noi lai thu muc D:\Claude AI Project\RTK_MultiDevice
    echo      de minh tiep tuc lam viec.
) else (
    echo *** CO LOI khi sao chep - xem thong bao ben tren ***
    echo Kiem tra o D: con du dung luong va duong dan hop le khong.
)
echo.
pause
