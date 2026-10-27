# 有线连接阶段耗时诊断

诊断报告中的 `WIRED_STARTUP` 行用于定位连接等待，不改变 USB 授权、重枚举、配对、认证、网络、重试或解码流程。无需打开详细调试开关。

## 读法

- `attempt`：进程内递增的连接编号；跨进程应结合报告的进程及启动记录区分。
- `seq`：该连接的记录顺序。重启连接创建新记录器，旧回调只持有旧记录器。
- `stage`：固定的阶段名，不含手机标识、证书、配对内容或协议载荷。
- `usbCycle`：0 为初始 USB 节点，1、2 为现有流程允许的重新枚举轮次。
- `totalMs`：从应用开始本次连接准备到该节点的单调时钟耗时，包含用户确认、手机响应、系统调度等等待。不是从物理插线瞬间开始，也不覆盖此前的进程冷启动。
- `deltaMs`：与前一条已记录节点之间的间隔。并行阶段可能交错，因此不能把它当作本阶段的独占执行时间；比较指定两个节点应使用它们的 `totalMs` 差。

## 关键节点

| 节点 | 含义 |
| --- | --- |
| HOST_REQUESTED / DISPLAY_PENDING / DISPLAY_READY | 应用准备连接、等待尺寸、尺寸已应用；已测量的窗口可直接就绪 |
| CONTROLLER_SETUP / CONTROLLER_START | 准备控制器及媒体资源、开始运行控制器 |
| MFI_PREPARING / MFI_READY | 认证提供方初始化；不代表与手机的认证已经完成 |
| USB_DISCOVERY / USB_FOUND | 发现手机；两者间可能只是等待用户插线 |
| USB_ACCESS_CHECK / USB_PERMISSION_WAIT / USB_PERMISSION_READY | 检查 USB 权限、等待授权、授权完成；重新枚举后按 usbCycle 单独记录 |
| USB_TRANSITION_REQUESTED / USB_REENUMERATED | 请求手机切换 USB 配置、发现新枚举 |
| USB_DATA_OPEN / USB_DATA_READY / NCM_OPEN / NCM_READY / USBMUX_OPEN / USBMUX_READY | 打开 USB、NCM 和 USBMUX 传输 |
| PAIRING_LOAD / PAIRING_REUSED / PAIRING_NEW / PAIRING_CREATED / PAIRING_READY / PAIRING_REJECTED | 区分复用记录、首次配对及旧记录被拒绝后的重新配对 |
| CARKIT_OPEN / CARKIT_READY / IAP2_LINK_OPEN / IAP2_LINK_READY | 打开 CarKit 控制流及 iAP2 链路 |
| NETWORK_ATTACH / NETWORK_ATTACHED | 车机端挂接 NCM/VPN 传输；不等同于手机已接受网络数据 |
| IDENTIFICATION_READY / AUTHENTICATION_READY | 手机接受设备描述、认证通过 |
| START_SESSION_SENT / START_SESSION_RESENT | 已发送会话启动请求、现有保护逻辑补发请求 |
| NCM_LINK_ACCEPTING / AIRPLAY_TCP_ACCEPTED / AIRPLAY_ACTIVE | 首次 USB 网络写入被接受、AirPlay TCP 接入、会话建立；TCP 接入不等同于认证成功 |
| MAIN_FRAME_SUBMITTED | 首张主屏画面已交给可见 Surface；不计仪表、镜像、无 Surface 或离屏停放输出。是解码器提交时间，不是屏幕硬件实际显示时间 |
| FAILED / CANCELLED / RESTARTED | 未完成启动时失败、取消或重启，保留之前节点定位停留位置 |

## 开销与边界

每个阶段在同一 USB 轮次只记录一次。使用固定枚举和三轮有界去重表；成功首帧、失败或取消后停止记录。没有额外轮询、定时器、逐帧统计或协议包日志。首帧复用解码器已有的首次输出通知。

事件只执行少量计时、格式化、脱敏及入队，磁盘写入复用现有后台线程。队列有上限，积压时丢弃待写日志而不等待磁盘，导出报告包含丢弃计数。日志不是零开销，也不能承诺所有节点在队列过载、进程被杀或报告截断时都保留。

只用单调时钟计算耗时，墙上时间仅供报告阅读。连接准备阶段的麦克风、VPN 等权限确认仍可结合已有状态日志判断。不要为了缩短测得的等待，直接删掉协议步骤或缩短失败超时。

## 实车取样

安装测试包后，分别测试冷启动后插线、拔插重连和手机已插线时启动。连接成功后从设置的诊断入口导出报告；若连接失败也直接导出，不必反复重启覆盖现场。注明手机是否解锁、是否弹出权限或信任确认，以及是否发生手动操作。已有配对与首次配对的耗时不要混作同一组比较。

自动测试覆盖计时与去重、USB 轮次、并发及迟到事件、日志异常、隐私过滤、磁盘阻塞、主屏/仪表/镜像区分和 USB 连接回归。真实设备的总耗时及影响仍以实车取样为准。
