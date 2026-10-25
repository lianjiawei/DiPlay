# CarPlay for Lincoln / Ford

[主说明](README.md) · [繁體中文](README.zh-TW.md)

这是本仓库的简体中文说明。项目是面向林肯、福特 Android 车机的 CarPlay 接收器，支持 USB 和无线连接；安装后的应用名称为 **CarPlay**，正式包名为 `cn.manstep.phonemirrorBox`。

本分支基于 [xcertplay](https://github.com/shilapi/xcertplay) / DiPlay 持续开发，但已经独立维护界面、车型适配、应用包名、签名、版本和更新渠道，不使用上游 DiPlay 的 APK 或更新源。

## 当前版本

| 项目 | 当前值 |
| --- | --- |
| 正式版 | 1.1.8 |
| 最低系统 | Android 7.1（API 25） |
| Release 包名 | `cn.manstep.phonemirrorBox` |
| Debug 包名 | `cn.manstep.phonemirrorBox.hudtest` |
| 主要硬件 | 林肯/福特 Freescale MEK / i.MX8 Android 车机 |

## 下载和更新源

- [GitHub 最新正式版](https://github.com/lianjiawei/DiPlay/releases/latest)
- [1.1.8 国内直连 APK](https://weclaw-agent.cloud/diplay-updates/assets/v1.1.8/CarPlay-1.1.8.apk)
- [1.1.8 更新说明](docs/RELEASE-NOTES-1.1.8.md)
- [项目仓库](https://github.com/lianjiawei/DiPlay)

应用内更新优先访问国内镜像 `https://weclaw-agent.cloud/diplay-updates/releases.json`，失败后才访问本仓库 GitHub Releases。两个来源都必须同时提供 APK 和 `SHA256SUMS.txt`，应用会继续检查文件校验值、包名和签名。

## 林肯/福特优化

- 重做横屏主页和设置，连接、连接中、已连接和重连共用一个主页。
- 首页可选择林肯或福特 Logo；可选择冒险家、航海家、飞行家或自定义背景。
- 支持林肯/福特车机音频关闭键、方向盘和中控切歌键。
- 支持兼容原车仪表的下一转向、距离、道路名、1.1.8 新增路口进度条，以及歌曲/歌词文字。
- 针对目标 i.MX8 车机优化视频输出、音频行为和流畅模式。
- 在线更新和下载集中在“设置 → 关于”，主页不显示更新卡片。
- 深色界面适配 1280×720、1920×720、超宽屏及手机横屏窗口。

核心 CarPlay 支持 USB、车机热点、Wi-Fi Direct 和现有 Wi-Fi / 同一局域网。显示设置包含分辨率、帧率、图标/文字大小、画面、Dock、系统栏和 CarPlay 日夜模式；音频设置包含导航压低媒体、路由与缓冲选项。

## 安装和升级

将 APK 安装在 Android 车机上，不要安装到 iPhone。基础连接不要求 root；依赖特定固件的扩展功能会明确标注 ADB 或实验性要求。

当前 1.1.x 正式版使用相同包名和签名，可以覆盖安装并保留设置。早期 `com.corsairplay`、不同签名的第三方同包名 APK、Debug 包和正式包不能互相覆盖。若系统报告签名冲突，先备份设置并确认旧 APK 来源；卸载会清除应用数据。

## 开发和构建

主应用模块为 `:mobile`，需要 Android Studio、JDK 25、Android SDK Platform 37、Build-Tools 36.0.0 和 NDK 28.2.13676358。完整步骤见 [BUILD.md](docs/BUILD.md)。

```sh
./gradlew :mobile:assembleDebug
```

普通源码 APK 不包含 CarPlay 运行身份，仅用于开发和界面测试。可独立连接的 APK 需要从仓库外提供运行身份，正式包还需要本分支自己的 Android 签名密钥；这些私密文件不会进入 Git。

## 反馈、风险和许可

诊断报告从 **设置 → 诊断 → 保存诊断报告** 导出，通常保存到 `Downloads/Lincoln`，不会自动上传。提供反馈前请删除热点密码和设备隐私信息，并注明车型、车机固件、Android、iPhone/iOS、连接方式和复现时间。本仓库当前未启用 GitHub Issues，可从[项目主页](https://github.com/lianjiawei/DiPlay)查看最新联系方式。

本项目未经 Apple、Lincoln 或 Ford 认证，不保证所有车型和未来系统版本均可使用。请停车后测试，驾驶中不要调整设置。

项目主体遵循 GPL-3.0；改编自 DiAuto 的界面和资料遵循 AGPL-3.0。分发修改版时请保留版权、许可证和对应源代码。完整说明见 [主 README](README.md)、[许可证中文说明](LICENSE-NOTICE.zh-CN.md)及[第三方声明](docs/THIRD_PARTY_NOTICES.md)。
