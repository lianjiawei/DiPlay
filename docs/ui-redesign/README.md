# CorsairPlay UI 设计与实现资料

本目录固定保存 2026-10-20（北京时间）这一轮 UI 设计和实现资料，作为后续统一优化的对照基线。源码位于当前 `lincoln-0.2.20` 分支；本次归档不改变应用功能，也不发布 APK。

## 设计资料

| 资料 | 内容 |
| --- | --- |
| [已确认的首页视觉方向](reference/approved-home-black-corsair.png) | 黑色林肯冒险家、蓝灰暮色、玻璃材质、冰蓝连接圆环 |
| [完整设计文档 PDF](v1/CorsairPlay-UI-Design-v1.pdf) | 16 页，覆盖基础规范、页面、动效、状态和适配 |
| [设计规范 JSON](v1/design-system.json) | 配色、尺寸、按钮、动效、信息架构及功能边界 |
| [页面设计图](v1/screens/) | 首页、连接中、首帧过渡、设置、弹窗、更新、存储和窄屏，共 9 张 |
| [原始文件校验清单](v1/manifest.json) | 设计文档、规范和页面图片的 SHA-256 |

`v1` 是原始设计阶段存档，其中的状态文字保留生成时语境；当前实现状态以下文和源码为准。设计图中的 CarPlay 内容用于示意，应用显示实际 iPhone 画面。

## 原生界面实装截图

以下 12 张图片由 Android 原生界面渲染生成，不是设计图。截图版本显示 `UI preview`，不代表发布版本号。

| 场景 | 截图 |
| --- | --- |
| 横屏首页 | [深色](implementation/screenshots/landscape-zh-home.png) · [浅色](implementation/screenshots/landscape-zh-light-home.png) |
| 连接准备页 | [横屏](implementation/screenshots/landscape-zh-connecting.png) · [阿拉伯语竖屏](implementation/screenshots/compact-ar-rtl-connecting.png) |
| 矮屏首页 | [1920×720 / xhdpi](implementation/screenshots/short-wide-zh-home.png) |
| 设置概览 | [宽屏大字体](implementation/screenshots/expanded-en-dark-overview.png) · [窄屏浅色大字体](implementation/screenshots/compact-zh-light-overview.png) |
| 显示设置 | [宽屏大字体](implementation/screenshots/expanded-en-dark-display.png) · [窄屏阿拉伯语 RTL](implementation/screenshots/compact-ar-rtl-dark-display.png) |
| 单选弹窗 | [浅色](implementation/screenshots/expanded-en-light-choice.png) · [阿拉伯语 RTL](implementation/screenshots/compact-ar-rtl-dark-choice.png) |
| 关于与更新 | [横屏深色](implementation/screenshots/landscape-zh-dark-about.png) |

## 实现与验证

- [详细回归记录](../../design-qa.md)：实现范围、适配矩阵、已修正问题和实车检查边界。
- [验证结果 JSON](implementation/validation.json)：记录对应代码提交、3339 项测试结果、Lint、构建和 594 个页面状态的回归结果。
- 改版前源码基线：[`pre-ui-redesign-20261020`](https://github.com/lianjiawei/DiPlay/tree/pre-ui-redesign-20261020)。
- 本轮 UI 实现代码提交：[`1f297f4b`](https://github.com/lianjiawei/DiPlay/commit/1f297f4b22fbcb4177096ba17c3cb6101abae51b)。该提交之后仅补充文档与本目录的归档资料。

保留现有连接、USB、设置保存、默认值、在线更新、存储清理和安装行为。主屏实际首帧到达后显示 CarPlay，成功提示单独淡出。实车上的帧率、连接过渡和触控仍需实测，自动化渲染截图不替代实车验证。

后续可基于此目录继续优化，保留 v1 便于比较；新增设计版本和更新后的实装截图应注明对应代码提交。
