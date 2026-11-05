# 构建与调试

`mass` 和 `mtp` 使用相同的 Android 工具链和应用包名 `cn.devcxl.photosync`。先切到要构建的分支，再从仓库根目录运行命令。安装 `artifacts/` 内的现成 APK 只需手机。

## 工具链

| 组件 | 版本或配置 |
| --- | --- |
| JDK | 21。实测 Microsoft OpenJDK 21.0.13.0.2，Java/Kotlin 字节码目标为 17。 |
| Gradle | Wrapper 8.13、Android Gradle Plugin 8.12.0、Kotlin 2.0.21。 |
| Android SDK | Android 36、Build-Tools 35.0.0。Build-Tools 36.0.0 用于 APK 签名和对齐检查。 |
| 原生工具 | NDK 29.0.14033849、CMake 3.22.1，构建 `arm64-v8a` 和 `armeabi-v7a`。 |
| SDK 管理和调试 | Command-line Tools 23.0 用于安装 SDK 组件，Platform-Tools 37.0.1 用于 ADB 调试。 |
| 应用 API 配置 | `minSdk 24`、`compileSdk 36`、`targetSdk 36`。 |

设置 `JAVA_HOME` 指向 JDK 21，`ANDROID_HOME` 指向 Android SDK。使用 `sdkmanager` 的环境可以按以下组件名安装构建工具。

```powershell
sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0" `
  "ndk;29.0.14033849" "cmake;3.22.1" "platform-tools"
```

## Windows PowerShell

初始化 LibRaw 和 Little CMS 两个 Git 子模块。首次构建还需下载 Gradle 发行包、插件与依赖。依赖已经缓存时，可给 Gradle 加 `--offline` 复验。

```powershell
git submodule update --init --recursive

& (Join-Path $env:JAVA_HOME 'bin\java.exe') -classpath gradle\wrapper\gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain "-Dorg.gradle.java.home=$env:JAVA_HOME" `
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

显式传入 `org.gradle.java.home` 可以覆盖 `gradle.properties` 中的默认值。APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。本地保留的真机验证 APK 和校验文件位于 `artifacts/`，该目录由 Git 忽略，切换分支时两份 APK 都保留。需要覆盖安装时，设备上的应用和目标 APK 须使用相同签名。签名密钥应私下保存。

## ADB

将 Platform-Tools 加入 `PATH`，并在手机上启用 USB 调试或完成无线调试配对。

```powershell
adb devices -l
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell run-as cn.devcxl.photosync cat files/usb-compat.log
```

多设备同时在线时，在 `install` 或 `shell` 前指定 `-s <设备序列号>`。日志标签为 `USB_COMPAT`。单元测试位于 `app/src/test`，测速 Activity 位于 `app/src/debug`。
