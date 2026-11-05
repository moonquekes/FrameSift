# 上游来源与改动范围

拾帧（FrameSift）以 `devcxl/PhotoSync` 的提交 `28ebca9485ff70ef0805fde3f0c38aeaf09b2383` 为基线，Git 历史保留了上游基线和后续改动。 [PhotoSync 上游仓库](https://github.com/devcxl/PhotoSync)

| 范围 | 来源与改动 |
| --- | --- |
| Android 应用框架 | 沿用入口、生命周期、Room 数据层、应用资源和 Gradle 模块结构。在既有 Activity 内接入相机相册。 |
| USB PTP/MTP | 沿用 `ptp/usbcamera` 的传输协议、对象读取和厂商实现。为 ZV-E10 增加相册模式识别、缩略图读取、会话处理、诊断和优先级队列。 |
| 图片显示和导出 | 复用原项目的 JPEG 分块显示控件和 `MainActivity` 的图库写入入口。增加日期分组、选择、连拍候选、EXIF 面板、进度和 SHA-256 校验。 |
| 海量存储 | 新增目录授权、卷状态、JPEG 索引、MPF 解析和范围读取。预览层增加中图、邻图预取及保留手势视口的原图切换。 |
| 原生组件 | 保留 `libraw` 模块及固定提交的 LibRaw、Little CMS Git 子模块。相机相册只展示 JPEG，当前构建仍会编译这些组件。 |

主仓库基线缺少覆盖整个项目的顶层许可证。源码中的版权声明和各组件的许可分别保留。部分 EOS 和 Nikon PTP 源文件有 GPL-2.0-or-later 头部。LibRaw 的许可见其 `COPYRIGHT`，Little CMS 的核心库许可见其 `LICENSE`。独立管理仓库不改变这些来源和许可，公开分发前须核实授权。
