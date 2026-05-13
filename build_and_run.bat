@echo off
setlocal

set PROJECT_DIR=e:\mywork\chouchou-music
set ANDROID_HOME=%PROJECT_DIR%\android-sdk
set TEMP=%PROJECT_DIR%\.gradle-tmp
set TMP=%PROJECT_DIR%\.gradle-tmp
set PATH=%PATH%;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\build-tools\34.0.0

set ADB=%ANDROID_HOME%\platform-tools\adb.exe
set APK=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk
set PACKAGE=com.example.helloworld
set MAIN=%PACKAGE%/.MainActivity

if not exist "%TEMP%" mkdir "%TEMP%"

echo === Building ===
cd /d %PROJECT_DIR%
call "%PROJECT_DIR%\gradle-8.2\bin\gradle.bat" assembleDebug
if errorlevel 1 (
    echo [ERROR] build failed
    pause
    exit /b 1
)

if not exist "%APK%" (
    echo [ERROR] APK not found at %APK%
    pause
    exit /b 1
)

echo.
echo === Connected devices ===
"%ADB%" devices

echo.
echo === Installing %APK% ===
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo [ERROR] install failed - is the device connected and unlocked?
    pause
    exit /b 1
)

echo.
echo === Restarting %PACKAGE% ===
"%ADB%" shell am force-stop %PACKAGE%
"%ADB%" shell am start -n %MAIN%

echo.
echo [DONE] build + install + launch succeeded.
pause
