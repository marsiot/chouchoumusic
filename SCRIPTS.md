# 项目脚本说明

项目根目录下的 `.bat` 脚本一览。全部针对 Windows 10/11，依赖系统自带的 `curl` 和 `tar`，运行前请确保 **JDK 17+** 在 PATH 里。

| 脚本 | 何时用 | 大致耗时 |
|---|---|---|
| [bootstrap.bat](bootstrap.bat) | 新机器 / 第一次拉代码后跑一次 | 5–10 分钟（首次下载 ~570MB） |
| [build_apk.bat](build_apk.bat) | 只想编 APK，不装到手机 | ~1 分钟 |
| [install_apk.bat](install_apk.bat) | APK 已经编好了，只想装到手机 | ~5 秒 |
| [build_and_run.bat](build_and_run.bat) | 改完代码 → 编 + 装 + 启动一气呵成（**日常开发用这个**） | ~1 分钟 |
| [debug_crash.bat](debug_crash.bat) | App 崩溃要抓堆栈定位 bug | 等到崩溃为止 |

---

## bootstrap.bat — 从零搭建编译环境

**用途**：在干净的 git clone 上一键准备所有编译运行所需的依赖，然后编 APK 并装上手机。

**做了什么**（5 步，每步幂等可重入）：

1. 检查 PATH 里有没有 `java`
2. 下 Gradle 8.2 二进制（腾讯云镜像）→ 解压到 `gradle-8.2/`
3. 下 Android cmdline-tools → 用 `sdkmanager` 接受 licenses + 安装 `platform-tools` / `platforms;android-34` / `build-tools;34.0.0` → 全部安到 `android-sdk/`
4. 生成 `local.properties` 指向本地 SDK
5. 调本地 Gradle 编 `assembleDebug`；如果连了手机就装上去 + 启动 app

**使用方法**：

```
git clone git@gitee.com:marsiot/chouchou-music.git
cd chouchou-music
# 装好 JDK 17+（任意发行版 OpenJDK / Microsoft / Adoptium）并加到 PATH
双击 bootstrap.bat
```

**注意点**：
- 默认从腾讯云镜像下，国内不需要翻墙。镜像 URL 写死在脚本里，万一不可用会报错。
- 已经下过的依赖会跳过（再跑只会走 build + install 那两步）。
- 如果 SDK 安装时 sdkmanager 卡在 license 接受阶段，可能是 stdin 喂 `y` 的方式跟新版 sdkmanager 不兼容；这时手动跑一遍 `android-sdk/cmdline-tools/latest/bin/sdkmanager.bat --licenses` 即可。
- 总下载量约 **570MB**。

---

## build_apk.bat — 只编 APK

**用途**：只跑 Gradle `assembleDebug`，不装设备。改了几行代码想看下能不能编过的时候用。

**做了什么**：
1. 设 `ANDROID_HOME`、`TEMP`、`TMP`（指向 `.gradle-tmp/`，绕开本机 JDK 19 + Windows C: temp 的 AF_UNIX bug）
2. 调本地 Gradle 编 `assembleDebug`

**使用方法**：双击或 `e:\mywork\chouchou-music\build_apk.bat`。
可以传 Gradle 参数：`build_apk.bat clean assembleRelease`。

**前提**：`bootstrap.bat` 已经跑过（要有 `android-sdk/` 和 `gradle-8.2/`）。

**输出**：`app/build/outputs/apk/debug/app-debug.apk`。

---

## install_apk.bat — 装到手机并启动

**用途**：编好的 APK 装到 USB 连接的安卓设备并启动 app。

**做了什么**：
1. 检查 adb 和 APK 存在
2. `adb devices` 列连接的设备
3. `adb install -r` 覆盖安装
4. `force-stop` + `am start` MainActivity

**使用方法**：双击或 `install_apk.bat`。

**前提**：
- APK 已编好（先跑过 `build_apk.bat`）
- 手机连 USB 并已开启**开发者选项 → USB 调试**
- 首次连 USB 要在手机上确认信任此电脑

---

## build_and_run.bat — 编 + 装 + 启动（日常开发用）

**用途**：日常改完代码一键全流程：编 APK → 装到手机 → 启动 app。`build_apk.bat` + `install_apk.bat` 的合并版。

**使用方法**：双击或 `build_and_run.bat`。

---

## debug_crash.bat — 抓崩溃堆栈

**用途**：app 闪退要看具体崩在哪一行时用。实时监听 `adb logcat` 里的 `FATAL EXCEPTION`（Java 崩溃栈）+ 自动写到带时间戳的日志文件。

**做了什么**：
1. 检查 adb / 设备连接
2. 可选地重启 app（拿一次干净的运行）
3. 清空 logcat 缓冲
4. 过滤监听 `AndroidRuntime:E` → 实时打印到 cmd 窗口 + 同步写到 `debug-logs/crash-YYYYMMDD-HHMMSS.log`

**使用方法**：

1. 双击 `debug_crash.bat`
2. 看到提示 `Relaunch the app now? (y/N):` 输入 `y` 让 app 重启（推荐），或 `n` 直接进监听
3. 在手机上重现崩溃
4. 看到 `FATAL EXCEPTION` 块说明抓到了；继续操作也会持续记录
5. 按 `Ctrl+C` 停止
6. 日志文件路径会显示在脚本开头，比如 `debug-logs/crash-20260514-093752.log`

**典型输出**：
```
05-14 11:07:48.090 24179 24179 E AndroidRuntime: FATAL EXCEPTION: main
05-14 11:07:48.090 24179 24179 E AndroidRuntime: Process: com.chouchou.music, PID: 24179
05-14 11:07:48.090 24179 24179 E AndroidRuntime: java.lang.NullPointerException: ...
05-14 11:07:48.090 24179 24179 E AndroidRuntime:    at com.chouchou.music.MainActivity.runNextBatch(MainActivity.java:1151)
   ...
```

**注意点**：
- `debug-logs/` 已在 `.gitignore` 里，日志不会被推到 git。
- 这个脚本只抓 **Java 层的崩溃栈**。原生（C/C++）SIGSEGV 类崩溃不在 `AndroidRuntime:E` 范围里，需要从 tombstones 看。这个项目目前没有 native 代码，所以基本不会碰到。
- 如果 logcat 缓冲很快被刷掉看不见，可以缩短复现时间或者用 `adb logcat -b crash` 单独看 crash buffer。

---

## 常见问题排查

**`java` 没找到**：装 JDK 17+，加到 PATH。推荐 [Microsoft Build of OpenJDK](https://learn.microsoft.com/zh-cn/java/openjdk/) 或 [Adoptium Temurin](https://adoptium.net/)。

**Gradle 报 `Unable to establish loopback connection`**：JDK 19 在 Windows 用户 Temp 路径上有 AF_UNIX bug。本项目所有脚本已经把 `TEMP`/`TMP` 重定向到 `.gradle-tmp/` 绕开。如果你手动跑 Gradle 命令需要自己设这两个变量。

**`adb devices` 显示设备但 `unauthorized`**：手机上**重新插拔 USB**，弹出确认对话框选**始终允许此电脑**。

**国内网络下 Gradle / SDK 镜像不通**：`bootstrap.bat` 用的是腾讯云镜像（`mirrors.cloud.tencent.com`）。如果某天该 URL 失效，可以改为：
- 阿里云：`https://mirrors.aliyun.com/`
- 华为云：`https://mirrors.huaweicloud.com/`

直接编辑脚本里 `curl -L -o ... <URL>` 那几行替换即可。
