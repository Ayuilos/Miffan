# P6a 验证任务（第一部分：源码核实）

背景见 `P6_SUNSHINE_PLAN.md` 与 `research/SUNSHINE_MOONLIGHT.md`。本部分只读源码、写结论和补丁草案，**不连接任何远程机器，不安装、不运行 Sunshine 或 Moonlight**。实机验证是第二部分，需用户另行授权。

## 源码（已下载，无需联网）

- `/Users/chenxiansheng/miffan/.deps/p6a/sunshine`：Sunshine `v2026.1008.43744`（`d6453a3`）
- `/Users/chenxiansheng/miffan/.deps/p6a/common-c`：moonlight-common-c `f900dd4`
- `/Users/chenxiansheng/miffan/.deps/p6a/android`：moonlight-android master（参考 HTTP / 配对 / 解码实现）

只读这些目录，不要修改。

## 要回答的问题

### 1. 地址分离

设计：手机经 SSH direct-tcpip 把 HTTPS（47984）、HTTP（47989）、RTSP（48010）转发到主机 `127.0.0.1`；UDP 47998–48000 直接发往主机的真实地址（局域网 / Tailscale / 公网）。于是主机看到的 TCP 对端是 `127.0.0.1`，UDP 对端是手机的真实地址（可能经过 NAT）。

- Sunshine：launch、RTSP、视频 / 音频 ping（`av_ping_payload`）、控制流（`control_connect_data`、`ML_FF_SESSION_ID_V1`）分别怎样把 UDP 包关联到会话？哪里还会比较对端 IP（例如 `get_session(peer, ...)`、RTSP 会话的地址检查、单会话限制、按地址判断 LAN / WAN）？TCP 对端为 `127.0.0.1` 时会不会被拒绝或误判？
- 对端地址被 NAT 改写、或手机网络切换后地址变化时会怎样？
- common-c：`RemoteAddr` 同时用于 TCP（RTSP / 端口探测）和 UDP（视频、音频、ENet 控制），`LocalAddr`、`isPrivateNetworkAddress`、packet size 的 AUTO 判断也依赖它。要让 TCP 连本地转发端口、UDP 发往真实地址，最小的补丁是什么？给出补丁草案（diff），说明每处改动的原因。HTTP / HTTPS 不在 common-c 中，由 App 的 Kotlin 层负责，可以忽略。
- 若协议上根本不可行，说明阻断点和证据。

### 2. 强制加密

- Sunshine `lan_encryption_mode` / `wan_encryption_mode` 设为 2 时，视频、音频、控制、输入各自是否加密？客户端不支持时是否拒绝？TCP 对端为 `127.0.0.1` 时用哪一个策略？
- common-c 设 `encryptionFlags=ENCFLG_ALL` 后，若主机不加密，客户端是否会拒绝，还是会静默降级？如果会降级，给出让客户端拒绝的补丁草案。
- 公网上能看到什么：在加密开启时，UDP 包中还有哪些明文（序号、帧头、FEC 等）？对“只开放 UDP 47998–48000、TCP 全走 SSH”的模型是否有实际风险？

### 3. 路径失败的检测

- 某个候选地址的 UDP 不通时，common-c 会以什么错误、在多长时间后失败（`ML_ERROR_NO_VIDEO_TRAFFIC` 等）？能否更早判断（例如控制流 ENet 握手超时）？主机端失败的会话会不会残留、影响下一次尝试？

## 交付

- `docs/remote-screen/P6A_NOTES.md`：逐题结论，每条附源码位置（文件:行号）；区分“已核实”和“推断”。
- 补丁草案以 diff 形式附在文档中，不需要编译。
- 在你的 worktree 分支提交这两个文件以外的任何改动都不需要；不要提交到 `feature/im-4.0`。

## 约束

- 不连接任何远程机器（包括 CachyOS 测试机和用户的账号），不运行网络命令。
- 不读取或复制密钥、签名配置、`local.properties`。
