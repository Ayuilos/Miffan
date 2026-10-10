# P6a 第二部分：实机验证用的 Mac 命令行工具

第一部分结论见 `P6A_NOTES.md`。本部分做一个在 Mac 上运行的命令行工具 `p6a-probe`，用打了附录 A 补丁的 common-c 连接 CachyOS 上的 Sunshine：TCP（HTTP / HTTPS / RTSP）经 `ssh -L` 转发到本机，UDP 直连主机真实地址。**你只负责编译和离线自检；连接远程机器的实测由 Claude 执行。**

用户已授权：用 Miffan 自己的测试客户端身份与用户 CachyOS 上的 Sunshine 配对；把该 Sunshine 的 `lan_encryption_mode`、`wan_encryption_mode` 设为 2（由 Claude 改）。

## 源码（已下载，不联网）

在 `/Users/chenxiansheng/miffan/.deps/p6a/`，只读，不要修改：

- `common-c`（`f900dd4`），子模块已就位：`common-c/enet`（`aca8784`）、`common-c/nanors`（`b1e3c22`）
- `embedded`：moonlight-embedded（`f32e415`），用其中的 `libgamestream`（HTTP、配对、launch / resume、证书生成）
- `sunshine`、`android`：参考

本机工具：Apple clang、make、Homebrew 的 `openssl@3`（`/opt/homebrew/opt/openssl@3`）。没有 cmake，不要安装任何东西；用 Makefile 直接编译。macOS SDK 自带 libcurl、libexpat、uuid。

## 文件（全部在你的 worktree 内）

- `tools/p6a-probe/Makefile`：从 `.deps/p6a` 复制源码到 `tools/p6a-probe/build/src/`（不进 git），应用补丁后编译。
- `tools/p6a-probe/patches/0001-split-transport-strict-encryption.patch`：`P6A_NOTES.md` 附录 A 的补丁，整理成可 `git apply` / `patch -p1` 的文件。如有必要可修正草案，并在补丁顶部说明改了什么。
- `tools/p6a-probe/patches/` 下可以加 libgamestream 的补丁（例如证书固定），同样说明原因。
- `tools/p6a-probe/probe.c`（可拆成多个文件）。
- `tools/p6a-probe/README.md`：编译方法、命令、输出字段。
- `build/` 加入 `.gitignore`。

## 命令

### `p6a-probe pair`

```
p6a-probe pair --http-host 127.0.0.1 --keydir <DIR> [--name miffan-p6a]
```

- `<DIR>` 内没有密钥时生成客户端证书与私钥，权限 0600，目录 0700。密钥只在 `<DIR>`，不进 git，不打印私钥。
- 打印 4 位 PIN，提示“在 Sunshine 网页里输入 PIN”，等待配对完成。
- 成功后把主机证书的 DER SHA-256 写入 `<DIR>/host-cert.sha256` 并打印。

### `p6a-probe stream`

```
p6a-probe stream --http-host 127.0.0.1 --keydir <DIR>
                 --udp-host <数字 IP> --rtsp-tcp 127.0.0.1:48010
                 [--app Desktop] [--seconds 30] [--width 1920 --height 1080 --fps 60]
                 [--bitrate 15000] [--codec hevc|h264] [--strict | --no-strict]
                 [--input-test] [--json <FILE>]
```

- HTTPS 前核对主机证书 SHA-256 与 `<DIR>/host-cert.sha256` 一致，不一致就退出。
- 应用已在运行时用 `/resume`，否则 `/launch`。**任何情况下都不调用 `/cancel`**，也不 unpair。
- 每次运行生成新的 `rikey` / `rikeyid`。
- `LiStartConnection` 使用补丁的拆分字段：`address = --udp-host`，`rtspTcpAddress/rtspTcpPort = --rtsp-tcp`。`--strict`（默认开）设置 `requireEncryptedStreams = true` 和 `ENCFLG_ALL`；`--no-strict` 关闭严格检查，`ENCFLG_ALL` 不变。
- 视频回调不解码，只统计：首个完整帧时间、帧数、IDR 数、字节数，每秒 fps。音频回调只计包数。
- `--input-test`：串流稳定后发送一次相对鼠标移动 +1 px，再 −1 px，记录调用结果。
- 运行 `--seconds` 秒后 `LiStopConnection` 正常断开。Ctrl+C 也走同样的断开路径。
- 输出（终端 + `--json`）：各阶段开始 / 完成时间（stageStarting / stageComplete / stageFailed，含错误码）、`connectionTerminated` 错误码、`EncryptionFeaturesEnabled` 等实际协商结果（视频、音频、控制是否加密；若需要可经补丁暴露只读查询函数）、协商的编码与分辨率、帧数与 fps、音频包数、`LiGetEstimatedRttInfo`。

## 自检（离线）

- `make` 在本机编译通过，无警告的部分尽量保持干净。
- `p6a-probe --help`、`pair --help`、`stream --help` 正常。
- 不带服务器运行 `stream`（例如 `--http-host 127.0.0.1` 但本地无转发）时，在合理时间内以清楚的错误退出。
- 补丁可对原始 common-c 干净应用。

## 交付与约束

- 在 worktree 分支 `codex/p6a-verify` 提交，不提交到 `feature/im-4.0`。
- 不联网、不 ssh、不连接任何远程机器，不安装软件。
- 不读取或复制签名配置、`local.properties`、任何已有私钥。
- 完成后简短汇报：编译结果、补丁对草案的修正、实测时 Claude 需要的命令示例。
