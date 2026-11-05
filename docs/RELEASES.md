# 发布说明

## v0.1.0-preview.1 · 2026-11-05

[下载 APK 与校验文件](https://github.com/moonquekes/FrameSift/releases/tag/v0.1.0-preview.1)

本次发布整理现有的两份真机验证 APK，没有重新编译或重新执行真机测试。两份 APK 均为 **debug 预览版**，不是正式签名发行版。发布标签定位于 `mass` 分支的发布文档提交，不代表两个 APK 都由该提交构建。

| APK | 源码分支 | 相机 USB 模式 | 构建源码提交 |
| --- | --- | --- | --- |
| `framesift-mass-debug.apk` | [`mass`](https://github.com/moonquekes/FrameSift/tree/mass) | 海量存储器（MSC） | [`bcef3016cd4a3c3b3b73c2d228d6c8a8b1a9f2ea`](https://github.com/moonquekes/FrameSift/commit/bcef3016cd4a3c3b3b73c2d228d6c8a8b1a9f2ea) |
| `framesift-mtp-debug.apk` | [`mtp`](https://github.com/moonquekes/FrameSift/tree/mtp) | MTP | [`b370e2d8f4203dce02e8ab768b91d4b8fe1ad8e5`](https://github.com/moonquekes/FrameSift/commit/b370e2d8f4203dce02e8ab768b91d4b8fe1ad8e5) |

### 安装与使用

1. 根据相机的 USB 连接模式选择对应的 APK。两份 APK 都在同一个 Release 的 Assets 中，无需安装开发工具。
2. MSC 版需要在系统文件夹选择器中授予相机 SD 卡 `DCIM` 只读访问权限；MTP 版需要允许应用访问相机 USB。
3. 两份 APK 的包名均为 `cn.devcxl.photosync`，不能作为两个独立应用同时安装。覆盖安装要求签名一致；如果卸载再安装，原有应用数据会被清除。
4. 当前验证设备为 Sony ZV-E10 一代和荣耀 Magic7，不能据此保证其他相机、手机和存储卡组合兼容。

### 文件校验

上传前已重新计算 SHA-256，并与随附的 `SHA256SUMS.txt` 核对一致：

```text
f67ff59992cc968bc8e040c0ca4cd9efdcbffcb39595dda123c605fc90b0550a  framesift-mass-debug.apk
8f70b337e97e02dcb9c272e0efbb968e2a8ea4a0f173c8cf3adbdc7d64313b17  framesift-mtp-debug.apk
```

文件大小分别为 9,730,724 字节和 9,728,876 字节。

### 源码与许可

- 克隆源码需要两个子模块：`git clone --recurse-submodules https://github.com/moonquekes/FrameSift.git`。
- 构建步骤见 [BUILD.md](BUILD.md)，分支对应的已有真机验证记录见 [VALIDATION.md](VALIDATION.md)。
- 项目基于 `devcxl/PhotoSync`。上游基线没有覆盖整个项目的顶层许可证；第三方组件的版权和许可声明必须保留，不能将此发布视为取得统一开源授权。详见 [UPSTREAM.md](UPSTREAM.md)。
- 仓库先以私有形式保存，公开源码或向外部分发 APK 前仍需核实授权与分发义务。
