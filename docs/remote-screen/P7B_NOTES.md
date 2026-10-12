# P7b：Windows helper 交付笔记

日期：2026-10-12。分支：`feature/p7b-windows-helper`。
依据：`P7B_SPEC.md`、`P7_WINDOWS_PLAN.md`、`research/WINDOWS_SPIKE.md`
及现有 `miffan.sh`、`RemoteScreenRepository.kt` / `RemoteStreamModels.kt` 的解析契约。
本次只交付脚本和测试；未修改 App、未接入 P7a，也没有实现 cua 命令。

## 调用和输出契约

安装文件为 `app/src/main/assets/remote/miffan.ps1`，**UTF-8 with BOM**，支持
Windows PowerShell 5.1。P7b 第二部分由 App 经 P7a 安装到：

```powershell
& "$env:LOCALAPPDATA\Miffan\bin\miffan.ps1" <命令> <参数>
```

每次调用 stdout 为一行压缩 JSON，输出 UTF-8 无 BOM。`version` 保留 POSIX 的整数
契约，输出 `1`（也是合法 JSON）。其他输出仅由字典、数组、字符串、数字、布尔值、
null 构成，不序列化 CIM/ETS 对象。成功退出 0；失败退出 1 并输出
`success=false/detail/error_type/backup/cleanup_task/cleanup_directory`。
拒绝理由仅允许脚本自身的固定字符串，不输出原生异常消息、stdin 或配置内容。

基础命令参数只用于选择操作；密码、PIN 和剪贴板文本不能放进 argv。
stdin 从已有 UTF-8 reader 读取，helper 不重置已设置为 UTF-8 的 InputEncoding，
避免丢弃执行层预读的缓冲数据。P7a 需设置编码、关闭进度、正确传播脚本退出码。

| 命令 | stdin | 输出与行为 |
| --- | --- | --- |
| `version` | 无 | 整数版本 `1` |
| `probe` | 无 | helper/os/arch/session/cua/vnc/rdp/clipboard，加 Windows 版本、build、GPU；显示器为 `displays=null/display_state=unknown`。WTS API 获取控制台用户、SID、会话、locked/unlocked/unknown；不创建任务 |
| `probe display` | 无 | success/detail/session_id/display_count/displays；每屏含 name/primary/x/y/width/height/dpi_x/dpi_y/scale_percent。普通权限交互任务只读枚举，不读取窗口或桌面内容，用完即删 |
| `sunshine probe` | 无 | 兼容 Sunshine 字段，附服务、进程会话、TCP 监听、配置/流状态是否确定。版本读文件 ProductVersion，绝不运行 sunshine.exe；证书解析 X509 后对 RawData DER 算 SHA-256 |
| `sunshine start` | 无 | success/detail/changed/backup；服务已运行则无变化；未运行时仅启动已确认的 SunshineService |
| `sunshine enforce-encryption` | 无 | success/detail/changed/backup；2/2 为无变化。活动或未知流状态拒绝；确认服务/默认配置/无命令行 override 后备份，改两项为 2，原子替换，再检查流状态后重启 SunshineService |
| `sunshine firewall check` | 无 | success/detail/firewall_model_ok/udp_rule_present/rules/profiles/unsafe_rules/unmanageable_rules/suggested_changes；只读，按 sunshine.exe 的完整路径筛选实际生效的入站规则，附 disabled 规则供审阅 |
| `sunshine firewall apply` | 下述完整 JSON 计划 | 内部从 ActiveStore 重新计算；空计划或与调用方批准的计划不一致，在备份前拒绝且零写入。非空匹配计划先备份，再核对快照、执行、重新 check；返回 changed/backup/applied_changes 和检查字段 |
| `clip` | 原始 UTF-8 文本，保留末尾换行 | 普通权限用户控制台会话任务写入并立即读回逐字比较；返回 success/detail/verified/session_id，绝不返回文本或摘要；SSH 用户必须与控制台用户 SID 相同且确认未锁屏。NUL 文本拒绝；空文本清空剪贴板 |

`probe display` 是用户在本轮确认的按需独立命令；基础 `probe` 保持轻量。
显示器 worker 确认实际进程 Session/SID 与控制台一致，以 per-monitor DPI awareness
读取 DPI；无法确认 DPI awareness/API 时 DPI 和缩放输出 null，不把虚拟的 96 DPI
当成用户缩放。控制台会话取 WTS 当前 ID，测试机为 Session 1，不硬编码重登录后的 ID。

### 与 POSIX 字段对照

| 原字段 | Windows 对应 |
| --- | --- |
| probe: helper/os/arch | 同名，os=`windows` |
| probe: session.present/type/desktop | 同名，type=`console`、desktop=`windows`；扩展 id/user/domain/sid/lock_state |
| probe: cua.path/version/min/ok | 保留必需结构，path/version=null、min=0.34.0、ok=false；本阶段不探测/实现 cua，不能将此占位值视为“机器未安装 cua” |
| probe: vnc/rdp | 同名占位结构，server=null/running=false；Windows 屏幕只走 Sunshine |
| probe: clipboard | `interactive-task` |
| sunshine: installed/version/running | 同名；running 同时检查服务状态和 sunshine.exe 进程 |
| sunshine: lan_encryption_mode/wan_encryption_mode | 同名整数/未知 null；缺项使用 Sunshine 默认 0/1，不能验证真实配置时 null |
| sunshine: certificate_sha256 | 同名，DER SHA-256 的 64 位小写 hex；未知 null |
| sunshine: active_stream/candidates | 同名。UDP base+9/+10/+11 的任何 socket（含 pending/端口冲突）阻止重启；查询/配置未知时 active_stream=true，另标 stream_state_known=false。candidates 只输出 RFC1918 和 100.64/10 IPv4 |
| sunshine: display_asleep/screen_locked/permissions/permissions_from_log | 同名；不猜测显示器睡眠或 Windows 权限；screen_locked 从 WTS 已知状态映射，否则 null |
| sunshine start/enforce: success/detail | 同名；另有 changed/backup，App 现有 parser 可以忽略扩展字段 |
| clip: POSIX 成功无 stdout | Windows 返回单行 JSON；现有 App clipboard 方法只检查退出码，兼容；未来可检查 verified |

### 防火墙批准计划

用户在本轮明确升级了 apply 约束：**拒绝空计划**，不是幂等成功；要逐项核对调用方
批准的变更。stdin 顶层必须恰为 `disable_rules/allowed_sources/add_rule`。

```json
{"disable_rules":["<已审阅的 Sunshine 规则 ID>"],"allowed_sources":["192.168.31.0/24","100.64.0.0/10"],"add_rule":{"id":"Miffan-Sunshine-UDP-LAN","program":"C:\\Program Files\\Sunshine\\sunshine.exe","protocol":"UDP","ports":["47998-48000"],"sources":["192.168.31.0/24","100.64.0.0/10"],"replace":false}}
```

若已有等效完整 UDP 规则，`add_rule=null`；只有需要替换自有同名规则时 replace=true。
add_rule 的字段必须恰为示例六项。调用方需根据 check 的 rules 和用户选择来源构造
具体计划、向用户展示并批准后提交；suggested_changes 中需要新增规则的模板没有
默认来源，不授权 Any/LocalSubnet。仅接受明确 IPv4/IPv6 网段，拒绝 /0、地址范围、
主机地址及特殊关键字；Windows 点分掩码规范化后比较，顺序和重复不影响计划。

只禁用本安装路径的 Local 入站 Sunshine 规则，使用字面 ID 匹配后传 CIM 对象，
不把 ID 当 wildcard。仅创建/替换 `Miffan-Sunshine-UDP-LAN`，程序仍必须是同一个
sunshine.exe。即使同为 Miffan- 前缀，也不会操作 SSH 或其他程序规则；GPO 不可修改。
新增放行硬编码 UDP 47998–48000；TCP 或额外输入字段在解析阶段拒绝。

所有**检查**均枚举 ActiveStore 的规则、应用/端口/地址过滤器，按 InstanceID 联结。
不通过关联查询取得规则：本机 PS 5.1 的关联查询会返回 NotApplicable/其他存储的数据，
即使显式传 PolicyStore 也不可靠。EnforcementStatus 是数组，任一 Enforced 才算生效。
检查同时列出三个 profile 是否开启、默认入站是否 Block；范围是绑定 Sunshine 程序的
规则，不宣称审计整个机器所有 Any-program 规则或验证网络可达性。

**备份/执行**才有意使用 PersistentStore：仅保存或修改已批准的 Local 规则，不能修改
ActiveStore 中合成的 GPO 策略。备份导出完整本地规则和 application/port/address/
service/interface/interface_type/security 过滤器 CLIXML，另存 plan.json。
不做全局防火墙导入/恢复；部分失败会报告 backup，后续需定向审阅。

### 配置、任务和备份

配置改写保留其他项、注释、重复加密键、原有 CRLF/LF 和 UTF-8 BOM；每个加密键均
改为 2，缺项追加。拒绝配置文件/祖先目录 reparse point。备份前后核对原始字节，
替换前复查配置及活动流，使用同目录临时文件和 File.Replace 原子替换并保留 ACL。
PS 5.1 调用 File.Replace 的 null 参数使用 NullString.Value，避免被转换成空路径。
检查与服务重启并非 Sunshine 的事务锁；最后检查后瞬间新建流的竞态留给未来服务协作。

备份放 `%LOCALAPPDATA%\Miffan\backup\`，目录受保护，ACL 仅该用户继承到子文件。
返回备份路径，不输出完整配置。只有实际配置/规则变更才备份；空/不一致防火墙计划
在创建备份目录之前拒绝。

交互任务采用当前用户 SID、Interactive/Limited，绝不 Highest/System，不需要密码。
随机 `Miffan-display-<GUID>` / `Miffan-clip-<GUID>`，ACL 仅该用户的目录在创建时即
设置，不先写文本再收紧 ACL。任务参数只有脚本路径、目录、SID、会话、模式，文本
只在私有 input.txt。worker 确认实际 Session/SID、控制台仍一致，结果原子写入；父进程
超时会停止自有任务、注销、清理文件。任务结束/注销竞态用新鲜根目录任务对象、
有限重试处理；仍失败则输出精确 cleanup_task/cleanup_directory，不宣称已清理。

## 测试结果

`tools/windows-helper-tests/README.md` 说明运行方法。Windows PowerShell 5.1 AST
解析通过；23 组离线测试全部通过，Python 传输脚本编译检查通过。

覆盖：配置保留/重复/幂等、实际临时文件原子替换与私有备份、X509 DER 固定指纹、
IPv4/IPv6/点分掩码、规则风险与完整计划、来源/ID/程序/GPO 边界、JSON 数组/null/
布尔/中文、活动流未知时拒绝、服务无变化、空计划/计划不一致零写入、修改前备份/
快照竞态、模拟成功执行后复查、ActiveStore 与 PersistentStore 差异、模拟模块覆盖
同名函数、普通权限任务/ACL/读回/清理及任务结束/注销暂态失败。

修改防火墙的最终路径只以离线数据验证，测试替换内部 mutation 边界，**不会调用
真实修改规则的 adapter**。2026-10-12 用户更新限制后，没有再在测试机运行任何 apply。
最终测试机只读/无变化命令输出见下文；clip 已先向 Claude send-text 紧接 Enter 通知。

## 共享测试机上的意外规则重建（保留事件记录）

在约 18:17 CST（备份 UTC 时间 10:17:37）的早期幂等 smoke，关联查询回落到另一
策略存储，现有 UDP 规则被误判为未生效；New/Remove 同名防写函数又被 NetSecurity
自动导入覆盖。脚本先备份后，**实际重建了 Miffan-Sunshine-UDP-LAN**。这违反了最初
“测试机不实际修改防火墙”的要求，已当时向用户及 Claude 如实报告。

实际有效配置仍为同一程序、UDP 47998–48000、LAN 192.168.31.0/24 和
100.64.0.0/10，来源顺序改为 Tailscale、LAN；没有扩大放行范围。两条 Sunshine 宽泛
规则仍 Disabled，SSH 规则未变，Sunshine PID 13352/服务 Running 未变。
Claude 独立只读核查并提供 LAN 到 47984/47989/47990/48010/3389 均不可达的结果；
本 helper 没有自行进行网络可达性测试，不把 Claude 交付证据说成自己的实测。

本次备份**保留，不删除、不自动回滚**：

```text
%LOCALAPPDATA%\Miffan\backup\sunshine-firewall-20261012-101737-4854127-0e64bdc5d0ee4cc18bac0987b27c32ca\
```

目录内有原规则及全部过滤器的 CLIXML、旧快照和原计划 plan.json。
修复：ActiveStore 表枚举联结；内部重新计算并核对完整批准计划；空计划零写入拒绝；
真实修改调用模块限定名；模拟测试替换内部边界，不依赖同名 native 函数遮蔽。
新增要求覆盖的三项回归均通过。最终 smoke 已移除 apply，包含空变更也不再执行。

另一次只读 display 测试遇到任务注销暂态失败，明确报告了自身 GUID；确认其 Ready、
无 worker 进程、临时目录已删除后，使用精确 CIM 对象移除了该遗留任务。
注销修复及回归通过，display 重测成功；最终核查无 display/clip 一次性任务残留。

## App 接入（P7b 第二部分）

- 等 P7a 合并后再改 helper 安装/升级。保存 asset 的 BOM，按固定 LOCALAPPDATA/bin 路径
  原子安装；version 继续解析整数。不要通过 bash 或修改 OpenSSH 默认 shell 来调用。
- 基础 probe 不做任务。显示器详情由明确按需的 probe display 调用；JSON 扩展字段
  可以忽略。当前 cua/vnc/rdp 占位结构只为旧 parser 兼容，不代表 Windows 支持那些协议。
- Sunshine probe 约 2.2 秒、基础 probe 约 1 秒；display 约 3.4 秒，防火墙检查约 4.2 秒。
  都包含本轮 SSH 启动耗时，不是普遍性能保证。建议 task 命令至少 30 秒预算；不要
  沿用 clipboard 的 10 秒预算判断所有慢机必然成功。
- Windows firewall 需要独立 UI：展示规则及具体完整变更，让用户批准后传三字段计划。
  没有变更就只 check；不能用空 apply 作心跳或“幂等成功”。计划不一致应重新读取、
  重新展示批准，不自动扩大来源、替换计划或重试写入。
- clip 的文本直接走 UTF-8 stdin；可读取 verified，成功须为用户会话读回一致。
  任务/SID/锁屏错误不能降级到 Session 0 Set-Clipboard。
- 本轮只覆盖 Windows 11、单屏 96 DPI、管理员 SSH 登录同一控制台用户。普通账号、
  多 DPI/多屏、锁屏切换、服务原本停止、真实非空 firewall apply 的 Windows VM 验收
  留给后续阶段；本轮有对应模拟逻辑测试，不将其说成真机全场景通过。

## 最终实测输出（不含秘密）

### `version`

退出码 0，SSH 端耗时 0.682 秒，stderr 为空。

```json
1
```

### `probe`

退出码 0，SSH 端耗时 0.986 秒，stderr 为空。

```json
{"os_version":"Microsoft Windows 11 专业版","gpu":["NVIDIA GeForce RTX 3060"],"arch":"AMD64","display_state":"unknown","displays":null,"vnc":{"server":null,"running":false},"clipboard":"interactive-task","os_build":"26200","helper":1,"os_version_number":"10.0.26200","rdp":{"server":null,"running":false},"cua":{"ok":false,"path":null,"min":"0.34.0","version":null},"os":"windows","session":{"present":true,"id":1,"lock_state":"unlocked","user":"Ekk","type":"console","desktop":"windows","sid":"S-1-5-21-3363479406-2473750968-85920261-1001","domain":"DESKTOP-QHRKJUQ"}}
```

### `sunshine probe`

退出码 0，SSH 端耗时 2.204 秒，stderr 为空。

```json
{"configuration_known":true,"permissions":null,"version":"2026.914.233613","listeners_known":true,"lan_encryption_mode":2,"permissions_from_log":null,"wan_encryption_mode":2,"installed":true,"display_asleep":null,"stream_state_known":true,"certificate_sha256":"8edb025280591442b733c675de6dafb1b011b0f69abddd1f18ed178b14bf512d","service_state":"Running","running":true,"active_stream":false,"screen_locked":false,"console_session_id":1,"daemon_session_ids":[1],"candidates":["100.64.0.8","192.168.31.61"],"listeners":[{"port":48010,"pid":13352,"address":"0.0.0.0"},{"port":47990,"pid":13352,"address":"0.0.0.0"},{"port":47989,"pid":13352,"address":"0.0.0.0"},{"port":47984,"pid":13352,"address":"0.0.0.0"}]}
```

### `sunshine start`

退出码 0，SSH 端耗时 1.732 秒，stderr 为空。

```json
{"detail":"Sunshine is already running","backup":null,"changed":false,"success":true}
```

### `sunshine enforce-encryption`

退出码 0，SSH 端耗时 2.318 秒，stderr 为空。

```json
{"detail":"Encryption is already enforced","backup":null,"changed":false,"success":true}
```

### `sunshine firewall check`

退出码 0，SSH 端耗时 4.202 秒，stderr 为空。

```json
{"unsafe_rules":[],"udp_rule_present":true,"rules":[{"id":"{E64063E9-6A00-4427-BC96-58CCC37AE7AF}","program":"C:\\Program Files\\Sunshine\\sunshine.exe","sources":["Any"],"profile":"Any","policy_source":"Local","enforcement":["Disabled"],"direction":"Inbound","ports":["Any"],"protocol":"TCP","enabled":false,"status":"Inactive","effective":false,"display_name":"Sunshine","action":"Allow"},{"id":"{6A386F92-DB45-4BA6-BEDB-38569C64A5E7}","program":"C:\\Program Files\\Sunshine\\sunshine.exe","sources":["Any"],"profile":"Any","policy_source":"Local","enforcement":["Disabled"],"direction":"Inbound","ports":["Any"],"protocol":"UDP","enabled":false,"status":"Inactive","effective":false,"display_name":"Sunshine","action":"Allow"},{"id":"Miffan-Sunshine-UDP-LAN","program":"C:\\Program Files\\Sunshine\\sunshine.exe","sources":["100.64.0.0/255.192.0.0","192.168.31.0/255.255.255.0"],"profile":"Any","policy_source":"Local","enforcement":["ProfileInactive","Enforced","Enforced","Enforced"],"direction":"Inbound","ports":["47998-48000"],"protocol":"UDP","enabled":true,"status":"OK","effective":true,"display_name":"Miffan Sunshine UDP LAN","action":"Allow"}],"unmanageable_rules":[],"success":true,"suggested_changes":{"disable_rules":[],"add_rule":null},"profiles":[{"name":"Domain","default_inbound":"Block","enabled":true},{"name":"Private","default_inbound":"Block","enabled":true},{"name":"Public","default_inbound":"Block","enabled":true}],"detail":"Firewall inspected","firewall_model_ok":true}
```

### `probe display`

退出码 0，SSH 端耗时 3.439 秒，stderr 为空。

```json
{"detail":"Desktop displays inspected","display_count":1,"session_id":1,"success":true,"displays":[{"x":0,"height":1440,"dpi_x":96,"primary":true,"scale_percent":100,"y":0,"dpi_y":96,"name":"\\\\.\\DISPLAY1","width":2560}]}
```

### `clip`

先通知 Claude 后运行一次；**覆盖用户当前剪贴板**。输入为中英文、emoji、LF/CRLF、tab、单双引号、美元符号和反引号测试文本，未读取/记录旧内容，不恢复以免覆盖用户后续变化。退出码 0，stderr 为空，Session 1 内逐字读回一致。

```json
{"detail":"Clipboard written and read back","verified":true,"session_id":1,"success":true}
```

最终只读核查：SunshineService Running、sunshine.exe PID 13352，P7b display/clip
一次性任务列表为空；两条宽泛规则 Disabled，UDP 规则来源与端口仍为目标范围，
SSH 规则仍为 LAN TCP 22。测试脚本/公开夹具保留在
`%LOCALAPPDATA%\Miffan\p7b-tests\` 供复现，没有安装到 App 的 bin 路径。

API 依据：会话锁屏状态使用微软的
[WTSINFOEX_LEVEL1](https://learn.microsoft.com/en-us/windows/win32/api/wtsapi32/ns-wtsapi32-wtsinfoex_level1_w)；
DPI awareness 的要求见
[GetDpiForMonitor](https://learn.microsoft.com/en-us/windows/win32/api/shellscalingapi/nf-shellscalingapi-getdpiformonitor)；
ActiveStore enforcement 数组定义见
[MSFT_NetFirewallRule](https://learn.microsoft.com/en-us/windows/win32/fwp/wmi/wfascimprov/msft-netfirewallrule)，
任务的 Interactive/Limited 选项见
[New-ScheduledTaskPrincipal](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtaskprincipal)。
