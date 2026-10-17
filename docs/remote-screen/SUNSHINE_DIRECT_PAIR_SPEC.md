# Sunshine 直接配对（免 PIN）规格

## 背景与决定

现在高性能模式配对要用户把 App 显示的 4 位 PIN 输进 Sunshine 网页后台（要 Sunshine 管理员密码），
对普通用户太重。用户决定（2026-10-17）：App 已通过 SSH 以该账号控制主机，PIN 不再带来额外保护；
改为由 helper 直接把本机客户端证书写进 Sunshine 的 `named_devices`，服务端证书沿用 helper 读出、
经 SSH 背书的 DER pin。手动 PIN 流程保留，只作兜底。

Sunshine 上游 `src/nvhttp.cpp`（已核对）：`named_devices` 每项为 `name`、`cert`（PEM）、`uuid`、
`enabled`（缺省 true，老版本无此字段并忽略）。`load_state()` 只在启动时调用，所以写入后必须重启。
Sunshine 自己的 `add_authorized_client` 先删除同一证书的旧记录再追加，UUID 为大写规范格式
（`XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX`）。

## 你负责的文件

- `remote-helper/src/miffan.sh`、`remote-helper/src/windows/miffan.ps1`（及其 include）
- `remote-helper/versions.lock`、`remote-helper/PROTOCOL.md`、`remote-helper/fixtures/**`、`remote-helper/tests/**`
- `app/src/main/java/me/ayuilos/miffan/data/repository/`：`RemoteSunshineUnpair.kt`（可加 pair 方法或拆出同目录新类）、
  `RemoteScreenRepository.kt`、`RemoteStreamModels.kt`
- `app/src/test/java/me/ayuilos/miffan/data/repository/` 下对应测试

不要改 `app/src/main/java/me/ayuilos/miffan/ui/**`（界面由 Claude 做）、不要改 `stream` 模块，
不要连接或修改任何真实主机（Windows 测试机、CachyOS、Mac），不要向 `feature/im-4.0` 提交。
在你的 worktree 分支 `feature/sunshine-direct-pair` 上提交即可。

## 1. helper 命令 `sunshine pair`

两个平台都加，参数校验、服务模型判定、停服务 → 重读 → 原子替换（保留属主/权限/ACL 与所有未知字段）→
按原运行状态重启的流程与现有 `sunshine unpair` 完全一致，尽量复用其代码，不要复制一份再改。

- 调用：`<helper> sunshine pair`，stdin 一行 JSON `{"name":"<名称>","cert":"<PEM>"}`。不接受命令行里的证书或名称。
- 校验：
  - `name` 非空、≤64 字符、无控制字符；
  - `cert` 恰好一个 `-----BEGIN CERTIFICATE-----` 块且能解析为 X.509；含私钥标记或多余内容直接拒绝。
    写入时用规范化 PEM（DER 重新编码，Python `ssl.DER_cert_to_PEM_cert` / Windows 等价），不原样写入 stdin 文本。
- 状态文件：路径解析同 unpair（`file_state` 覆盖等）。文件必须已存在且为普通文件；`root` 必须是对象；
  `root.named_devices` 缺失则新建空数组；存在但不是数组，或任一已有项不是含字符串 `name`/`cert`/`uuid` 的对象 →
  失败 `Unrecognized Sunshine state format`（不修改任何东西）。
- 写入：删除所有与该证书 DER 相同的已有项（按证书本身比较，不按名称/UUID），追加
  `{"name":…,"cert":<规范 PEM>,"uuid":<新的大写 UUID4>,"enabled":true}`。
- Sunshine 正在运行：停 → 重读 → 写 → 启动，`restarted=true`。未运行：只写文件，不启动，`restarted=false`。
- 未安装或服务模型无法确认：失败，不写。
- 成功输出（整段 stdout 为一行 JSON，严格解析）：
  `{"added":true,"replaced":<删除的旧项数>,"restarted":<bool>,"client_sha256":"<小写 64 hex，写入证书的 DER SHA-256>"}`
- 失败沿用各平台现有约定（POSIX `finish(False, detail)`；Windows SafeFailures 白名单，新 detail 都要加进白名单）。
  至少区分这些 detail，App 据此决定是否退回 PIN：
  - `Unrecognized Sunshine state format`
  - `Cannot read Sunshine state or configuration; check administrator rights`（Windows 已有）
  - `Cannot edit Sunshine state or control its service; check administrator rights`（Windows 已有，POSIX 对应权限错误也用同一句）
  - `Sunshine pair attempted; could not restart Sunshine`（已改文件但没能重启）
- 日志、stderr、detail 都不得包含证书全文或名称以外的用户数据。
- helper 版本号两份都要升（`versions.lock` 同步 sha256），`PROTOCOL.md` 两张命令表补上 pair 行和成功/失败形状。

测试（`remote-helper/tests/test_sunshine_helper.py` + golden fixtures，仿照 unpair 用例）：
新增、替换同证书旧项（含 2 条重复）、未运行不启动、未知字段原样保留、权限/属主保留、
格式不认识拒绝且文件字节不变、坏 PEM / 多证书 / 含私钥 / 超长或含控制字符名称拒绝、停服务后 Sunshine 刷出的新状态被保留。
Windows：在 `remote-helper/tests/windows/Test-Helper.ps1` 补等价离线用例（PS 5.1 的 `ConvertTo-Json -Depth 100` 保留深层字段）。
本机没有 pwsh，跑不了就在交付说明里写明，不要假装跑过。

## 2. App 数据层

在 `RemoteScreenRepository` 新增（签名按此实现，界面会直接调用）：

```kotlin
sealed interface RemoteStreamDirectPairing {
    /** Sunshine now trusts this phone; [certificateSha256] is the pinned server certificate. */
    data class Paired(val certificateSha256: String) : RemoteStreamDirectPairing
    /** Someone is streaming from this computer; pairing restarts Sunshine and would cut them off. */
    data object ActiveStream : RemoteStreamDirectPairing
    /** Direct pairing cannot be done here; offer the manual PIN pairing instead. */
    data class ManualPinNeeded(val reason: RemoteStreamManualPinReason) : RemoteStreamDirectPairing
}
enum class RemoteStreamManualPinReason { CERTIFICATE_UNAVAILABLE, STATE_UNRECOGNIZED, NO_PERMISSION, NOT_CONFIRMED }

suspend fun pairStreamDirectly(hostId: String, expectedRevision: String,
    clientName: String = "Miffan (${Build.MODEL})".take(64),
    interruptActiveStream: Boolean = false): RemoteStreamDirectPairing
```

流程（复用 `startStreamPairing` 已有的 revision 检查、`ensureHelper`、`sunshineProbe`、`streams.verifyCertificate`）：

1. 检查 revision、装 helper、probe；`streams.verifyCertificate(row.streamCertificateSha256, probe)` 失败照旧抛出。
2. `check(probe.installed && probe.running) { "Sunshine is not running" }`（与现有一致）。
3. `probe.certificateSha256 == null` → `ManualPinNeeded(CERTIFICATE_UNAVAILABLE)`。
4. `probe.activeStream && !interruptActiveStream` → `ActiveStream`，不改主机。
5. 用 `RemoteStreamIdentityStore` 的证书调用 helper `sunshine pair`（stdin 传 JSON，名称/证书不进命令行），超时 45 s。
   - 输出 `client_sha256` 必须等于本地 `streamClientCertificateSha256(pem)`，否则抛错。
   - `Unrecognized Sunshine state format` → `ManualPinNeeded(STATE_UNRECOGNIZED)`；两句 administrator rights → `ManualPinNeeded(NO_PERMISSION)`；
     其他失败（含 could not restart）抛出，带 helper detail。
6. 等 Sunshine 用 pinned 证书（`probe.certificateSha256`）重新应答，最长 20 s（参照现有 unpair 后的等待循环），
   然后 `streams.host(ssh, cert).serverInfo().paired` 必须为 true；超时或 false → `ManualPinNeeded(NOT_CONFIRMED)`
   （记录已写入也照此返回，PIN 兜底流程本身会先 unpair）。证书不符照旧抛 `streamCertificateFailure`。
7. `hostDao.pinPairedStreamCertificate(hostId, cert, expectedRevision, row.streamCertificateSha256, now) == 1`，否则抛
   `"Host identity or certificate trust changed during pairing"`。返回 `Paired(cert)`。

不需要先调用 unpair：helper 的 pair 已按证书替换旧记录（顺带避开 2026.914 重复记录 bug）。
`startStreamPairing`（手动 PIN）保持现状不删。

单元测试（`RemoteSunshineUnpairTest.kt` 风格，或新建 `RemoteSunshinePairTest.kt`）：stdin 内容与命令行不含证书、
严格解析成功形状、`client_sha256` 不符抛错、各 detail 到 `ManualPinNeeded` 原因的映射、其余失败抛出。
仓库层流程若现有测试设施能覆盖就补 ActiveStream / CERTIFICATE_UNAVAILABLE 分支；覆盖不了在说明里写明。

## 验收

- `./gradlew :app:testDebugUnitTest --tests '*RemoteSunshine*' --tests '*RemoteStream*'` 通过
- `python3 -I remote-helper/tests/test_sunshine_helper.py`（及该目录其他 helper 测试、`check_shellcheck.py`）通过
- `./gradlew :app:assembleRemoteHelper :app:assembleDebug` 通过
- 交付说明写进 `docs/remote-screen/SUNSHINE_DIRECT_PAIR_NOTES.md`：改了什么、跑了哪些测试及结果、没跑成的项。
  真机验证（Windows 2026.914、CachyOS 2026.1008、Mac）由 Claude 和用户做。
