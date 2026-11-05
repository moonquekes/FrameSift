# PhotoSync · MTP 版

通过 USB MTP/PTP 在荣耀 Magic7 上浏览 Sony ZV-E10 一代的 JPEG。手机从相机读取照片列表和缩略图，可按拍摄日期挑片，再把选中的原始 JPEG 保存到手机图库。

## 开始使用

1. 安装本地 `artifacts/photosync-mtp-debug.apk`，或按[构建说明](docs/BUILD.md)生成 APK。
2. 在相机上关闭「PC Remote」和「智能手机连接」，将「设置 → USB 连接」设为「MTP」，然后用数据线连接手机。
3. 打开 PhotoSync。如果 Android 提示允许 PhotoSync 访问相机 USB，选择允许。出现「SONY ZV-E10 · 已连接」后即可浏览照片。
4. 点按查看大图，长按进入多选。点导入后可查看读取、保存和校验进度。原始 JPEG 保存到手机图库的 `Pictures/PhotoSync`。

若系统弹窗的请求方是「图库」，且 PhotoSync 已连接，可以取消图库的这次请求。若相机持续显示忙或读取失败，可先拔线再关闭相机，几秒后开机接回。

## 挑片方式

- 相册只显示 JPEG，按拍摄日期分组，默认最新优先。日期组可以折叠和整组选中。
- 浏览优先复用已缓存的缩略图。双指放大、点「查看原图」或导入时按需读取完整 JPEG。显示 EXIF 时可以单独读取 JPEG 文件头。
- 设置分为「连拍分组」和「拍摄参数」。连拍候选默认要求相邻间隔不超过 1 秒、至少 3 张且 DSC 序号连续。大图 EXIF 默认显示焦距、镜头、ISO、光圈和快门。

## 分支和文档

| 分支 | 相机连接方式 |
| --- | --- |
| `mtp` | 通过 USB MTP/PTP 会话浏览相机对象。 |
| `mass` | Android 挂载相机 SD 卡，经 `DCIM` 只读授权读取 JPEG 和内嵌预览。 |

[构建与工具链](docs/BUILD.md) · [真机验证与 APK 校验](docs/VALIDATION.md) · [上游来源与改动](docs/UPSTREAM.md)

项目基于 [devcxl/PhotoSync](https://github.com/devcxl/PhotoSync)。
