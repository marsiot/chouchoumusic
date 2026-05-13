@echo off
setlocal

set PROJECT_DIR=e:\mywork\chouchou-music
set ADB=%PROJECT_DIR%\android-sdk\platform-tools\adb.exe
set APK=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk
set PACKAGE=com.example.helloworld
set MAIN=%PACKAGE%/.MainActivity

if not exist "%ADB%" (
    echo [ERROR] ADB not found at %ADB%
    echo run install_sdk_components.bat first to install platform-tools.
    pause
    exit /b 1
)

if not exist "%APK%" (
    echo [ERROR] APK not found at %APK%
    echo run build_apk.bat first.
    pause
    exit /b 1
)

echo == Checking connected devices ==
"%ADB%" devices
echo.

echo == Installing %APK% ==
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo [ERROR] install failed
    pause
    exit /b 1
)

echo.
echo == Force-stopping %PACKAGE% ==
"%ADB%" shell am force-stop %PACKAGE%

echo == Launching %MAIN% ==
"%ADB%" shell am start -n %MAIN%

echo.
echo [DONE] App should now be running on the device.
pause
