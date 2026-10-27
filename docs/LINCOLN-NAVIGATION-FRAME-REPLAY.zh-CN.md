# 林肯仪表导航帧诊断与离线回放

当前导航输出以 Git 标签 `v1.2.0` 为基线，工作区增加了明确换动作编号但新详情缺失时停止重发旧终点，以及同路口远距离突然为零的有界确认保护；帧级诊断本身只观察数据，不决定路口、距离或进度。此前试验包的等待距离与终点拦截不在当前工作区导航输出中。用户原始照片发生时没有帧记录，不能用合成回放证明当时的根因。

## 诊断记录

在设置中导出诊断报告；其中 `Lincoln navigation frames v1` 为进程内保存的最近记录，包含连接会话、帧顺序、单调时间、消息 ID、状态、索引列表、距离、相邻动作距离、字段存在/错误/重复标记以及本地路线代次和距离修订。采集发生在共享导航解析后、同一消息顺序锁内；只有林肯输出附加期间采集。普通距离消息每秒最多 8 条，路线结构消息 32 条、动作详情 64 条，分别限流；最多保留 256 条。`rateDropped` 和 `evicted` 表示丢失，序号间断也是不完整的证据。进程退出后内存记录会消失。

该帧记录不保留文本、路名、地址、歌词、设备标识、坐标或原始载荷；新增输出记录也只保存动作代码、数值距离和进度身份是否变化。它不在导航回调进行文件或网络 I/O；导出时复制内存记录后组装文本。**这只描述新增记录：完整诊断报告还可能包含应用既有的其他日志，分享前仍应自行检查。** `accepted=true` 表示 Android 接受广播调用，不证明仪表已经消费或绘制。

## 回放入口

帧日志是脱敏且可能限流的**部分信息**，不是可完整恢复的原始 iAP2 载荷。测试专用的 `LincolnNavigationNumericReplay` 从最大 64 MiB 的完整报告流式提取有界帧区段，重建允许的数值字段，经实际 `CarPlayGlance → LincolnClusterOutput` 运行。它不能复现路名、歌曲、未知字段、真实设备异步调度或仪表固件 UI；日志丢帧、重复字段、超出记录限制、畸形消息和未记录的会话起点会产生警告。仅当测试明确开启 `allowIncomplete` 时才尽力处理不完整数据，结果不能视为原事件的完整复现。

运行已有合成场景：

```sh
./gradlew :shared:testDebugUnitTest \
  --tests 'com.shilapi.xcertplay.lincoln.LincolnNavigationFrameTraceTest' \
  --tests 'com.shilapi.xcertplay.lincoln.LincolnNavigationNumericReplayTest'
```

以后有可授权使用的诊断报告时，可使用下面的测试入口。`--rerun` 防止 Gradle 将更换报告后的测试误认为已执行；测试输出的数值结果也在测试 XML 中。这里的路径只是命令示例，不要把个人报告提交到仓库。

```sh
DIPLAY_NAV_REPLAY_REPORT=/absolute/path/to/report.txt ./gradlew --no-daemon \
  :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.lincoln.LincolnNavigationNumericReplayTest.replaysOptionalLocalDiagnosticReport' --rerun
```

自动回放对比“诊断关闭、诊断开启、脱敏数值重放”的基础箭头、距离、身份变化及基于已分析 InstrumentPanel 1.0 接收公式的进度；兼容模式开/关分别测试。模拟帧及接收模型不能证明高德手机当时发出的消息、也不能证明具体车辆仪表画面，仍需待将来正常使用时按需验证。
