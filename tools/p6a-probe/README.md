# p6a-probe（P6a 第二部分）

macOS 命令行验证工具：HTTP／HTTPS／RTSP 经本机 SSH TCP 转发，媒体与控制 UDP 使用单独的数字 IP；只统计完整视频帧和音频包，不解码、不显示、不播放声音。

本次交付只编译和离线自检，未连接任何远程主机、运行 SSH 或安装软件。远程验证由 Claude 在已有授权范围内执行。依据：[P6A2_SPEC.md](../../docs/remote-screen/P6A2_SPEC.md)、[第一部分核实](../../docs/remote-screen/P6A_NOTES.md)。

## 编译与离线检查

```sh
cd tools/p6a-probe
make -j4
make check
build/p6a-probe --help
build/p6a-probe pair --help
build/p6a-probe stream --help
```

依赖：本机 Apple clang、make、Python 3、macOS SDK 的 curl／expat／uuid，以及已安装的 Homebrew OpenSSL 3。不使用 cmake、brew 安装或下载。

```sh
make DEPS_DIR=/Users/chenxiansheng/miffan/.deps/p6a \
     OPENSSL_DIR=/opt/homebrew/opt/openssl@3
```

`prepare.py` 只复制离线依赖中的 `.c`／`.h`／license 到 `build/src/`，先干净检查补丁，再应用到副本。原始 `.deps` 保持只读。二进制、源码副本、对象文件与日志都在被忽略的 `build/` 下。换依赖目录／编译器选项时先 `make clean`；补丁变化会自动重新复制并编译。

基线：common-c `f900dd4`、ENet `aca8784`、nanors `b1e3c22`、embedded `f32e415`。编译 common-c 和工具自有代码使用 `-Werror`；第三方代码也启用 `-Wall -Wextra`。链接的是本机 OpenSSL 动态库，不是可脱离依赖分发的 standalone binary。继承 common-c／embedded 的 GPLv3 许可；完整源文件的版权和许可保留在本地构建副本中。

`make check` 的所有 socket 都绑定或连接 loopback，所有身份／测试主机证书都在临时目录中新生成，结束后删除。检查项包括：帮助、参数拒绝、完整 PIN challenge 配对、0600／0700 权限、DER pin 保存、正确 pin 的 HTTPS、chunked XML、暂停应用的 resume、每次新 rikey、错误 pin 在 HTTP 请求前拒绝、坏配对 challenge 不存 pin、不 cancel/unpair、native 严格模式拒绝明文 RTSP、不同 UDP 地址下使用指定 RTSP TCP relay、无转发的快速失败、Ctrl+C。受限沙箱可能禁止本机监听端口；该自检只需要允许 loopback，不需要互联网权限。

## 实测命令（供 Claude 执行，本次没有执行）

先确认 Sunshine 的 LAN/WAN encryption mode 都为 2，保持其他用户会话不受影响。以下使用默认端口；SSH 主机别名／UDP 数字 IP 由实际测试环境填写。

```sh
# 独立终端维持已核验主机身份的 SSH 转发。
ssh -N -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:47989:127.0.0.1:47989 \
  -L 127.0.0.1:47984:127.0.0.1:47984 \
  -L 127.0.0.1:48010:127.0.0.1:48010 CACHYOS_SSH_ALIAS
```

```sh
cd tools/p6a-probe
KEYDIR="$(mktemp -d /private/tmp/miffan-p6a.XXXXXX)"
build/p6a-probe pair --http-host 127.0.0.1 --keydir "$KEYDIR" --name miffan-p6a
# 在 Sunshine 网页里输入终端显示的四位 PIN。
# 保留这个 KEYDIR；不要借用官方 Moonlight 的证书或身份。

build/p6a-probe stream --http-host 127.0.0.1 --keydir "$KEYDIR" \
  --udp-host REAL_NUMERIC_IP --rtsp-tcp 127.0.0.1:48010 \
  --app Desktop --seconds 30 --width 1920 --height 1080 --fps 60 \
  --bitrate 15000 --codec hevc --strict --json build/strict-first.json

# 明确允许的一次 +1/-1 水平像素移动；仅 --input-test 会发鼠标操作。
build/p6a-probe stream --keydir "$KEYDIR" \
  --udp-host REAL_NUMERIC_IP --rtsp-tcp 127.0.0.1:48010 \
  --seconds 30 --input-test --json build/input-test.json

# 对照测试：仍请求 ENCFLG_ALL，但关闭严格的能力/协商拒绝策略。
build/p6a-probe stream --keydir "$KEYDIR" \
  --udp-host REAL_NUMERIC_IP --rtsp-tcp 127.0.0.1:48010 \
  --codec h264 --no-strict --json build/non-strict-h264.json
```

如果本机默认端口已占用，可转发到其他本地端口，并传 `--http-port PORT --https-port PORT --rtsp-tcp 127.0.0.1:PORT`。主机返回的 HTTPS／RTSP 远端端口不会覆盖这些本地 forward 端口。HTTP host 和 RTSP relay 只接受 `127.0.0.1`，禁用代理和重定向；UDP 地址接受数字 IPv4／IPv6，不接受 hostname。

`pair` 只在新的私有目录里自动生成身份；已有目录要求当前用户所有、0700，文件要求为非 symlink 的当前用户所有普通文件、0600。保存 `client.pem`、`key.pem`、随机 `uniqueid.dat`，不创建 PKCS#12 副本、不打印私钥。配对通过 PIN challenge 和签名验证得到主机证书，在最后的 HTTPS pairchallenge 前即核对 DER pin；完整配对成功后写 `host-cert.sha256` 并打印。已有 pin 不会被替换为不同的证书。

`stream` 必须先有完整身份和 pin。每次 HTTPS 连接在 TLS 握手后、发送任何 HTTP 请求之前核对证书 DER SHA-256；使用 OpenSSL 直接实现 TLS，因为 macOS SDK curl 的 TLS backend 不能作为 OpenSSL SSL_CTX 回调使用。HTTP 只在首次配对读取 serverinfo／交换 challenge 时使用；stream 不从 HTTPS 回退 HTTP。

`currentgame != 0` 时 resume，否则 launch；即使 Sunshine 处于 SERVER_FREE、应用已暂停，也保留 currentgame 来决定 resume。launch/resume 每次随机生成新的 rikey/rikeyid。工具不会调用 `/cancel` 或 `/unpair`，配对失败也不会自动 unpair；HTTP 层还限制允许的路径。停止串流保留主机正在运行的应用。

## 输出与时间口径

终端逐阶段输出事件，最后输出汇总；`--json` 输出结构化结果。JSON 文件必须是新文件，拒绝覆盖或跟随 symlink，防止误覆盖测试身份。文件权限 0600。没有 JSON 选项时只输出终端信息。

| 字段 | 含义 |
| --- | --- |
| `stages[].kind/stage/ms/error` | starting／complete／failed，native 阶段号、距进程开始的毫秒、错误码。stage -1 表示 HTTP/身份初始化；终端另显示 native 阶段名 |
| `startup_error` | LiStartConnection 返回码；`-110` 加密要求不满足，`-111` split 协议／会话标识不可用；native 阶段错误见 stages |
| `connection_terminated/termination_error` | 是否收到 connectionTerminated 及其错误；未收到时 error 为 null。可能是 `-100` 无视频流量、`-101` 无完整帧、控制握手超时等，不保证所有 UDP 故障都报 -100 |
| `connection_started_ms` | 距进程开始的 connectionStarted 时间；未完成为 -1 |
| `negotiation.available` | 是否完成 RTSP 握手；false 时下面的 0／false 不是成功协商证明 |
| `negotiation.supported/requested/enabled` | common-c 的实际 Sunshine encryption 位图：CONTROL_V2=1、VIDEO=2、AUDIO=4 |
| `video_encrypted/audio_encrypted/control_encrypted/control_v2` | common-c 实际选择的加密；controlEncrypted 表示现代版本的加密控制协议，controlV2 区分新 IV 格式；音频仍是 AES-CBC，视频／控制为 AES-GCM |
| `video.format/width/height/requested_fps` | setup 回调的编码位图和尺寸、流帧率；H.264=1、HEVC=256。HEVC 模式同时提供 H.264 fallback，h264 只提供 H.264 |
| `video.first_complete_frame_ms` | 距开始 LiStartConnection 的首个完整帧时间，未收帧为 -1；并非解码或显示延迟 |
| `video.frames/idr/bytes` | submitDecodeUnit 完整帧、IDR 和压缩视频字节数；不包括所有 UDP／FEC wire bytes |
| `video.observed_seconds/average_fps/samples` | 从 native 开始至断开前的观测时间、平均 fps；samples 给每次约一秒采样的实际间隔 fps、距 native 开始的 ms、累计帧数。第一段包含建流时间，不是纯稳态帧率 |
| `audio_packets` | 已收到并解密、交给音频回调的压缩包数，不播放音频 |
| `input_test.sent/forward/reverse` | 是否在串流开始至少一秒且收到至少两帧后执行 +1/-1；两次 LiSendMouseMoveEvent 的返回码，通常 0 表示排队成功，不证明主机桌面移动已观察到 |
| `rtt.available/ms/variance_ms` | 断开前 LiGetEstimatedRttInfo 的 ENet RTT／方差，单位 ms；没有有效控制 peer 时 unavailable |
| `result/interrupted/error` | 退出结果、中断标记和可读错误；不记录会话 key、HTTP URL、原始 pairing payload 或 native debug payload |

退出码：0 完成，1 连接／协议／无完整帧失败，2 参数／JSON 写入失败，130 Ctrl+C。HTTP connect 预算 3 秒，HTTPS 请求总预算 20 秒；PIN 请求可等 180 秒。native 建流有 45 秒取消预算；控制握手原有 10 秒预算不变。连接完成后运行 `--seconds` 秒，无完整视频帧超过 10 秒额外终止（补足 common-c 收到垃圾包后静默的检测缺口）。Ctrl+C／SIGTERM 在主循环处理：建流中 LiInterruptConnection，等待启动线程退出；建流成功后 LiStopConnection 并等待回调结束，不在 signal handler 中调用库的清理函数。

## 相对第一部分草案的修正

`0001-split-transport-strict-encryption.patch` 保留附录 A 的独立 RTSP TCP sockaddr／端口、严格加密检查和新会话标识检查，增加：

- 每次连接清空 negotiation 位图／音频加密状态，避免失败尝试报告上一次结果。
- 只读 `LiGetP6aNegotiation()`，供完整 RTSP 握手后／startup 返回时取快照；不是线程安全的任意时刻查询接口。
- 去掉原库自动唤醒屏幕的鼠标移动，输入测试明确 opt-in。

`0002-libgamestream-probe.patch` 移除 cancel/unpair 接口、配对失败自动 unpair、固定 uniqueid 和 PKCS#12 副本；补全 PIN challenge hash 校验、配对长度校验、随机 session key 失败处理、resume XML 节点选择、暂停 Sunshine 应用的 currentgame 保留、HTTPS 失败不降级 HTTP、转发端口固定和客户端名称。HTTP 实现接到 probe-owned DER pin transport；不会用 SPKI pin 冒充整个证书的 DER 指纹。修正其 signed-byte／signed-size 编译警告。

本次离线通过不表示媒体 UDP、公网风险、音频认证、切网恢复或主机 session 清理已验收。第二部分的真实流量、帧率、RTT 和网络失败时间仍需 Claude 实测；工具不会代替授权去更改主机配置或清除别人的串流。
