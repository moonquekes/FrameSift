# PhotoSync 海量存储版

适合拍摄结束后，把 Sony ZV-E10 一代接到荣耀 Magic7 上挑片。手机通过 USB 海量存储（MSC）读取相机 SD 卡的 `DCIM`，以竖屏相册浏览 JPEG，按拍摄日期分组，默认最新优先。可以折叠日期、整组选中、左右翻看、检查连拍候选，再把选中的原始 JPEG 导入手机图库。

已验证的版本是本地标签 `photosync-zve10-magic7-msc-20261105`，对应源码提交 `bcef3016cd4a3c3b3b73c2d228d6c8a8b1a9f2ea`。真机组合为 ZV-E10 一代、荣耀 Magic7（Android 17 / API 37）和 exFAT SD 卡。应用构建配置为 `minSdk 24`、`compileSdk 36`、`targetSdk 36`。

## 安装和连接

1. 在手机上安装 [已验证的 Debug APK](../PhotoSync-validation/photosync-zve10-magic7-mass-storage-debug.apk)。此前安装的是同签名的 PhotoSync 调试版时，可以直接覆盖安装并保留应用数据。
2. 在 ZV-E10 的「网络」菜单里关闭「PC Remote」和「智能手机连接」，在「设置 → USB 连接」选择「海量存储器」。插入 SD 卡，开机后用支持数据传输的 USB 线连接荣耀手机。 [Sony ZV-E10 USB 连接说明](https://helpguide.sony.net/ilc/2070/v1/en/contents/TP0001117083.html)
3. 打开 PhotoSync。首次连接时点击「选择相机照片文件夹」，在 Android 文件夹选择器中打开相机 SD 卡上的 `DCIM`，选择「使用这个文件夹」并允许。成功后会显示「海量存储 · 已连接」。同一存储卷再次连接时可复用目录授权。
4. 若 Android 弹出照片相关权限，允许 PhotoSync 读取原始照片元数据。这版在 Android 10 及以上请求 `ACCESS_MEDIA_LOCATION`，以保留 JPEG 中已有的 EXIF 定位字段。相机目录授予应用的是持久只读权限。
5. 点按照片进入预览，长按进入多选。确认后点导入，进度分为读取、保存和校验。导入件保存在手机图库的 `Pictures/PhotoSync`，JPEG 文件按原始字节复制并校验 SHA-256。

若应用显示未连接，先确认手机的文件管理能打开相机 SD 卡。手机能看到存储卷、应用仍提示选择目录时，重新选该卷下的 `DCIM`。若系统提示格式化或修复存储卡，取消提示后再检查相机模式和数据线。

## 浏览与配置

| 项目 | 默认行为 |
| --- | --- |
| 日期和排序 | 按 EXIF 拍摄日期分组，默认最新优先。日期标题可折叠，支持整组选择。 |
| 连拍候选 | 默认相邻照片时间差不超过 1 秒，至少 3 张，且 DSC 序号连续。候选由时间和文件序号推断，可在设置的「连拍分组」标签页调整。 |
| 大图 EXIF | 默认显示焦距、镜头、ISO、光圈、快门。在「拍摄参数」标签页选择字段，也可在大图页用 EXIF 按钮开关。 |
| 预览资源 | 网格优先加载可见小图。点开后读取内嵌 1616×1080 中图，有空时按翻页方向预取相邻 1 张中图。 |
| 原图读取 | 双指放大到中图像素不足、主动点「查看原图」或导入时才读取完整 JPEG。等待期间可继续放大和平移中图，松手后保留倍率和画面中心切到原图。 |
| 本机缓存 | 索引存在应用私有文件区，缩略图和 JPEG 文件头使用内存及本机缓存。中图磁盘缓存约 192 MiB，预览用原图缓存软上限约 128 MiB。导入到图库的文件独立保存。 |

连接时会重新枚举目录并校验缓存身份。拍了新照片后重新连接，或在应用里点刷新，即可更新列表。换卡或格式化后，若系统识别为新存储卷，需要重新选 `DCIM`。格式化后的处理依据卷标识和文件指纹实现，真机验证中未格式化 SD 卡。

## Windows 工具链

安装现成 APK 只需要 APK 文件。下面是重编译或复现实验时用过的环境。

| 用途 | 版本和配置 |
| --- | --- |
| Java 和 Gradle | Microsoft OpenJDK **21.0.13.0.2**、Gradle Wrapper **8.13**、Android Gradle Plugin **8.12.0**、Kotlin **2.0.21**。项目使用 JDK 21 工具链，Java/Kotlin 字节码目标为 17。 |
| Android SDK | SDK Platform **Android 36**。Build-Tools **35.0.0** 用于 Gradle 构建，**36.0.0** 用于 APK 验签和对齐检查。Command-line Tools **23.0** 用于安装 SDK 组件。 |
| 原生依赖 | NDK **29.0.14033849**、CMake **3.22.1**。`:libraw` 编译 LibRaw **0.21.4** 和 Little CMS，源码由两个 Git 子模块提供，目标 ABI 为 `arm64-v8a`、`armeabi-v7a`。 |
| 真机调试 | Android Platform-Tools **37.0.1** 的 `adb`。自动手势和截图对照使用 UI Automator、Python **3.11**、Pillow、NumPy 和 OpenCV。 |

工具安装在 `%USERPROFILE%\.codex\tools`。仓库保留了 Gradle Wrapper 的 JAR，Windows 上可在 PowerShell 中直接运行它。显式传入 JDK 路径会覆盖项目配置里的 Linux 路径。在 `PhotoSync` 仓库根目录执行以下命令。首次构建需要能访问 Gradle 发行包、依赖仓库和 Git 子模块的源站。

```powershell
git submodule update --init --recursive

$toolRoot = Join-Path $env:USERPROFILE '.codex\tools'
$env:JAVA_HOME = Join-Path $toolRoot 'android-build\jdk-21.0.13.0.2+1'
$env:ANDROID_HOME = Join-Path $toolRoot 'android-sdk'

& "$env:JAVA_HOME\bin\java.exe" -classpath gradle\wrapper\gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain "-Dorg.gradle.java.home=$env:JAVA_HOME" `
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

编译输出位于 `app/build/outputs/apk/debug/app-debug.apk`。在独立仓库 `PhotoSync-MSC` 里构建时，直接在该仓库根目录运行上方命令。[归档的 PowerShell 构建脚本](../PhotoSync-validation/build.ps1)记录了旧工作目录和当时使用的本地代理配置。需要覆盖手机上这份已验证 APK 时，保留并继续使用同一个调试签名。本机匹配的调试密钥库位于 `%USERPROFILE%\.android\debug.keystore`，应私下保存。

### ADB 安装和日志

手机启用 USB 调试或已配对无线调试后，可用以下命令安装编译产物并查看应用调试日志。`devices -l` 列出多台设备时，在 `install` 或 `shell` 前加 `-s` 和目标设备序列号。

```powershell
$adb = Join-Path $env:USERPROFILE '.codex\tools\android-platform-tools\platform-tools\adb.exe'
& $adb devices -l
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell run-as cn.devcxl.photosync cat files/usb-compat.log
```

## 源码入口

| 模块 | 位置 |
| --- | --- |
| 目录授权、挂载监听和 JPEG 索引 | `album/MassStorageAccess.kt`、`album/MassStorageIndex.kt` |
| 只读文件访问与 MPF 中图 | `album/MassStorageFiles.kt`、`album/JpegMpf.kt` |
| 相册、缩放视口与导入 | `album/CameraAlbumScreen.kt`、`album/AlbumPreviewDialog.kt`、`album/PreviewViewport.kt`、`album/AlbumTransfers.kt` |
| 共享读取队列与旧 PTP/MTP | `ptp/manager/CameraReadQueue.kt`、`ptp/manager/UsbPtpConnectionController.kt` |

上述 Kotlin 路径相对于 `app/src/main/java/cn/devcxl/photosync`。`app/src/debug` 提供测速入口，`app/src/test` 提供单元测试。[查看应用源码](./app/src/main/java/cn/devcxl/photosync)

## 已验证结果和限制

- 两张实拍 JPEG 批量导入后，手机图库副本的 SHA-256 与 SD 卡原文件一致。横拍和竖拍在松手换原图时，画面中心偏移均小于 1 像素。
- 样本中图首次显示约 62–253 毫秒，预加载或磁盘命中约 39–59 毫秒。约 1,300 张 JPEG 首次完整索引约 33–39 秒，复用约 1 秒。一次实际拔插后目录授权保留，荣耀图库未弹出 USB 授权。
- 原图读取仍有波动，实际批量导入中见过 3.75 秒的单张读取。中图速度和全卡索引耗时取决于设备、照片和缓存状态。当前兼容性范围以本机验证组合为准。
- Debug 构建、81 项单元测试、Lint 和 APK 签名校验均已完成。Lint 为 0 错误、61 警告。 [测速报告](../PhotoSync-validation/MASS-STORAGE-REPORT.md) · [真机清单](../PhotoSync-validation/MASS-STORAGE-TEST-CHECKLIST.md) · [APK 与提交校验记录](../PhotoSync-validation/MASS-STORAGE-ARTIFACT.json)

## 回退和使用范围

需要恢复旧 MTP 版时，把相机的「USB 连接」改成「MTP」，用 [旧版 Debug APK](../PhotoSync-validation/photosync-zve10-magic7-debug.apk) 覆盖安装。旧版源码保存在本地分支 `codex/zve10-mtp-stable-20261104`。返回海量存储时，切回相机的「海量存储器」并安装本页开头的 APK。 [旧版恢复说明](../PhotoSync-backups/20261104-200934-mtp-stable/RESTORE.md)

本版基于 devcxl/PhotoSync。审查的上游提交缺少覆盖主仓库的顶层许可证，相关文件和子模块各自的许可见审查记录。本地源码和调试包供个人验证使用，公开分发前须确认授权。 [上游仓库](https://github.com/devcxl/PhotoSync) · [许可审查](../PhotoSync-validation/SOURCE-AUDIT.md)
