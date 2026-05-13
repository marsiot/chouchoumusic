@echo off
setlocal

set PROJECT_DIR=e:\mywork\chouchou-music
set ANDROID_HOME=%PROJECT_DIR%\android-sdk
set TEMP=%PROJECT_DIR%\.gradle-tmp
set TMP=%PROJECT_DIR%\.gradle-tmp
set PATH=%PATH%;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\build-tools\34.0.0

if not exist "%TEMP%" mkdir "%TEMP%"

cd /d %PROJECT_DIR%
"%PROJECT_DIR%\gradle-8.2\bin\gradle.bat" assembleDebug %*

pause
