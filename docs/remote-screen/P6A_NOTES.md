# P6a 第一部分：地址分离、强制加密与失败检测

日期：2026-10-10。依据 [P6A_SPEC.md](P6A_SPEC.md)，仅完成本地源码核实与补丁草案。

## 结论

- **已核实**：这份 Sunshine 与 common-c 的新会话标识协议允许 TCP 经 SSH、UDP 直连；Sunshine 无须为地址分离修改 IP 匹配。旧客户端的 IP 匹配路径不适用。证据：`S/src/stream.cpp:659`、`:1487`、`:2073`；`C/src/SdpGenerator.c:270`。
- **已核实**：上游 `ENCFLG_ALL` 是能力允许时启用加密的请求，不是拒绝降级的策略。Miffan 必须加客户端强制检查。Sunshine 的模式 2 强制 RTSP、视频和音频，但 ANNOUNCE 的强制检查没有覆盖控制协议。证据：`C/src/Limelight.h:90`、`C/src/SdpGenerator.c:276`；`S/src/nvhttp.cpp:1410`、`S/src/rtsp.cpp:1293`。
- **已核实**：视频与控制／输入是 AES-GCM；音频是 AES-CBC，无认证标签。计划中“全部 AES-GCM”的描述需要修正。证据：`S/src/stream.cpp:1748`、`:1254`、`:352`；`C/src/AudioStream.c:192`。
- **推断**：可以进入另行授权的 P6a 第二部分验证，不能据此宣布公网安全或实机连通已验收。主要待测项是多网卡／IPv6 源地址选择、UDP 分别受阻、快速重试、切网与公网攻击面。依据：下述会话关联、超时和加密边界。

## 阅读范围与证据口径

源码引用的前缀均指本机只读快照，行号为补丁前的行号：

| 前缀 | 本地目录 | 任务给定基线 |
| --- | --- | --- |
| `S` | `/Users/chenxiansheng/miffan/.deps/p6a/sunshine` | `v2026.1008.43744`，`d6453a3` |
| `C` | `/Users/chenxiansheng/miffan/.deps/p6a/common-c` | `f900dd4` |
| `A` | `/Users/chenxiansheng/miffan/.deps/p6a/android` | 已下载的 master 参考快照 |

**已核实**表示本地源码中存在该行为；**推断**表示由代码推导的运行结果或集成建议，未经实机验证。版本身份沿用任务给定信息；未联网重新核验。未连接远程机器，未安装、运行或编译 Sunshine／Moonlight，未读取密钥、签名配置或 `local.properties`，未修改 `.deps`。本文两个 diff 仅作草案。

**核实边界**：common-c 的 ENet 子模块在快照中没有可读的实现文件，Sunshine 的嵌套 common-c／ENet 实现也不在这份源码内；只能核实调用方及 Sunshine 的会话逻辑。ENet 内部包头、地址迁移、重传的具体实现不能标为已核实。子模块声明见 `C/.gitmodules:1`、`S/.gitmodules:28`（moonlight-common-c 条目）；本次没有补拉依赖。

## 1. 地址分离

### 1.1 Sunshine 怎样把三类 UDP 关联到 launch

| 阶段 | 已核实行为 | 源码位置 |
| --- | --- | --- |
| HTTPS `/launch`、`/resume` | `make_launch_session()` 分配递增 launch ID；从客户端 `rikey` 取得会话密钥；生成 8 随机字节的十六进制字符串 `av_ping_payload`（16 字符），另生成随机 32 位 `control_connect_data`。HTTPS 的来源 IP 没有参与这两个标识的计算。 | `S/src/nvhttp.cpp:476`、`:510`、`:523`、`:1385`、`:1501` |
| RTSP 接入 | `handle_accept()` 取当前唯一 pending `launch_event`，直接关联新 TCP socket；这里没有比较 launch HTTPS 来源 IP 与 RTSP 来源 IP。消息通过该会话的 RTSP cipher 解密。 | `S/src/rtsp.cpp:545`、`:556`、`:279` |
| RTSP SETUP | 音视频 SETUP 返回 `X-SS-Ping-Payload`；控制 SETUP 返回 `X-SS-Connect-Data`；分别返回真实 UDP `server_port`。 | `S/src/rtsp.cpp:1025`、`:1045`、`:1051` |
| RTSP ANNOUNCE | 读取 `x-ml-general.featureFlags`。`session::start()` 初始把视频／音频 peer 地址和 legacy control 的期望地址设成 RTSP 来源 IP，此时音视频端口为 0。 | `S/src/rtsp.cpp:1158`、`:1302`；`S/src/stream.cpp:2265` |
| 视频、音频 ping | `recvThread()` 从 `SS_PING.payload` 找对应 channel 的队列；支持 `ML_FF_SESSION_ID_V1` 时只注册 payload 队列，不注册 IP 队列。`recv_ping()` 接受首个匹配 ping 后用实收 UDP 来源 **IP + 端口**覆盖 peer。 | `S/src/stream.cpp:1442`、`:1487`、`:2073`、`:2117` |
| ENet 控制 | 新 peer 用 `event.data` 匹配 `control_connect_data`；有 `ML_FF_SESSION_ID_V1` 时不比较 IP。匹配后记录 ENet peer，后续按 peer 指针查表；还从 ENet 的本地地址选择媒体发送源地址。 | `S/src/stream.cpp:659`、`:682`、`:696`、`:701`、`:750` |

**已核实**：common-c 对 Sunshine 宣告 `ML_FF_SESSION_ID_V1`，解析两个 ping payload 和 connect data，把后者传给 `enet_host_connect()`，音视频 ping 每 500 ms 发一次。因此本次基线会走标识匹配，而不会拿手机 UDP 地址与 `127.0.0.1` 比较。证据：`C/src/SdpGenerator.c:270`；`C/src/RtspConnection.c:1202`、`:1267`、`:1309`；`C/src/ControlStream.c:1788`；`C/src/VideoStream.c:54`；`C/src/AudioStream.c:38`。

**推断**：在 SSH 转发和 UDP 均可达、主机监听地址正确且上述扩展保留的前提下，首连可行，没有发现协议层必须同源 IP 的阻断点。不能仅把客户端 `serverInfo.address` 改成 `127.0.0.1`：那会同时改变 UDP 目的地。依据：上一表及 `C/src/Connection.c:351`、`C/src/VideoStream.c:61`、`C/src/ControlStream.c:1767`。

### 1.2 仍然涉及 IP、单 pending 会话和 LAN／WAN 的地方

- **已核实**：legacy control 在 `get_session()` 比较 `expected_peer_address`；legacy `PING` 按来源 IP 找队列。没有 `ML_FF_SESSION_ID_V1` 时，TCP 来源 `127.0.0.1` 与真实 UDP 来源不一致会阻断这两条关联路径。草案对 split transport 缺少新标识的情况直接拒绝。证据：`S/src/stream.cpp:688`、`:1487`、`:2077`；`C/src/RtspConnection.c:1203`、`:1268`、`:1310`。
- **已核实**：RTSP 仅支持一个 **pending launch**，`session_raise()` 遇到已有 pending 时直接返回，不覆盖；活跃串流存放在 `_session_slots` 集合，不能把单 pending 误写成 Sunshine 强制只有一条活跃串流。`/launch` 在 app 已运行时返回 400，需要按既有语义走 `/resume`。证据：`S/src/rtsp.cpp:595`、`:620`、`:731`；`S/src/nvhttp.cpp:1376`、`:1497`。
- **已核实**：LAN／WAN 加密策略取 HTTPS／RTSP 的 TCP 来源地址；`127.0.0.0/8` 为 PC，PC 与 LAN 均用 `lan_encryption_mode`。所以经 SSH 的公网手机会被视为 PC/LAN；只设 WAN 为 2 不够，必须 LAN 也为 2。来源是 `::1` 或 IPv4-mapped loopback 时也是同类结果。证据：`S/src/network.cpp:23`、`:40`、`:68`、`:161`、`:198`；`S/src/nvhttp.cpp:1410`、`:1522`；`S/src/rtsp.cpp:935`、`:1294`。
- **已核实**：RTSP `Host` 地址还影响立体声音质判断，但 Sunshine 实际只检查是否包含 `0.0.0.0`，没有用它认证会话；SDP 的 serverAddress 不是 UDP 实收来源校验。证据：`S/src/rtsp.cpp:1214`；`C/src/RtspConnection.c:959`；`C/src/SdpGenerator.c:170`。
- **推断**：Sunshine 不应整体配置为只 bind loopback，否则媒体 UDP 也可能只在 loopback 监听。应按端口用防火墙限制 TCP 对外访问，保留 UDP 在选定真实接口可达。默认／自定义 bind 地址被网络 listener 使用；这里是后续部署判断，本次没有修改配置。依据：`S/src/network.cpp:129`、`:151`；`S/src/rtsp.cpp:484`；`S/src/stream.cpp:1960`。

### 1.3 NAT 与手机切网

- **已核实**：建流前 NAT 改写 UDP 来源 IP 或源端口不妨碍新标识匹配，音视频各用收到 ping 的 endpoint 回送。控制以 connect data 首次匹配，不要求三类 UDP 使用相同 NAT 源端口。证据：`S/src/stream.cpp:682`、`:1494`、`:2117`、`:1715`、`:1911`。
- **已核实**：音视频 peer 只在初始 `recv_ping()` 更新；成功返回后 fail guard 移除对应 ping 队列。持续 ping 的发送存在，但服务端这条路径不会因此持续更新 endpoint。已建立 control peer 的 session 在慢路径中也会被跳过。证据：`S/src/stream.cpp:675`、`:2083`、`:2117`；`C/src/VideoStream.c:69`；`C/src/AudioStream.c:52`。
- **推断**：已有 NAT 映射稳定时可工作；串流中 NAT rebind 或手机由 Wi-Fi 切蜂窝造成 IP／端口变化时，没有透明迁移保证，媒体可能继续发旧 endpoint。客户端 `LocalAddr` 又绑定启动时选定接口地址。产品应停止旧会话并重新 launch/resume、换新密钥和标识，而不是原地改一个全局地址。ENet 子模块不在本地，不能断言其内部所有迁移行为；Sunshine 的媒体 endpoint 本身已足以否定“已核实无缝切网”。依据：上述位置及 `C/src/Connection.c:381`、`C/src/ControlStream.c:1759`、`C/src/VideoStream.c:331`。

### 1.4 common-c 的最小改动边界

**已核实**：`RemoteAddr/AddrLen` 同时用于 RTSP TCP、历史 TCP 输入／控制／首帧、UDP 媒体与控制，以及 SDP。`LocalAddr`、AUTO 的 private/NAT64 判断和 packet size 也从 `RemoteAddr` 推导。历史 RTSP-over-ENet 分支存在，但 Sunshine 宣告 `7.1.431.-1`，本次不会走它。证据：`C/src/Connection.c:351`、`:381`、`:401`；`C/src/RtspConnection.c:407`、`:950`、`:1015`；`C/src/InputStream.c:654`；`C/src/ControlStream.c:1840`；`C/src/VideoStream.c:361`；`C/src/SdpGenerator.c:553`、`:574`；`S/src/nvhttp.h:37`。

**补丁建议**：让 `SERVER_INFORMATION.address` 继续表示选定的真实 UDP 地址，仅添加可选 `rtspTcpAddress/rtspTcpPort` 和独立的 RTSP TCP sockaddr／长度。只替换现代 Sunshine 会用到的 RTSP TCP 连接点，不对历史协议做泛化。split transport 不探测真实主机的 TCP 端口，避免在“TCP 不开放”的模型下误拒绝；无 split 参数时完整保留原始地址解析／端口探测流程。证据与改动点：`C/src/PlatformSockets.c:712`；`C/src/Connection.c:345`；`C/src/RtspConnection.c:407`。

**已核实**：common-c 的 private 判定不包含 IPv4 loopback，也不把 `100.64/10` 一般性当作 LAN（只有特殊 464XLAT 判断传 `matchCGN=true`）。因此“把 RemoteAddr 换 loopback 一定会 AUTO 判 LAN”并不符合这份源码；确定的错误是 UDP 目标／路由一起变成 loopback。保留真实地址后，AUTO 对公网 IPv4／NAT64 使用 1024、公网 IPv6 使用 1184；Tailscale `100.64/10` 按一般判断走 REMOTE。证据：`C/src/PlatformSockets.c:639`、`:703`、`:765`；`C/src/Connection.c:401`。

**推断**：AUTO 基于地址范围，并不测量隧道 MTU，私网地址也可能跨 VPN；P6b 应由路径选择层结合实际链路设置 `streamingRemotely/packetSize`。补丁保留现有算法，不把本地 SSH relay 当媒体路径。`getLocalAddressByUdpConnect()` 是选路用的 UDP connect/getsockname，不是 UDP 可达性探测；函数实际用了全局 `RtspPortNumber`，未用 `targetPort`。本草案不顺带修改该问题。依据：`C/src/PlatformSockets.c:573`；`C/src/Connection.c:381`。

## 2. 强制加密

### 2.1 Sunshine 模式 2 的真实约束

| 流 | 已核实：本次客户端实际使用的加密 | 主机模式 2 是否明确强制检查 |
| --- | --- | --- |
| RTSP TCP | `corever>=1` 启用 `rtspenc://`，使用会话密钥 AES-GCM（不是 TLS）；外层另有 SSH。 | `/launch` 和 `/resume` 没有 RTSP cipher 时返回 403。`S/src/nvhttp.cpp:510`、`:1410`、`:1522`；`S/src/rtsp.cpp:266`、`:823` |
| 视频 UDP 47998 | 每个数据／FEC shard 的整个 RTP + NV video header + payload 用 AES-GCM 加密。 | DESCRIBE 广告／请求视频加密；ANNOUNCE 缺少 VIDEO 或 AUDIO 位时 403。`S/src/rtsp.cpp:930`、`:1293`；`S/src/stream.cpp:1703`、`:1748` |
| 音频 UDP 48000 | Opus payload 用 AES-CBC + padding；FEC 基于密文生成。 | 与视频一起要求 AUDIO 位；legacy NV 音频能力位也能置 AUDIO。`S/src/rtsp.cpp:1163`、`:1293`；`S/src/stream.cpp:352`、`:1885`、`:1924` |
| 控制 UDP 47999 | 现代 common-c 请求 protocol 13，并协商 `SS_ENC_CONTROL_V2`；控制内容 AES-GCM。 | DESCRIBE 始终请求 CONTROL_V2，但模式 2 的 ANNOUNCE 拒绝条件 **没有**检查 CONTROL_V2 或 protocol 13。若已选 13，服务端会丢弃明文控制消息；若不是 13，服务端 `encode_control()` 直接返回明文。`S/src/rtsp.cpp:930`、`:1131`、`:1155`、`:1295`；`S/src/stream.cpp:576`、`:731`、`:1254`；`C/src/SdpGenerator.c:203` |
| 输入 | 通过加密控制流承载，不另发明文键鼠；旧协议还有独立输入加密路径。 | 对本次 common-c 客户端已加密，但不能仅凭主机模式 2 宣称所有任意客户端的控制／输入协议都被完整强制。`C/src/InputStream.c:238`；`C/src/ControlStream.c:703`；`S/src/stream.cpp:1295` |

**已核实**：经 SSH 的 TCP 来源 `127.0.0.1` 选择 LAN 策略，不会因 UDP 实际是公网来源重新选择 WAN 策略。主机默认 LAN 为 0、WAN 为 1；模式值解析范围为 0–2。证据：`S/src/network.cpp:198`；`S/src/config.cpp:822`、`:1788`。

**推断／建议**：Miffan 客户端的严格检查能覆盖自身连接；若要求主机对任意客户端也完整强制加密，可使用附录 B 的主机检查草案，把 CONTROL_V2 与 protocol 13 纳入模式 2 的 ANNOUNCE 拒绝条件。它会额外拒绝旧控制协议客户端，必须评估兼容性。两端补丁都不能把既有 CBC 音频变成 GCM。

### 2.2 `ENCFLG_ALL` 会不会降级

- **已核实**：视频必须同时满足 supported 与客户端 VIDEO 请求位才启用；主机没有广告视频加密时，`ENCFLG_ALL` 不会自行报错。CONTROL_V2 也是支持时才启用。`encryptionSupported` 缺失会被解析成 0。证据：`C/src/SdpGenerator.c:276`、`:281`；`C/src/RtspConnection.c:1149`。
- **已核实**：音频有额外 legacy NV 路径：在版本至少 `7.1.431` 时 AUDIO 请求会设置 `NVFF_AUDIO_ENCRYPTION` 和 `AudioEncryptionEnabled`，即使 SS 音频 supported 位没有协商。输入加密也一直请求。因此不能笼统说“所有流都会静默变明文”；明确问题是没有一个“所有要求均满足才允许连接”的策略。证据：`C/src/Limelight.h:90`；`C/src/SdpGenerator.c:188`；`C/src/InputStream.c:105`。
- **补丁建议**：增加独立 `requireEncryptedStreams`，不重新解释 upstream `ENCFLG_ALL` 语义。Miffan 同时设置它与 `ENCFLG_ALL`。严格模式拒绝非现代 Sunshine、非 `rtspenc://`、客户端未请求音视频加密、DESCRIBE 缺少任一 VIDEO/AUDIO/CONTROL_V2 能力；ANNOUNCE 后、PLAY 前再核对实际 enabled 位和 `AudioEncryptionEnabled`，返回明确的新错误码。依据：`C/src/RtspConnection.c:943`、`:1149`、`:1333`；`C/src/SdpGenerator.c:270`。
- **已核实／边界**：没有加密密钥时，视频与控制的 GCM 验证不接受明文替代，控制还明确丢弃明文包；音频 CBC 解密失败时丢包，但没有认证标签。客户端可以拒绝协议广告／协商降级，不能靠一个新布尔参数证明恶意主机真实配置为模式 2、或让 CBC 获得完整性保证。证据：`C/src/VideoStream.c:213`；`C/src/ControlStream.c:1219`；`C/src/AudioStream.c:192`。
- **已核实**：密钥是客户端通过 HTTPS `/launch`／`/resume` 发送的 `rikey`，不是主机在 UDP 上下发的密钥。App 必须附加 `LiGetLaunchUrlQueryParameters()` 返回的 `&corever=1`，保留 launch 返回的加密 scheme。Android 参考实现这样处理 URL 和密钥。证据：`C/src/Connection.c:550`；`S/src/nvhttp.cpp:481`；`A/app/src/main/java/com/limelight/nvstream/http/NvHTTP.java:780`。
- **已核实**：Android JNI 参考实现默认请求 AUDIO，部分条件下才设 `ENCFLG_ALL`，没有本文新增的强制策略；移植时不能直接照搬其默认加密选项来满足 Miffan 的拒绝降级要求。证据：`A/app/src/main/jni/moonlight-core/callbacks.c:482`、`:499`。

### 2.3 公网仍能看到什么，有什么实际风险

| 通道 | 已核实：应用层没有隐藏的内容 | 源码位置 |
| --- | --- | --- |
| 视频 | 外部加密前缀：12 字节 IV、4 字节 frameNumber、16 字节 tag；包长、包间隔。RTP 序号／时间戳、NV 帧头和 FEC info 在整个 shard 的密文内，不能说它们全部明文。frameNumber 前缀本身未作为 AAD 认证。 | `S/src/stream.cpp:158`、`:1732`、`:1763`；`S/src/crypto.cpp:189`；`C/src/VideoStream.c:188` |
| 音频 | RTP header（序号、时间戳、SSRC、payload type），FEC header（shard index、base sequence/time 等）；音频 payload 是 CBC 密文，FEC parity 是密文的纠删码而不是明文 Opus。 | `S/src/stream.cpp:313`、`:1889`、`:1897`、`:1917`；`C/src/AudioStream.c:182` |
| 控制／输入 | 应用加密 envelope 的 type、length、seq、GCM tag；内层消息类型和键鼠内容加密。ENet 握手调用携带 32 位 connect data，ACK／传输信息不在应用 GCM envelope 里；ENet 内部精确线格式本次未核实。 | `S/src/stream.cpp:291`、`:564`；`C/src/ControlStream.c:703`、`:1788` |
| 音视频 ping | 16 字符会话标识和 ping 序号直接 `sendto()`，没有应用加密／认证；它是关联标识，不是会话加密密钥。 | `C/src/VideoStream.c:69`；`C/src/AudioStream.c:52`；`S/src/stream.cpp:1494` |

**推断：实际剩余风险**（不是漏洞利用实测）：

1. IP／UDP header、包长和时序仍暴露，frameNumber／音频时间戳等还能用于流量分析。关闭对外 TCP 减少管理／RTSP 暴露，不隐藏 UDP 或桌面活动节奏。依据：上表及 `S/src/stream.cpp:1763`、`:1897`。
2. 初始 ping 与 control connect 标识不是认证凭证。知道／猜中标识的攻击者可能竞争初始 endpoint／peer 绑定，使真正客户端无法建流；后续仍没有密钥解密视频／伪造有效 GCM 输入。这类会话劫占和 UDP 洪泛的 DoS 风险仍在。控制 peer 绑定、ping endpoint 更新和 pingTimeout 刷新先于应用层加密校验，不能宣传“只有 SSH 认证的 UDP 包会被接收”。依据：`S/src/stream.cpp:682`、`:699`、`:763`、`:1498`、`:2117`。
3. 音频 CBC 无 MAC/tag，不能保证对主动篡改／重放的抵抗。其 IV 基于 `avRiKeyId + uint16 sequenceNumber`，同一会话序号每 65536 个数据包回绕，代码构造的 IV 随之复用；5 ms 包时约 327.68 秒。这不等于已经证明可恢复音频或执行代码，但不能当作现代 AEAD 音频的安全保证。依据：`S/src/stream.cpp:524`、`:1856`、`:1885`、`:1900`；`C/src/AudioStream.c:181`、`:192`。
4. 本次服务端控制解密没有显式检查已接收 seq 的单调性／重放窗口，只用收到的 seq 构造 IV；能否借 ENet 序号重放输入尚未核实（依赖缺失），不承诺抗重放验收已完成。依据：`S/src/stream.cpp:1241` 至 `:1298`。

**建议**：可把“只开放 UDP 47998–48000、TCP 全经已验证 SSH”作为地址分离测试模型，但公网验收要明确接受上述边界；若产品要求音频也必须认证加密，现协议和本草案不满足，需要另作协议改造或走 VPN 外层保护。这不改变本次只做源码核实的范围。

## 3. 路径失败的检测与会话清理

### 3.1 客户端失败顺序与时间

| 失败情形 | 已核实的路径／错误 | 时间口径与限制 |
| --- | --- | --- |
| UDP 控制 47999 不通（包含全部 UDP 被阻断） | `startControlStream()` ENet handshake；超时返回平台 `ETIMEDOUT`，经 `stageFailed(STAGE_CONTROL_STREAM_START, err)`，`LiStartConnection()` 失败后自动调用 `LiStopConnection()`。 | 该阶段等待预算 **10 秒**，不是自 `/launch` 起总共 10 秒；socket 错误／异常事件可能更早结束。`C/src/ControlStream.c:144`、`:1796`；`C/src/Connection.c:482`、`:542` |
| 控制成功，视频 47998 无任何流量 | 视频接收线程累计每次接收 timeout，达到 10 秒触发 `connectionTerminated(ML_ERROR_NO_VIDEO_TRAFFIC=-100)`。 | 视频线程启动后约 **10 秒**；100 ms 接收轮询，线程调度及两端其他失败会改变实际回调。`C/src/VideoStream.c:4`、`:137`、`:146`；`C/src/Limelight-internal.h:91` |
| 收到包但拼不出完整视频帧 | 从首个数据包起，后续收到包时检查 10 秒，触发 `ML_ERROR_NO_VIDEO_FRAME=-101`。 | 不是始终独立运行的墙钟定时器：`receivedDataFromPeer` 在包长／认证检查前置真；收到一个包后再完全静默不会在 timeout 分支触发上述无帧检查。需 App 首个有效帧期限兜底。`C/src/VideoStream.c:146`、`:162`、`:169`、`:179` |
| 音频 48000 不通 | 音频接收 timeout 继续循环，没有对应 NO_AUDIO_TRAFFIC 错误；Sunshine 初始 audio ping 超时会停止整个 session。 | 通常会转化成主机断流／控制错误，不能只等客户端音频错误。`C/src/AudioStream.c:276`；`C/src/ConnectionTester.c:37`；`S/src/stream.cpp:2158` |
| RTSP TCP 转发失效 | RTSP connect / receive 失败；与媒体候选地址可达性不同。 | TCP connect 参数 10 秒、receive 15 秒，并有 connect 重试；不应把每次 RTSP 失败都判定 UDP 候选坏了。`C/src/RtspConnection.c:4`、`:407`、`:423`、`:469` |

**已核实**：启动顺序是 RTSP → control start → video start → audio start → input start。音频 ping 在 audio SETUP 后已经开始，但 Sunshine 尚需 ANNOUNCE 创建 stream session。因此全部 UDP 不通时，通常先报控制启动超时，视频接收线程尚未启动；不是每个失败地址必然返回 `-100`。证据：`C/src/Connection.c:440`、`:482`、`:495`；`C/src/RtspConnection.c:1209`；`C/src/AudioStream.c:90`；`S/src/rtsp.cpp:1302`。

**推断**：Sunshine 默认 `ping_timeout=10s` 的初始音频／视频超时与控制握手 10 秒会竞争，最终也可能收到断开或通用错误。App 必须同时处理 setup 的返回值／stageFailed 与 streaming 的 connectionTerminated，记录阶段和所试地址，不能依赖一个唯一错误码。依据：`S/src/config.cpp:816`；`S/src/stream.cpp:2133`、`:2159`；`C/src/ControlStream.c:1815`、`:1380`。

### 3.2 能否更早判断

- **已核实**：控制 ENet 握手是视频收包前现成的判断点，但当前 timeout 也是 10 秒；只能证明控制端口可达，不能证明视频／音频端口可达。`serviceEnetHost()` 每次至多等待 100 ms 并检查 `ConnectionInterrupted`。证据：`C/src/ControlStream.c:1796`；`C/src/Misc.c:3`、`:18`、`:20`。
- **推断／建议**：候选地址尝试可配置单独的控制握手预算（例如后续实测 2–3 秒），或 App 用总期限调用 `LiInterruptConnection()`，等待启动线程退出／`LiStopConnection()` 完成后再尝试。100 ms 是检查粒度，不是端到端取消耗时保证；缩短预算在高 RTT／丢包链路可能误判。需要实际首个有效视频帧期限和音频状态观察。依据：`C/src/Misc.c:9`；`C/src/Connection.c:63`、`:69`、`:542`；上表。
- **推断／建议**：不宜把泛用 ConnectionTester 当这个 split 模型的权威预检，它还列出 UDP 48010 与 TCP 端口；UDP 流量实际要经 launch 的 session 标识关联，普通空 ping／UDP connect 不证明回程可达。本次草案不调整 timeout 默认值，避免混入未经实测的延迟政策。依据：`C/src/ConnectionTester.c:5`、`:21`；`S/src/stream.cpp:1494`；`C/src/PlatformSockets.c:573`。

### 3.3 失败会话是否残留、会否影响重试

- **已核实**：pending launch 的计时器在 `ping_timeout` 到期移除事件；control 首次匹配会提前清掉对应 launch event。未连接 control 的 stream session 有独立 ping deadline；控制广播线程超时后 stop，音视频初始 ping 失败也有 stop fail guard。证据：`S/src/rtsp.cpp:595`、`:620`；`S/src/stream.cpp:696`、`:1329`、`:2289`、`:2133`、`:2159`。
- **已核实**：STOPPING session 从 control 集合与 peer 表移除，断开 ENet 并触发 controlEnd；RTSP loop 清理停止的 session、join workers、释放 slot，活跃 session 时 loop 每 500 ms 有机会清理。join 还重置输入。证据：`S/src/stream.cpp:1335`、`:2229`；`S/src/rtsp.cpp:652`、`:710`、`:1358`。
- **推断**：正常超时清理存在，不能描述成永久孤儿会话；但 pending event 到期前，新 `/resume` 生成的 launch session 可能被 `session_raise()` 静默忽略，而 HTTP 已返回成功。新客户端会拿新 key 对应 URL 去 RTSP，实际 socket 却关联旧 pending/key，从而失败。过短客户端预算会增加这段重试窗口。依据：`S/src/nvhttp.cpp:1533`、`:1545`；`S/src/rtsp.cpp:595`、`:556`、`:279`。
- **已核实**：common-c startup 失败自动本地清理；streaming 终止回调由独立线程转发，不等于自动释放全部连接资源。已建立 control 时 `stopControlStream()` 有最多 2 秒的 graceful disconnect 预算。App 应串行停止上一条再建下一条。证据：`C/src/Connection.c:146`、`:542`；`C/src/ControlStream.c:145`、`:1642`、`:1673`。
- **已核实**：会话断开不等于停止主机应用，Sunshine 可保留已运行 app；`/launch` 会因此失败，下一次应遵循 `/resume`。`/cancel` 会终止所有 sessions 和应用，不能作为无害的候选失败清理。证据：`S/src/stream.cpp:2242`；`S/src/nvhttp.cpp:1376`、`:1554`。
- **推断／建议**：不能只按一个固定 sleep 宣称清理完成。第二部分应记录主机 launch event 和 session 结束，确认快速重试窗口；控制已建立时先正常断开并观察清理，控制未建立时需允许主机 pending timeout／退避后 fresh resume。禁止为重试调用全局 cancel、踢其他客户端或修改正在运行的用户串流。依据：上述清理与单 pending 实现。

## 4. 补丁草案与调用约定

### 附录 A：common-c（基于 `f900dd4`）

这是一个组合草案，路径相对 common-c 根目录。`RemoteAddr/AddrLen/LocalAddr` 始终保留真实 UDP 路径；新增 RTSP sockaddr 允许 TCP IPv4 loopback、UDP IPv6 等不同地址族。HTTP／HTTPS 仍由 App Kotlin 层处理。

| 改动 | 原因 |
| --- | --- |
| `Limelight.h` 新增 SERVER_INFORMATION 字段与 setup 错误码 | 不改变 `ENCFLG_ALL` 的 opportunistic 含义；调用方能明确传 relay 和严格策略。新增 struct 字段需重编 JNI／调用方，并用初始化函数或完整零初始化，不能与旧 ABI 混用。原定义：`C/src/Limelight.h:524`、`:542` |
| `Connection.c`／internal globals 分离 RTSP TCP address、length、port | 避免修改 UDP、local bind、AUTO／MTU／SDP；split 分支不访问真实主机的 TCP 探测端口。原逻辑：`C/src/Connection.c:345`、`:381` |
| `RtspConnection.c` 仅替换 TCP socket 目标 | URL 的远端 host、scheme、远端端口保留；本地随机 relay 端口仅用于 TCP socket。旧协议 split 拒绝，不漏出历史 TCP 35043／47995／47996 或 RTSP-over-ENet。原连接点：`C/src/RtspConnection.c:407`、`:950` |
| 验证 ping payload 与 connect data 存在 | 避免转入 legacy 地址匹配。合法 connect data 可以是 0，不把 0 当缺失。原解析：`C/src/RtspConnection.c:1203`、`:1268`、`:1310` |
| 检查加密 scheme、supported 和 enabled | 明确拒绝降级，且错误通过已有 RTSP stageFailed 和 cleanup 路径返回；DESCRIBE early exit 释放 response。原协商：`C/src/RtspConnection.c:1149`、`:1333`；`C/src/Connection.c:440`、`:542` |

```diff
--- a/src/Limelight.h
+++ b/src/Limelight.h
@@ -420,2 +420,6 @@

+// Miffan fork: setup errors, also reported via stageFailed().
+#define ML_ERROR_ENCRYPTION_REQUIRED -110
+#define ML_ERROR_UNSUPPORTED_TRANSPORT -111
+
 // This error is passed to ConnListenerConnectionTerminated() if a fully formed
@@ -538,2 +542,12 @@
     int serverCodecModeSupport;
+
+    // Optional split transport for modern Sunshine only. address remains the
+    // selected numeric UDP destination; these fields identify a local SSH relay.
+    // NULL/0 preserves the existing direct connection behavior.
+    const char* rtspTcpAddress;
+    unsigned short rtspTcpPort;
+
+    // Reject missing RTSP, video, audio, or control-v2 encryption support.
+    // The caller must also request ENCFLG_AUDIO | ENCFLG_VIDEO.
+    bool requireEncryptedStreams;
 } SERVER_INFORMATION, *PSERVER_INFORMATION;
--- a/src/Limelight-internal.h
+++ b/src/Limelight-internal.h
@@ -18,2 +18,5 @@
 extern struct sockaddr_storage RemoteAddr;
+extern struct sockaddr_storage RtspTcpAddr;
+extern SOCKADDR_LEN RtspTcpAddrLen;
+extern uint16_t RtspTcpPortNumber;
 extern struct sockaddr_storage LocalAddr;
--- a/src/Connection.c
+++ b/src/Connection.c
@@ -11,2 +11,5 @@
 struct sockaddr_storage RemoteAddr;
+struct sockaddr_storage RtspTcpAddr;
+SOCKADDR_LEN RtspTcpAddrLen;
+uint16_t RtspTcpPortNumber;
 struct sockaddr_storage LocalAddr;
@@ -281,2 +284,12 @@

+    if ((serverInfo->rtspTcpAddress == NULL) != (serverInfo->rtspTcpPort == 0) ||
+            (serverInfo->rtspTcpAddress != NULL &&
+             (!IS_SUNSHINE() || !APP_VERSION_AT_LEAST(7, 1, 431)))) {
+        Limelog("Split RTSP transport requires modern Sunshine and both relay fields\n");
+        err = ML_ERROR_UNSUPPORTED_TRANSPORT;
+        goto Cleanup;
+    }
+    RtspTcpPortNumber = serverInfo->rtspTcpAddress != NULL ?
+        serverInfo->rtspTcpPort : RtspPortNumber;
+
     alreadyTerminated = false;
@@ -347,3 +360,12 @@
     LC_ASSERT(RtspPortNumber != 0);
-    if (RtspPortNumber != 48010) {
+    if (serverInfo->rtspTcpAddress != NULL) {
+        // No TCP probes against the media destination. The app selects one
+        // numeric candidate at a time and keeps the SSH relay alive.
+        err = resolveHostName(serverInfo->address, AF_UNSPEC, 0, &RemoteAddr, &AddrLen);
+        if (err == 0) {
+            err = resolveHostName(serverInfo->rtspTcpAddress, AF_UNSPEC, 0,
+                                  &RtspTcpAddr, &RtspTcpAddrLen);
+        }
+    }
+    else if (RtspPortNumber != 48010) {
         // If we have an alternate RTSP port, use that as our test port. The host probably
@@ -380,3 +402,8 @@

-    // Resolve LocalAddr by RemoteAddr.
+    if (serverInfo->rtspTcpAddress == NULL) {
+        memcpy(&RtspTcpAddr, &RemoteAddr, sizeof(RtspTcpAddr));
+        RtspTcpAddrLen = AddrLen;
+    }
+
+    // Resolve LocalAddr by the UDP destination, not the SSH relay.
     {
--- a/src/RtspConnection.c
+++ b/src/RtspConnection.c
@@ -406,3 +406,3 @@
     do {
-        sock = connectTcpSocket(&RemoteAddr, AddrLen, RtspPortNumber, RTSP_CONNECT_TIMEOUT_SEC);
+        sock = connectTcpSocket(&RtspTcpAddr, RtspTcpAddrLen, RtspTcpPortNumber, RTSP_CONNECT_TIMEOUT_SEC);
         if (sock == INVALID_SOCKET) {
@@ -955,2 +955,14 @@
     encryptedRtspEnabled = serverInfo->rtspSessionUrl && strstr(serverInfo->rtspSessionUrl, "rtspenc://");
+    if (serverInfo->rtspTcpAddress != NULL && useEnet) {
+        return ML_ERROR_UNSUPPORTED_TRANSPORT;
+    }
+    if (serverInfo->requireEncryptedStreams &&
+            (!IS_SUNSHINE() || !APP_VERSION_AT_LEAST(7, 1, 431) ||
+             (StreamConfig.encryptionFlags & (ENCFLG_VIDEO | ENCFLG_AUDIO)) !=
+                 (ENCFLG_VIDEO | ENCFLG_AUDIO) ||
+             serverInfo->rtspSessionUrl == NULL ||
+             strncmp(serverInfo->rtspSessionUrl, "rtspenc://", 10) != 0)) {
+        Limelog("Required encrypted RTSP/streams are unavailable\n");
+        return ML_ERROR_ENCRYPTION_REQUIRED;
+    }
     encryptionCtx = PltCreateCryptoContext();
@@ -1156,2 +1168,10 @@
         EncryptionFeaturesEnabled = 0;
+        if (serverInfo->requireEncryptedStreams &&
+                (EncryptionFeaturesSupported & (SS_ENC_VIDEO | SS_ENC_AUDIO | SS_ENC_CONTROL_V2)) !=
+                    (SS_ENC_VIDEO | SS_ENC_AUDIO | SS_ENC_CONTROL_V2)) {
+            Limelog("Host cannot provide all required stream encryption\n");
+            freeMessage(&response);
+            ret = ML_ERROR_ENCRYPTION_REQUIRED;
+            goto Exit;
+        }

@@ -1206,2 +1226,8 @@
             memcpy(AudioPingPayload.payload, pingPayload, sizeof(AudioPingPayload.payload));
+        }
+
+        if (serverInfo->rtspTcpAddress != NULL && AudioPingPayload.payload[0] == 0) {
+            freeMessage(&response);
+            ret = ML_ERROR_UNSUPPORTED_TRANSPORT;
+            goto Exit;
         }
@@ -1273,2 +1299,8 @@

+        if (serverInfo->rtspTcpAddress != NULL && VideoPingPayload.payload[0] == 0) {
+            freeMessage(&response);
+            ret = ML_ERROR_UNSUPPORTED_TRANSPORT;
+            goto Exit;
+        }
+
         // Parse the video port out of the RTSP SETUP response
@@ -1310,2 +1342,7 @@
         connectData = getOptionContent(response.options, "X-SS-Connect-Data");
+        if (serverInfo->rtspTcpAddress != NULL && (connectData == NULL || connectData[0] == 0)) {
+            freeMessage(&response);
+            ret = ML_ERROR_UNSUPPORTED_TRANSPORT;
+            goto Exit;
+        }
         if (connectData != NULL) {
@@ -1350,2 +1387,11 @@
         freeMessage(&response);
+    }
+
+    // SDP generation has now selected the effective encryption modes.
+    if (serverInfo->requireEncryptedStreams &&
+            ((EncryptionFeaturesEnabled & (SS_ENC_VIDEO | SS_ENC_AUDIO | SS_ENC_CONTROL_V2)) !=
+                 (SS_ENC_VIDEO | SS_ENC_AUDIO | SS_ENC_CONTROL_V2) || !AudioEncryptionEnabled)) {
+        Limelog("Negotiated stream encryption does not meet policy\n");
+        ret = ML_ERROR_ENCRYPTION_REQUIRED;
+        goto Exit;
     }
```

**调用约定／推断**：P6b JNI 在零初始化的 `SERVER_INFORMATION` 中设置：

```c
// udpCandidate 是 App 已选定的数值地址；不得填 SSH relay 地址。
serverInfo.address = udpCandidate;
serverInfo.rtspSessionUrl = launchSessionUrl; // 原样保留 rtspenc:// 和远端端口
serverInfo.rtspTcpAddress = "127.0.0.1";
serverInfo.rtspTcpPort = localSshRtspForwardPort;
serverInfo.requireEncryptedStreams = true;
streamConfig.encryptionFlags = ENCFLG_ALL;
```

每次尝试使用新的 `rikey/rikeyid`，同样的值提供给 HTTP launch 与 common-c；App 管理 SSH forward 的生存期、已核验主机证书与 HTTP／HTTPS 的本地端口。`rtspTcpAddress` 限定为手机本地 loopback，`address` 传一个候选数值地址，避免零 TCP probe 时 hostname 多地址解析替 App 偷选路径。原有 API 仍允许未设置字段的直连调用；strict 检查证明本客户端的协议能力／协商满足要求，主机 LAN/WAN 配置为 2 仍需后续经 SSH 核实。依据：`C/src/Limelight.h:98`；`C/src/PlatformSockets.c:712`；`S/src/nvhttp.cpp:481`、`:510`。

### 附录 B：Sunshine 可选的主机强制控制加密补全

地址分离本身不需要 Sunshine 补丁。这个独立草案只补全“模式 2 对所有接入客户端都要求现代控制加密”的检查；不用于修改音频密码格式，也不会自动安装到任何主机。路径相对 Sunshine 根目录。

```diff
--- a/src/rtsp.cpp
+++ b/src/rtsp.cpp
@@ -1294,3 +1294,5 @@
     auto encryption_mode = net::encryption_mode_for_address(sock.remote_endpoint().address());
-    if (encryption_mode == config::ENCRYPTION_MODE_MANDATORY && (config.encryptionFlagsEnabled & (SS_ENC_VIDEO | SS_ENC_AUDIO)) != (SS_ENC_VIDEO | SS_ENC_AUDIO)) {
+    if (encryption_mode == config::ENCRYPTION_MODE_MANDATORY &&
+        ((config.encryptionFlagsEnabled & (SS_ENC_VIDEO | SS_ENC_AUDIO | SS_ENC_CONTROL_V2)) !=
+           (SS_ENC_VIDEO | SS_ENC_AUDIO | SS_ENC_CONTROL_V2) || config.controlProtocolType != 13)) {
       BOOST_LOG(error) << "Rejecting client that cannot comply with mandatory encryption requirement"sv;
```

## 5. 本次校验与第二部分验收边界

**本次已完成**：逐项交叉阅读 Sunshine/common-c 与 Android launch/JNI 参考；两个 unified diff 均用 `git apply --check` 在 `/private/tmp` 的一次性原文件副本上通过，未应用到 `.deps`。只提交本文，不修改 P6a spec、产品代码、原计划或调研文件。没有编译和运行验证；diff 能匹配原文件不等于构建或行为已通过。

**后续待用户另行授权后验证**：

1. TCP 对端 loopback、UDP 对端手机真实 IP 的首连，IPv4/IPv6 与多网卡的回送源地址；分别只阻断 47998、47999、48000，记录 error、阶段和墙钟时间。
2. 主机 LAN/WAN 分别 0/1/2、去掉视频／音频／CONTROL_V2 广告、返回明文 RTSP scheme，确认严格客户端拒绝；确认音频 CBC 与文档描述一致，主机可选补丁对旧控制协议的拒绝。
3. 控制未建立时快速重试、控制已建立后视频失败的断开／resume、Wi-Fi→蜂窝及 NAT rebind，确认旧会话实际释放且不影响用户已有串流。
4. 编译 JNI／native API 改动，检查直接连接仍兼容、旧 split 协议拒绝、缺标识拒绝；确认有效首帧期限能处理“收到垃圾包后静默”的情况。公网安全边界另行验收，不能仅用握手成功代替。
