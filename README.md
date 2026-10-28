# CarPlay for Lincoln / Ford

[简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

面向林肯、福特 Android 车机的 CarPlay 接收器，支持 USB 和无线连接。本仓库基于 [xcertplay](https://github.com/shilapi/xcertplay) / DiPlay 持续开发，已经针对林肯、福特车机重做首页、设置、车型背景、仪表转发、音频行为和在线更新，不再使用上游 DiPlay 的发布渠道。

仓库仍保留 `DiPlay` 名称以延续 Git 历史；安装到车机后的应用名称是 **CarPlay**。

> 这是个人维护的实验项目，并非 Apple、Lincoln 或 Ford 官方产品。不同车型、车机固件和 iOS 版本的兼容性可能不同，请停车后测试。

## 项目信息

| 项目 | 当前值 |
| --- | --- |
| 应用名称 | CarPlay |
| 当前正式版 | 1.1.9 |
| Release 包名 | `cn.manstep.phonemirrorBox` |
| Debug 包名 | `cn.manstep.phonemirrorBox.hudtest` |
| 最低系统 | Android 7.1（API 25） |
| 主要适配 | 林肯/福特原厂 Android 车机，重点适配 Freescale MEK / i.MX8 平台 |
| 开发分支 | `lincoln` |

这个分支与上游的主要区别：

- 以林肯/福特车机为主要适配目标，并隐藏不适用于该硬件的上游车型入口。
- 应用名称、包名、发布签名和版本线均为本分支独立维护。
- 首页和设置已按横屏车机重新设计，连接过程统一在一个主页中呈现。
- 增加林肯/福特 Logo、车型背景、方向盘/车机按键及原车仪表适配。
- 支持在应用内检查、下载和安装更新。

## 下载与更新

- [GitHub 最新正式版](https://github.com/lianjiawei/DiPlay/releases/latest)
- [CarPlay 1.1.9 更新说明](docs/RELEASE-NOTES-1.1.9.md)
- [项目仓库](https://github.com/lianjiawei/DiPlay)

应用内更新位于 **设置 → 关于 → 检查更新**，支持直接检查、下载和安装新版本。下载完成后会校验文件、包名和 Android 签名。

## 主要功能

### CarPlay 连接

- USB 有线 CarPlay。
- 车机热点、Wi-Fi Direct、现有 Wi-Fi / 同一局域网无线 CarPlay。
- 选择已配对 iPhone、连接时自动准备网络、打开应用自动连接及随车启动。
- 主页、连接中、已连接和重连状态使用同一页面，Android 系统返回键不会不断堆叠旧页面。

### 林肯/福特车机适配

- 林肯或福特首页 Logo，可在 **设置 → 车辆 → 首页品牌** 切换。
- 冒险家、航海家、飞行家背景，以及自定义横屏背景。
- 林肯/福特车机“音频关闭”键、方向盘切歌键和中控切歌键适配。
- 针对 Freescale MEK / i.MX8 车机的视频输出和流畅模式。
- 不适用于林肯/福特硬件的 BYD 专属设置会在目标车机上隐藏。

### 原车仪表

在检测到兼容硬件或仪表应用时，**设置 → 导航 → 林肯/福特仪表** 可提供：

- 下一转向图标、距离和道路名称。
- 1.1.8 新增的下一路口进度条，并处理换路口、重新规划和断开重连。
- 当前歌曲或歌词行及播放状态。
- 总剩余里程和时间的实验性转发。

仪表字段按照已分析的原车接收端限制发送：道路和媒体文字安全截断，路口距离使用原车百度地图的精度规则。请勿同时运行旧的独立仪表转发插件。

### 显示、声音与交互

- 深色车机界面，适配 1280×720、1920×720、超宽屏和手机横屏窗口。
- CarPlay 分辨率、帧率、图标/文字大小、画面调整、Dock、系统栏和日夜模式设置。
- 导航播报时压低媒体音量，可调整幅度与恢复时间。
- 微信语音、Siri、通话和车机麦克风相关兼容处理。
- 设置搜索、分类设置、二维码备份/恢复和本地诊断报告。
- 首页不显示更新卡片；更新提醒和下载统一放在“设置 → 关于”。

## 安装与升级

1. 将 APK 下载到 Android 车机，而不是 iPhone。
2. 允许车机安装未知来源应用。
3. 安装并打开 CarPlay，按照引导授予蓝牙、Wi-Fi/定位及麦克风权限。
4. 选择连接方式并在 iPhone 上确认 CarPlay。

基础 CarPlay 不要求 root。少量依赖特定车机固件的扩展功能会在设置说明中明确标注 ADB 或实验性要求。

当前 1.1.x 正式版使用相同包名和发布签名，可以覆盖升级并保留设置。以下情况不能直接覆盖：

- 早期 `com.corsairplay` 包名与当前应用是两个独立应用。
- 第三方“第三代 CarPlay”APK 即使包名相同，也可能使用不同签名。
- Debug 包和正式包的包名不同，可同时安装，但设置不互通。

若 Android 提示签名冲突，需要先确认旧 APK 的来源并备份设置，再决定是否卸载；卸载会清除该应用的数据。

## 从源码开发

开发环境需要 Android Studio、JDK 25、Android SDK Platform 37、Build-Tools 36.0.0、Platform-Tools 和 NDK 28.2.13676358。详细步骤见 [构建文档](docs/BUILD.md)。

主应用模块是 `:mobile`：

```sh
./gradlew :mobile:assembleDebug
```

完整代码检查：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

普通源码构建不包含 CarPlay 运行身份，因此适合开发和界面测试，不能直接作为可连接 iPhone 的公开正式包。独立连接构建需要在 Git 仓库外提供运行身份；正式发布还需要本分支自己的 Android 签名密钥。任何私钥、keystore 和本机配置都不应提交到仓库。

## 诊断与反馈

遇到连接、声音、画面或仪表问题时，打开 **设置 → 诊断 → 保存诊断报告**。报告通常保存在 `Downloads/Lincoln`，不会自动上传。提交问题前请检查文本并删除热点密码、设备标识等隐私信息，同时提供：

- 林肯/福特车型、车机 Android 与固件版本。
- iPhone 型号和 iOS 版本。
- USB、车机热点、Wi-Fi Direct 或同一局域网连接方式。
- 复现步骤、故障时间、预期结果和实际结果。

需要反馈时，请在 [lianjiawei/DiPlay 项目主页](https://github.com/lianjiawei/DiPlay)提供上述信息；本仓库当前未启用 GitHub Issues。

## 风险与许可

- 本项目未经 Apple 认证，公开 APK 包含实验性 CarPlay 配件身份，接收 APK 的人可以提取它。
- iOS、车机固件或仪表应用升级后，现有兼容行为可能变化。
- 仪表、方向盘按键和车机专用功能只在相应硬件/固件存在时生效，不代表支持所有林肯或福特车型。
- 驾驶过程中不要调试设置或查看日志。

项目基于 xcertplay 开发，主体遵循 GPL-3.0；部分界面和资料改编自 DiAuto，相关内容遵循 AGPL-3.0。分发 APK 或修改版时必须保留原有版权、许可证和相应源代码。参见 [中文许可证说明](LICENSE-NOTICE.zh-CN.md) 和 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。

CarPlay 是 Apple Inc. 的商标；Lincoln 和 Ford 是各自权利人的商标。本项目与这些公司没有隶属或认可关系。
