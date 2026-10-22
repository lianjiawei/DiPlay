# CorsairPlay precision.12-rc1 测试预发布

针对“同一台 iPhone 开个人热点并运行 CarPlay”的兼容性候选。
版本代码 67，最低 Android 7.1（API 25），保留 Android 8.1 兼容性。

## 改动

- 继续复用车机系统已连接的 Wi-Fi，不要求在 DiPlay 再输入热点密码，也不向 iPhone 下发 Wi-Fi 凭据。
- 蓝牙认证和启动请求完成后，向当前 Wi-Fi 的本地网关补充定向 mDNS 查询，作为组播服务发现之外的另一条发现路径。
- 网关只是候选：须有完整的 CarPlay 控制服务记录、与已配对 iPhone 匹配的蓝牙标识、指向该网关的地址记录和真实端口，才发送连接请求。不写死热点 IP，不猜控制端口，不扫描网段。
- 新增查询和现有 Wi-Fi 下的主动控制连接绑定到当前 Wi-Fi Network，保留全局网络路由。
- 现有 Wi-Fi 的启动请求同时提供实际监听的 IPv4／IPv6 地址；车机热点和 Wi-Fi Direct 保留此前的启动参数。
- 本模式缩短单个控制地址的重试占用，给另一地址族留出时间；非成功 HTTP 响应可重试。网关或链路变化时重新建立连接。
- 会话建立后取消定向查询及后续主动连接请求，保留 AirPlay 服务发布；忽略服务名称大小写差异导致的重复发现。
- 诊断补充定向发现的查询次数、应答和匹配计数，不记录查询报文、手机名称或蓝牙标识。

## 怎么测试

1. 在用于 CarPlay 的同一台 iPhone 上开启个人热点，用车机系统 Wi-Fi 设置连接它。
2. 确认这台 iPhone 已与车机蓝牙配对，在 DiPlay 中选中它。
3. 选择“现有 Wi-Fi／同一局域网”连接方式，重新发起 CarPlay 连接。无需再填热点密码。
4. 无论成功或失败，导出本版本的诊断报告，并记录 iPhone 型号和 iOS 版本；若成功，再测试锁屏、断开重连和持续播放。

这是实验性连接适配，**尚未在该热点拓扑上实机验证，不保证解决所有 iOS 版本的限制或长期稳定性**。
若 iPhone 未在热点接口开放控制服务，Android 端不能强制开启。不同设备提供的热点、普通路由器仍可使用原有组播发现。

## 安装

沿用 precision.10 起的同一签名，可覆盖安装 precision.10／precision.11-rc1 并保留设置。
GitHub 作为预发布提供 APK 和 APK 校验值，precision.10 继续保留为稳定最新版。
源码使用对应 Git 标签获取；本地测试报告不作为公开附件上传。

## 协议依据

定向查询遵循 [RFC 6762 §5.5](https://www.rfc-editor.org/rfc/rfc6762.html#section-5.5)
及 [§6.7](https://www.rfc-editor.org/rfc/rfc6762.html#section-6.7)，
服务记录遵循 [RFC 6763](https://www.rfc-editor.org/rfc/rfc6763.html)。
Socket 网络选择使用 Android 的 [Network.bindSocket](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.DatagramSocket))，所用重载均早于 API 25。
