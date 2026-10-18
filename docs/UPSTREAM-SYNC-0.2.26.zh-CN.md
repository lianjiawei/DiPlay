# CorsairPlay 0.2.26 上游同步记录

## 来源与个人版标识

- 上游：`kriskwok/DiPlay` 正式标签 `v0.2.26`，提交 `df93896969ad8e9000f099ad9e0999d96df2b48a`。
- 同步前个人分支：`lincoln-0.2.20`，提交 `7332f9ec9abbcc6786d42ecc78fb5c119a1c926d`。
- 采用合并提交保留双方历史；上游默认分支当时仍指向 `515a0b9a`，因此按正式标签合入。
- Android 应用 ID 仍为 `com.corsairplay`，Debug 仍使用 `.hudtest` 后缀。
- 个人版版本为 `0.2.26-precision.1`，versionCode 为 `48`，高于上一版个人包的 `44` 和上游的 `47`。
- 自动更新继续查询 `lianjiawei/DiPlay` 的 Releases，保留缓存来源检查及个人版本比较规则；历史版本列表采用上游的 30 条查询上限。

## 合入的上游内容

1. CorsairPlay 应用名称与图标、林肯／福特／自定义品牌预设。
2. 按当前播放状态路由方向盘上一曲／下一曲，暂停中的 CarPlay 不继续占用媒体会话。
3. 上游的焦点协调：其他车机播放器取得永久焦点时暂停 CarPlay，CarPlay 恢复播放时重新请求焦点。
4. CarPlay 四角圆角及车机主题背景；圆角默认关闭。
5. 桌面卡片歌词与播放控制协议，默认关闭，属于需修改车机桌面的实验性功能。按仓库设置规范放入“高级”中的实验显示设置，并补充各语言的实验标识和 ADB root 安装前提。
6. 音乐分块、优先非阻塞写入；持续无进展时回退阻塞写入。音乐时钟微调采用上游的默认关闭与迁移策略。
7. USB 插入时请求前台、连接与主题刷新等修复，以及上游的版本历史与更新说明展示。

## 保留和适配的个人修复

| 项目 | 合并处理 | 重点回归场景 |
| --- | --- | --- |
| 导航距离 | 保留 1 公里内 5 米、10 公里内 50 米、更远 500 米的取整精度 | 阈值边界与异常距离 |
| 车机静音 | 保留 system/car 来源区分、标准解除静音广播与焦点恢复校正 | 系统静音可恢复；实体静音键不会被擅自取消 |
| PCM 增益 | 使用上游 PcmChunkWriter；重试时可从原始 PCM 重新计算增益 | 部分写入不叠乘；等待期间静音／解除静音及时生效；通话不被音乐压低 |
| 零进展保护 | 补上上游回退后仍返回 0 的退出路径 | 非阻塞零进展先正常重试、再回退；阻塞写入仍为 0 时返回，避免写入线程永久循环 |
| PCM 诊断 | 保留 inputPeak、outputPeak、effectiveGain、audioOff 和压低状态 | 明确区分输入静音和本地清零；不记录音频内容 |
| 时钟拥塞 | 启用微调时仍保留输入队列高水位保护与校准保留 | 接收队列满时不继续减速；正常时钟漂移仍可校准 |
| 焦点中断 | 用上游的权威播放状态判断，保留延迟任务的会话、焦点、用户意图检查 | 短焦点抖动不误发播放；切换播放器取消待执行恢复；断开、手动暂停和重复 GAIN 不误恢复 |
| 仪表生命周期 | 保留导航结束延时清屏、剩余信息开关及重复车机来源事件处理 | 相同结束快照仍清理；重规划和下一段真实导航继续正常 |
| 备份服务 | 保留停止时等待旧监听线程退出 | 连续打开／关闭仍使用固定端口 |

## 桌面卡片适配范围

桌面卡片沿用上游针对 QQ Music 等歌词模式的字段解释：把 iAP2 artist 当作歌名、title 当作歌词。若某个音乐 App 按标准字段发送“歌名／真实歌手”，卡片会把歌手当作第一行。这是上游实验功能的现有局限，不能仅凭当前字段稳定区分两种模式；后续应结合 sourceApp 或显式歌词模式适配，不在本次同步中加入猜测规则。仪表原有 title 行输出不受此项影响。

## 验证

初次完整检查发现上游缺失 6 种语言的桌面卡片设置文案，已补齐。FPS 文案保持上游的 translatable=false 规则，不额外增加翻译。初次完整检查还在旧版导航压低测试中复现了写入器的永久循环：AudioTrack 在非阻塞和阻塞模式均返回 0。已修复该退出边界，并把依赖原地修改 PCM 的旧断言更新为核对实际写入数据；新用例限制写入次数，旧实现会直接失败。

最终仓库要求的完整命令通过：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug \
  --continue --max-workers=4 --console=plain
```

- Shared：1585 项。
- Common：1467 项。
- Home：6 项。
- 共 3058 项，失败、错误和跳过均为 0；三个 Debug 构建和三项 Lint 均通过。
- 截图导出再单独验证 3 个场景，使用原生绘图生成页面与控件截图，涵盖窄屏中文、宽屏英文、阿拉伯语 RTL，字体均为 1.3 倍；验证新设置标题换行且不截断。测试文件：`AdaptiveSettingsUiTest`。
- 图像、完整测试 XML 和日志保存在本次云环境的 `/workspace/artifacts/0.2.26-precision.1-validation/`。
- `:mobile:verifyStandaloneAuthentication :mobile:lintRelease :mobile:assembleRelease` 通过；APK 签名验证通过，签名证书与上一版个人包一致。
- APK：`CorsairPlay-0.2.26-precision.1.apk`，SHA-256：`d51685f2b753d103bbaf21583c998bf91b459754db3540a6895bd8f140ce5783`。
- `scripts/check_public_tree.py` 通过；运行时认证与个人签名资料继续位于 Git 仓库之外。

自动化检查与静态审查不能确认真实车机的 HAL 杂音、iPhone 配对、随心听固件行为或仪表接收效果。本次未连接车机，也未执行 ADB root 或安装桌面修改包。桌面卡片的接收端仍需适配对应车机桌面。
