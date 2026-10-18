# Remote helper

Miffan 在 Linux/macOS 与 Windows 上通过 SSH 调用的 helper 源码。此目录不是 Gradle
模块，不单独发布：helper 与 App 一起构建、一起发版。运行时协议见 [PROTOCOL.md](PROTOCOL.md)。

## 构建

```sh
./gradlew :app:assembleRemoteHelper
./gradlew assembleDebug
```

`build-logic` 的可缓存 `AssembleRemoteHelper` task 只依赖 JVM。它将 `src/miffan.sh`
和 `src/windows-setup.ps1` 原样复制，把 `src/windows/miffan.ps1` 模板中的整行
`#include Name.cs` 替换为同目录 C# 文件的原始字节，输出至
`app/build/generated/remoteHelper/remote/`。模板中的 UTF-8 BOM 保留；包含文件末尾 LF
也是内容的一部分。不要运行源码模板，Windows 使用生成后的文件。

Android variant assets API 自动引入生成目录，APK 的资源路径保持不变。生成目录不入库。
2026-10 的迁移保持三个输出与 `6f4eb1777` 逐字节一致，不升级 helper 版本。

## 测试

```sh
python3 remote-helper/tests/test_helper_safety.py
python3 remote-helper/tests/test_sunshine_helper.py
python3 remote-helper/tests/check_shellcheck.py /path/to/shellcheck-v0.10.0/shellcheck
./gradlew test
./gradlew lint
```

Python 测试只使用临时目录及本地命令 stub，不访问远程主机。需要 Python 3、OpenSSL、
本地 Unix socket 与基本 Unix 工具；安全测试沿用原有 `timeout`/`script` stub。`fixtures/sh/` 来自实际运行
helper，按 JSON 值比较；随机 RDP 端口、用户名、证书 pin、probe 中的临时 cua/socket 路径在
验证类型/范围后归一化。刷新时先审查 helper 及解析协议，再运行：

```sh
UPDATE_FIXTURES=1 python3 remote-helper/tests/test_helper_safety.py
UPDATE_FIXTURES=1 python3 remote-helper/tests/test_sunshine_helper.py
```

`fixtures/ps1/` 根据当前代码与 Windows 离线测试手工整理。
[Windows 测试说明](tests/windows/README.md) 描述 PS 5.1 的运行方式；`Test-Helper.ps1`
使用 mocked OS/service/firewall/task 边界验证共享样例。`stage.py` 先构建，再上传生成
helper、测试和公开 fixtures；它会连接已配置的 Windows 主机，只在明确授权测试时运行。
本次迁移没有运行 stage、smoke 或 Windows 真机测试。一次性 spikes 保留在原来的 tools 目录。

App JVM 测试通过 Gradle system properties 获得生成目录、fixtures 和版本锁；这些文件
也声明为测试输入，因此修改 golden 或锁文件会重跑测试。`RemoteHelperContractTest` 遍历
全部共享 JSON，选择真实解析函数或与内联消费者相同的 decoder，并断言关键字段；
Windows 剪贴板流样例交给实际 `WindowsClipboardChannel.write`。

CI 下载固定 ShellCheck **v0.10.0** Linux x86_64 归档并校验 SHA-256
`6c881ab0698e4e6ea235245f22832860544f17ba386442fe7e9d629f8cbedf87`。
以 `-S warning` 检查，严格比对迁移前已有的三条 SC2155 诊断（行 84、97、334）；
任何新增、移动或变化的 warning/error 都失败。此迁移不修改 helper 消除既有告警。
后续项：为 Windows CI 引入 PowerShell 静态检查，并在 Windows PS 5.1 上运行离线测试。

## 版本规则

`miffan.sh` 使用 `MIFFAN_HELPER_VERSION`；Windows 使用 `$script:HelperVersion`。
`ensureInstalled` 仅比较版本数字，内容变动时必须升对应版本，再在 `versions.lock` 末尾
**新增** `<文件> <版本> <生成后 sha256>`。旧行只能保留，不能修改或删除；评审看到
旧行被改就是忘了升版本。新版本应递增。`windows-setup.ps1` 无版本号，不入锁。

`RemoteHelperVersionTest` 在 `./gradlew test` 中校验生成文件的当前版本与 SHA-256，
并拒绝重复锁项及当前版本回退。更新锁时必须对展开后的文件计算摘要：

```sh
./gradlew :app:assembleRemoteHelper
shasum -a 256 app/build/generated/remoteHelper/remote/miffan.sh app/build/generated/remoteHelper/remote/miffan.ps1
```

已有历史锁行的不可变性由 Git 评审保证，测试无法从工作区文件本身识别被篡改的历史。
