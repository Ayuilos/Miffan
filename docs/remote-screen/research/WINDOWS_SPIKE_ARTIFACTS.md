# Windows spike 临时证据清单

2026-10-12 收尾时只读清点。未删除任何证据；以下目录/文件仅供本机 Claude 审阅，不打包公开。
测试凭据/身份在主 checkout `.deps/windows-spike/`，不在本清单中；不得复制到审阅包或 git。

“审阅前保留”在 Claude 核对结论和回滚前保留；“复现可选”可从提交的脚本/官方版本重建；辅助诊断审阅后可删除。
目录行代表整目录。历史文档的两份原始响应已删除，恢复窗口标题在相关目录已脱敏。
清单 JSON 另存于 `/private/tmp/windows-spike-artifact-inventory.json`，包含核心单文件 SHA-256。

| 路径（均在 /private/tmp） | 建议 | 文件数 | 大小 |
| --- | --- | ---: | ---: |
| `windows-spike-a5-cleanup-observer.txt` | 审阅前保留 | 1 | 0.000 MiB |
| `windows-spike-a5-cursor-calibration.txt` | 审阅前保留 | 1 | 0.001 MiB |
| `windows-spike-a5-final/` | 审阅前保留 | 18 | 1.154 MiB |
| `windows-spike-a5-foreground-before.txt` | 审阅前保留 | 1 | 0.000 MiB |
| `windows-spike-a5-media-final.txt` | 审阅前保留 | 1 | 0.008 MiB |
| `windows-spike-a5-native-input.json` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-a5-native-input.txt` | 审阅前保留 | 1 | 0.003 MiB |
| `windows-spike-a5-native-input2.json` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-a5-native-input2.txt` | 审阅前保留 | 1 | 0.003 MiB |
| `windows-spike-a5-native-input3.json` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-a5-native-input3.txt` | 审阅前保留 | 1 | 0.004 MiB |
| `windows-spike-a5-observer-refresh.txt` | 审阅前保留 | 1 | 0.000 MiB |
| `windows-spike-a5-observer-start.txt` | 审阅前保留 | 1 | 0.000 MiB |
| `windows-spike-a5-observer-task-final.xml` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-a5-verification.json` | 审阅前保留 | 1 | 0.000 MiB |
| `windows-spike-add-firewall.bat` | 复现可选 | 1 | 0.000 MiB |
| `windows-spike-b-cua-task-highest-bare.xml` | 审阅前保留 | 1 | 0.003 MiB |
| `windows-spike-b-cua-task-limited.xml` | 审阅前保留 | 1 | 0.003 MiB |
| `windows-spike-b-highest-diagnostics.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.000 MiB |
| `windows-spike-b-highest-direct/` | 辅助诊断，可于审阅后删除 | 1 | 0.000 MiB |
| `windows-spike-b-highest-direct-logged/` | 审阅前保留 | 7 | 0.008 MiB |
| `windows-spike-b-highest-events.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.002 MiB |
| `windows-spike-b-highest-logs.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.000 MiB |
| `windows-spike-b-highest-task.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.000 MiB |
| `windows-spike-b-highest-token-logged.txt` | 审阅前保留 | 1 | 0.001 MiB |
| `windows-spike-b-highest-token.txt` | 审阅前保留 | 1 | 0.000 MiB |
| `windows-spike-b-medium-bridge/` | 审阅前保留 | 9 | 0.068 MiB |
| `windows-spike-b-medium-token.txt` | 审阅前保留 | 1 | 0.001 MiB |
| `windows-spike-b-resume-baseline.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.000 MiB |
| `windows-spike-b3-isolated/` | 审阅前保留 | 41 | 0.857 MiB |
| `windows-spike-b3-window-metrics.json` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-baseline.json` | 审阅前保留 | 1 | 0.001 MiB |
| `windows-spike-command-probe-resume.jsonl` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-command-probe.jsonl` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-confighttp.cpp` | 复现可选 | 1 | 0.081 MiB |
| `windows-spike-cua/` | 辅助诊断，可于审阅后删除 | 1 | 0.000 MiB |
| `windows-spike-cua-bridge/` | 审阅前保留 | 7 | 0.248 MiB |
| `windows-spike-cua-daemon.rs` | 复现可选 | 1 | 0.013 MiB |
| `windows-spike-cua-diagnostics.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.001 MiB |
| `windows-spike-cua-install-output.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.008 MiB |
| `windows-spike-cua-install.ps1` | 复现可选 | 1 | 0.088 MiB |
| `windows-spike-cua-main.rs` | 复现可选 | 1 | 0.046 MiB |
| `windows-spike-cua-proxy.rs` | 复现可选 | 1 | 0.059 MiB |
| `windows-spike-cua-release.json` | 复现可选 | 1 | 0.119 MiB |
| `windows-spike-cua-serve.rs` | 复现可选 | 1 | 0.126 MiB |
| `windows-spike-cua-status-registered.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.029 MiB |
| `windows-spike-cua-status-stderr.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.000 MiB |
| `windows-spike-cua-status.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.002 MiB |
| `windows-spike-cua-task.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.027 MiB |
| `windows-spike-cua-tree.json` | 复现可选 | 1 | 2.874 MiB |
| `windows-spike-cua-windows-lib.rs` | 复现可选 | 1 | 0.005 MiB |
| `windows-spike-emulator/` | 审阅前保留 | 4 | 0.017 MiB |
| `windows-spike-emulator.txt` | 审阅前保留 | 1 | 0.007 MiB |
| `windows-spike-encryption.txt` | 审阅前保留 | 1 | 0.000 MiB |
| `windows-spike-existing-stream-test.apk` | 复现可选 | 1 | 14.485 MiB |
| `windows-spike-final-status.json` | 审阅前保留 | 1 | 0.008 MiB |
| `windows-spike-hevc.json` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-inspect-window.py` | 辅助诊断，可于审阅后删除 | 1 | 0.003 MiB |
| `windows-spike-install-firewall.json` | 审阅前保留 | 1 | 0.001 MiB |
| `windows-spike-installer.wxs` | 复现可选 | 1 | 0.006 MiB |
| `windows-spike-inventory-before.json` | 辅助诊断，可于审阅后删除 | 1 | 0.010 MiB |
| `windows-spike-local-cleanup.json` | 审阅前保留 | 1 | 0.000 MiB |
| `windows-spike-notepad-probe/` | 复现可选 | 3 | 0.496 MiB |
| `windows-spike-openssh-doc.html` | 复现可选 | 1 | 0.058 MiB |
| `windows-spike-probe-build.txt` | 辅助诊断，可于审阅后删除 | 1 | 0.019 MiB |
| `windows-spike-probe-deps/` | 复现可选 | 97 | 0.634 MiB |
| `windows-spike-redaction-check/` | 辅助诊断，可于审阅后删除 | 1 | 0.000 MiB |
| `windows-spike-release.json` | 审阅前保留 | 1 | 0.120 MiB |
| `windows-spike-resume-status.txt` | 审阅前保留 | 1 | 0.008 MiB |
| `windows-spike-setup.ps1` | 复现可选 | 1 | 0.016 MiB |
| `windows-spike-sunshine-misc.cpp` | 复现可选 | 1 | 0.069 MiB |
| `windows-spike-sunshine-proxy.msi` | 复现可选 | 1 | 32.148 MiB |
| `windows-spike-sunshine-src/` | 复现可选 | 568 | 31.483 MiB |
| `windows-spike-sunshinesvc.cpp` | 复现可选 | 1 | 0.013 MiB |
| `windows-spike-tool-schema-summary.json` | 审阅前保留 | 1 | 0.061 MiB |
| `windows-spike-tree.json` | 复现可选 | 1 | 0.187 MiB |
| `windows-spike-ts-h264.json` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-ts-h264.txt` | 审阅前保留 | 1 | 0.003 MiB |
| `windows-spike-ts-input.json` | 审阅前保留 | 1 | 0.002 MiB |
| `windows-spike-ts-input.txt` | 审阅前保留 | 1 | 0.003 MiB |
| `windows-spike-ts-media.txt` | 审阅前保留 | 1 | 0.007 MiB |
| `windows-spike-ts-status.txt` | 审阅前保留 | 1 | 0.006 MiB |
| `windows-spike-ts-typing-overlap.json` | 审阅前保留 | 1 | 0.003 MiB |
| `windows-spike-ts-typing-overlap.txt` | 审阅前保留 | 1 | 0.004 MiB |
| `windows-spike-ts-typing.json` | 审阅前保留 | 1 | 0.003 MiB |
| `windows-spike-ts-typing.txt` | 审阅前保留 | 1 | 0.004 MiB |
| `windows-spike-window-inspection/` | 审阅前保留 | 2 | 0.002 MiB |

共 87 项、833 个普通文件、约 85.74 MiB（不跟随 symlink）。

重点顺序：final-status → a5-foreground/cursor-calibration → a5-native-input3 → a5-verification → a5-final/result-5、image-5-2、result-12–15 → B 权限对照/XML → ts-h264/typing-overlap/media → C command-probe。
a5-native-input/input2 保留为拒绝点击的对照：各只发送了绝对鼠标移动，未点击/发键；不能只看 input_test.sent=false 就声称无任何鼠标事件。
windows-spike-cua-bridge/result-3.json 为完整 schema；旧 CP936 损坏响应只是编码失败证据，不能重新当作有效 JSON。
Windows `%TEMP%\miffan-windows-spike` 的备份与文件另见主笔记。当前 cua-driver-serve action 依赖其中 cua-logged-daemon.ps1，恢复任务或迁移包装前不能删除整个目录。
