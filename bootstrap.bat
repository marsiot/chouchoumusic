@echo off
setlocal enabledelayedexpansion

REM ===============================================================
REM   chouchou-music one-shot bootstrap
REM   From a fresh git clone: download Gradle + Android SDK,
REM   accept licenses, generate local.properties, build APK,
REM   then install and launch if a device is connected.
REM
REM   Prereqs: Windows 10+ (built-in curl + tar), JDK 17+ on PATH.
REM ===============================================================

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

set "ANDROID_HOME=%PROJECT_DIR%\android-sdk"
set "GRADLE_HOME=%PROJECT_DIR%\gradle-8.2"
set "TEMP=%PROJECT_DIR%\.gradle-tmp"
set "TMP=%PROJECT_DIR%\.gradle-tmp"
set "PACKAGE=com.example.helloworld"
set "MAIN=%PACKAGE%/.MainActivity"

if not exist "%TEMP%" mkdir "%TEMP%"

echo.
echo === chouchou-music bootstrap ===
echo project: %PROJECT_DIR%
echo.

REM ---- [1/5] Check Java ----
java -version >NUL 2>&1
if errorlevel 1 (
    echo [ERROR] Java JDK not found on PATH. Install JDK 17+ first.
    pause
    exit /b 1
)

REM ---- [2/5] Gradle 8.2 ----
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    echo --- [2/5] Downloading Gradle 8.2 from Tencent mirror ---
    if not exist "%PROJECT_DIR%\gradle-8.2-bin.zip" (
        curl -L --connect-timeout 15 ^
            -o "%PROJECT_DIR%\gradle-8.2-bin.zip" ^
            https://mirrors.cloud.tencent.com/gradle/gradle-8.2-bin.zip
        if errorlevel 1 (
            echo [ERROR] Gradle download failed.
            pause
            exit /b 1
        )
    )
    echo Extracting Gradle ...
    pushd "%PROJECT_DIR%"
    tar -xf gradle-8.2-bin.zip
    popd
    if not exist "%GRADLE_HOME%\bin\gradle.bat" (
        echo [ERROR] Gradle extraction failed.
        pause
        exit /b 1
    )
    del "%PROJECT_DIR%\gradle-8.2-bin.zip" >NUL 2>&1
) else (
    echo --- [2/5] Gradle 8.2 already present, skip ---
)

REM ---- [3/5] Android SDK ----
if not exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    echo --- [3/5] Setting up Android SDK ---

    if not exist "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" (
        if not exist "%PROJECT_DIR%\cmdline-tools.zip" (
            echo Downloading cmdline-tools from Tencent mirror ...
            curl -L --connect-timeout 15 ^
                -o "%PROJECT_DIR%\cmdline-tools.zip" ^
                https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-11076708_latest.zip
            if errorlevel 1 (
                echo [ERROR] cmdline-tools download failed.
                echo Browse and pick a working file manually:
                echo   https://mirrors.cloud.tencent.com/AndroidSDK/
                pause
                exit /b 1
            )
        )
        echo Extracting cmdline-tools ...
        if not exist "%ANDROID_HOME%\cmdline-tools" mkdir "%ANDROID_HOME%\cmdline-tools"
        pushd "%ANDROID_HOME%\cmdline-tools"
        tar -xf "%PROJECT_DIR%\cmdline-tools.zip"
        if exist cmdline-tools (
            if exist latest rmdir /S /Q latest
            ren cmdline-tools latest
        )
        popd
        if not exist "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" (
            echo [ERROR] cmdline-tools extraction failed.
            pause
            exit /b 1
        )
        del "%PROJECT_DIR%\cmdline-tools.zip" >NUL 2>&1
    )

    echo Accepting SDK licenses ...
    set "_yes=%TEMP%\sdk-yes.txt"
    if exist "!_yes!" del "!_yes!"
    for /L %%i in (1,1,30) do (
        echo y>> "!_yes!"
    )
    call "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" ^
        --sdk_root="%ANDROID_HOME%" --licenses < "!_yes!" >NUL 2>&1

    echo Installing platform-tools + platforms;android-34 + build-tools;34.0.0 ...
    call "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" ^
        --sdk_root="%ANDROID_HOME%" ^
        "platform-tools" "platforms;android-34" "build-tools;34.0.0"
    if errorlevel 1 (
        echo [ERROR] SDK component install failed. Network or mirror issue?
        pause
        exit /b 1
    )
) else (
    echo --- [3/5] Android SDK already present, skip ---
)

REM ---- [4/5] local.properties ----
if not exist "%PROJECT_DIR%\local.properties" (
    echo --- [4/5] Writing local.properties ---
    set "SDK_PATH=%ANDROID_HOME:\=/%"
    > "%PROJECT_DIR%\local.properties" echo sdk.dir=!SDK_PATH!
) else (
    echo --- [4/5] local.properties already present ---
)

REM ---- [5/5] Build APK ----
echo.
echo --- [5/5] Building APK ---
cd /d "%PROJECT_DIR%"
call "%GRADLE_HOME%\bin\gradle.bat" assembleDebug
if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)

set "APK=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk"

REM ---- Install + launch if a device is connected ----
echo.
echo --- Checking for connected device ---
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
"%ADB%" devices | findstr /R "	device$" >NUL
if errorlevel 1 (
    echo [INFO] No device connected. APK is at:
    echo   %APK%
) else (
    echo Installing APK ...
    "%ADB%" install -r "%APK%"
    "%ADB%" shell am force-stop %PACKAGE%
    "%ADB%" shell am start -n %MAIN%
    echo Launched on device.
)

echo.
echo === bootstrap done ===
pause
