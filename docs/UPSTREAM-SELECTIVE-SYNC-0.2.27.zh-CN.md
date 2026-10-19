# 0.2.27 上游修复的选择性同步

个人分支基线：`52e2ae4ff1279a6851fc80a941eb5d89b0b9a9d8`（`0.2.26-precision.4`）。
上游来源：`kriskwok/DiPlay` 标签 `v0.2.27`，提交 `b8e0dda0897a07ab1680629839da017f73932937`。
本次按功能适配四项修复，不将整个上游提交标记为已合并。

## 合入内容

1. 更新网络：网络选择排除 VPN；前台最新版查询、历史列表、APK 和校验文件下载均使用选定网络的连接工厂。后台检查沿用同一网络选择器。只绑定更新请求，不改变 CarPlay 的进程网络。没有合适网络时，前台保留普通连接的回退路径。
2. 有线启动超时：首次发送 `carplay-start-session` 后等待 30 秒；仍未建立 AirPlay 会话则报告失败，交由既有重试机制恢复。重复发送不延长时限；会话建立、控制循环退出或控制器关闭时取消定时任务。
3. 重试竞态：已排队的重连任务发现同一代控制器恢复运行、且没有再次丢失连接时，不再拆掉该连接。恢复后再次失败、控制通道结束或传输丢失仍允许重试。
4. 软件 Opus 容错：捕获 Concentus 编码时的 `AssertionError`，丢弃该帧并尝试重置编码器；后续帧继续编码，错误报告保留既有次数上限。

## 个人实现与范围

- 保留 `lianjiawei/DiPlay` 更新源、个人签名配置、`precision.*` 比较规则、缓存来源隔离和下载进度／手动安装弹窗。
- 保留个人 USB 重枚举状态机、PCM 增益和写入保护、音频焦点处理、5／50／500 米仪表精度及有界异步日志。
- 不包含上游自动启动、高德后台清理、音乐积压丢包、光感兜底、备用服务器、仅正式版本筛选或缩短导航结束清屏延时。
- 不修改应用版本号，不发布或替换 APK。

## 回归覆盖

- VPN 为当前网络时选择可用物理网络、保持物理默认网络优先、拒绝未验证网络。
- 本地 HTTP 实际下载验证连接工厂，最新版与历史查询及下载继续经过个人更新路由；已有进度、校验失败重试和点击后安装测试保留。
- 上游有线启动定时器用例：未发送不计时、重复发送不延期、超时只触发一次、成功及关闭后不触发。
- 上游重连用例：恢复后不拆连接、未恢复及再次丢失时仍重试。
- 上游 Opus 用例：注入断言失败，验证状态重置及后续帧可被解码。

## 验证结果

仓库要求的完整 CI 命令通过，最终一轮耗时 4 分 48 秒，包含有效的未变更任务缓存：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug \
  --continue --max-workers=4 --console=plain
```

- Shared 1603 项、Common 1613 项、Home 6 项，共 3222 项；失败、错误和跳过均为 0。
- Mobile、Home、Maphost 的 Debug 构建及三项 Lint 全部通过。
- `scripts/check_public_tree.py` 和 `git diff --check` 通过。
- 本次没有运行 Release 构建、发布 APK 或执行真实车机安装。

自动化检查不替代真实车机上的 USB、网络切换和麦克风验证。
