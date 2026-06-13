@echo off
chcp 65001 > nul
echo Saving logcat to file... (Ctrl+C to stop)
echo.
set FILENAME=logcat_%date:~6,4%%date:~3,2%%date:~0,2%_%time:~0,2%%time:~3,2%%time:~6,2%.txt
set FILENAME=%FILENAME: =0%
adb logcat -c
adb logcat NtripClient:D GnssDataManager:D *:S > "%~dp0%FILENAME%"
echo Saved to: %FILENAME%
