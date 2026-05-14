@echo off
setlocal

REM ===============================================================
REM   chouchou-music crash monitor
REM   Streams Android FATAL EXCEPTION stacks from adb logcat,
REM   showing them live and saving to debug-logs/crash-<stamp>.log
REM
REM   Usage: double-click. Reproduce the crash. Ctrl+C to stop.
REM ===============================================================

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
set "ADB=%PROJECT_DIR%\android-sdk\platform-tools\adb.exe"
set "PACKAGE=com.example.helloworld"
set "MAIN=%PACKAGE%/.MainActivity"

if not exist "%ADB%" (
    echo [ERROR] adb not found at %ADB%
    echo Run bootstrap.bat first.
    pause
    exit /b 1
)

"%ADB%" devices | findstr /R "	device$" >NUL
if errorlevel 1 (
    echo [ERROR] No device connected. Plug in USB and enable USB debugging.
    "%ADB%" devices
    pause
    exit /b 1
)

REM Build a timestamped log path
if not exist "%PROJECT_DIR%\debug-logs" mkdir "%PROJECT_DIR%\debug-logs"
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%i"
set "LOG=%PROJECT_DIR%\debug-logs\crash-%STAMP%.log"

echo.
echo === Crash monitor: %PACKAGE% ===
echo Device:
"%ADB%" devices
echo.
echo Log file: %LOG%
echo.

REM Optional: relaunch the app for a clean run
set /p REL="Relaunch the app now? (y/N): "
if /I "%REL%"=="y" (
    "%ADB%" shell am force-stop %PACKAGE%
    "%ADB%" shell am start -n %MAIN% >NUL
    echo App relaunched.
    echo.
)

echo Clearing logcat buffer ...
"%ADB%" logcat -c

echo.
echo ============================================================
echo  Now reproduce the crash on your phone.
echo  Stack traces will appear below AND be saved to the log.
echo  Press Ctrl+C to stop.
echo ============================================================
echo.

REM Filter on AndroidRuntime:E (Java crash stacks) and tee to file.
"%ADB%" logcat AndroidRuntime:E *:S ^
    | powershell -NoProfile -Command "$input | Tee-Object -FilePath '%LOG%'"
