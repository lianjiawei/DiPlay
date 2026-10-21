# 设计稿还原修正：原生 Android 界面

本轮按已确认的黑色林肯冒险家设计稿重新校准页面构图、玻璃按钮、连接圆环、设置行和弹窗。首页和连接态使用相同构图；设置页减少重复解释，让名称、当前值和操作入口优先。重连、权限、清理文件等必要说明保留。

- [设计稿与实装并排对照 PDF](design-vs-native.pdf)：8 组页面，左侧为原始设计，右侧为原生 Android 渲染。
- [首页对照](home-comparison.png) · [连接态对照](connecting-comparison.png) · [显示设置对照](display-comparison.png) · [竖屏对照](portrait-comparison.png)。
- [逐页尺寸、裁剪范围与截图校验值](comparison-index.json)。
- [原生截图](screenshots/)：含深浅主题、宽窄屏、大字体和阿拉伯语 RTL。
- [功能边界核对](behavior-boundary.json)：14 个连接、更新、清理、安装和快捷设置方法与上一版逐字一致；视频和手势层未改。
- [素材清单](asset-manifest.json) · [Material Icons 来源](material-icons-provenance.json)。
- [验证结果 JSON](validation.json)：3344 项测试、594 个页面状态回归、三个应用的 Lint 与构建通过。
- [完整复核记录](../../../../design-qa.md)。

截图来自 Robolectric 的 Android 原生绘制，使用测试配对信息及模拟连接状态，没有真实 iPhone 视频流。两侧图像保持比例放入相同对照框；弹窗只裁去设计图中的周围背景，裁剪坐标明确记录。存储截图使用实际测试扫描结果（0 个文件），不会伪造设计图中的容量或文件数量。版本字段 `UI preview` 仅用于截图，不改变发布版本。

原始 [v1 设计稿](../../v1/)保持不变。第 03 张的 CarPlay 内容是示意，实装始终显示手机真实画面；实车 GPU 帧率与首帧过渡仍需车辆验证。本轮提交源码与对照资料，不发布 APK。
