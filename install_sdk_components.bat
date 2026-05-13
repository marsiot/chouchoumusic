@echo off
setlocal

set SDK_ROOT=e:\mywork\chouchou-music\android-sdk
set SDK_TOOLS=%SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat

echo y | %SDK_TOOLS% platforms;android-34 build-tools;34.0.0 --sdk_root=%SDK_ROOT%