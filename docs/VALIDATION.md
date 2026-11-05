# MTP 真机验证

| 项目 | 验证结果 |
| --- | --- |
| 设备 | Sony ZV-E10 一代、荣耀 Magic7（Android 17 / API 37），相机 USB 模式为 MTP。 |
| 对象枚举 | `OpenSession`、`GetStorageIDs`、`GetObjectHandles`、`GetThumb`、`GetObject` 实测成功。相册列出 1,316 张 JPEG，按 10 个日期分组。 |
| 预览 | `GetThumb` 的三个样本中位数约 72–77 毫秒，方向信息需要另读文件头。普通打开和翻页复用小图，放大触发原图。 |
| 导入 | 实测多选导入、进度和完整性校验。下载缓存与图库副本的 SHA-256 一致。三个完整 `GetObject` 样本的中位数约 0.66–0.80 秒。 |
| 连接 | 四次重建界面复用会话，一次实际拔插自动重新建立会话。另一次拔插捕获到荣耀图库单独发起 USB 授权。 |
| 构建 | `assembleDebug`、`testDebugUnitTest`、`lintDebug` 通过，74 项单元测试，Lint 0 错误。 |

测试于 2026-11-03 至 2026-11-04 完成。相机 JPEG 中存在 1616×1080 内嵌预览。本机 MTP 测试里，`GetPartialObject` 的非零偏移返回了文件开头，标准 MTP 代表样本返回 160×120 小图。此分支的浏览路径采用小图和按需原图。系统图库授权和相机会话忙仍可能随设备状态出现。

## 已验证 APK

本地文件 `artifacts/framesift-mtp-debug.apk`，9,728,876 字节。构建源码提交 `b370e2d8f4203dce02e8ab768b91d4b8fe1ad8e5`。该 APK 已实际安装并通过哈希核对。

```text
8f70b337e97e02dcb9c272e0efbb968e2a8ea4a0f173c8cf3adbdc7d64313b17  framesift-mtp-debug.apk
```
