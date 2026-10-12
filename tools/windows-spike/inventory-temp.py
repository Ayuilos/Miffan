#!/usr/bin/env python3
"""Read-only inventory of spike evidence. Lists metadata; never deletes artifacts."""
import argparse,hashlib,json,os
from pathlib import Path

parser=argparse.ArgumentParser();parser.add_argument('--markdown',type=Path,required=True);parser.add_argument('--json',type=Path,required=True)
args=parser.parse_args();os.umask(0o077)
CORE={
 'windows-spike-baseline.json','windows-spike-install-firewall.json','windows-spike-release.json',
 'windows-spike-encryption.txt','windows-spike-resume-status.txt','windows-spike-ts-status.txt',
 'windows-spike-final-status.json','windows-spike-local-cleanup.json','windows-spike-command-probe.jsonl',
 'windows-spike-command-probe-resume.jsonl','windows-spike-b-cua-task-limited.xml',
 'windows-spike-b-cua-task-highest-bare.xml','windows-spike-b-medium-token.txt',
 'windows-spike-b-highest-token.txt','windows-spike-b-highest-token-logged.txt',
 'windows-spike-b-medium-bridge','windows-spike-b-highest-direct-logged','windows-spike-b3-isolated',
 'windows-spike-b3-window-metrics.json','windows-spike-cua-bridge','windows-spike-tool-schema-summary.json',
 'windows-spike-emulator','windows-spike-emulator.txt','windows-spike-hevc.json','windows-spike-window-inspection',
 'windows-spike-ts-media.txt','windows-spike-ts-h264.json','windows-spike-ts-h264.txt',
 'windows-spike-ts-typing-overlap.json','windows-spike-ts-typing-overlap.txt',
 'windows-spike-ts-typing.json','windows-spike-ts-typing.txt','windows-spike-ts-input.json','windows-spike-ts-input.txt'
}
items=[]
for p in sorted(Path('/private/tmp').glob('windows-spike*')):
 if p in (args.markdown,args.json):continue
 if p.is_symlink():items.append({'path':str(p),'category':'复现可选','kind':'symlink','files':0,'bytes':0});continue
 files=[f for f in p.rglob('*') if f.is_file() and not f.is_symlink()] if p.is_dir() else [p]
 name=p.name
 if name in CORE or name.startswith('windows-spike-a5-'):
  category='审阅前保留'
 elif name in ('windows-spike-notepad-probe','windows-spike-probe-deps','windows-spike-sunshine-src','windows-spike-sunshine-proxy.msi','windows-spike-existing-stream-test.apk') or p.suffix in ('.rs','.cpp','.wxs','.bat','.ps1','.html') or 'tree' in name or 'release' in name:
  category='复现可选'
 else:category='辅助诊断，可于审阅后删除'
 item={'path':str(p),'category':category,'kind':'directory' if p.is_dir() else 'file','files':len(files),'bytes':sum(f.stat().st_size for f in files)}
 if item['kind']=='file' and category=='审阅前保留':item['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
 items.append(item)
args.json.write_text(json.dumps(items,ensure_ascii=False,indent=2));args.json.chmod(0o600)
lines=['# Windows spike 临时证据清单','',
 '2026-10-12 收尾时只读清点。未删除任何证据；以下目录/文件仅供本机 Claude 审阅，不打包公开。',
 '测试凭据/身份在主 checkout `.deps/windows-spike/`，不在本清单中；不得复制到审阅包或 git。','',
 '“审阅前保留”在 Claude 核对结论和回滚前保留；“复现可选”可从提交的脚本/官方版本重建；辅助诊断审阅后可删除。',
 '目录行代表整目录。历史文档的两份原始响应已删除，恢复窗口标题在相关目录已脱敏。',
 '清单 JSON 另存于 `/private/tmp/windows-spike-artifact-inventory.json`，包含核心单文件 SHA-256。','',
 '| 路径（均在 /private/tmp） | 建议 | 文件数 | 大小 |','| --- | --- | ---: | ---: |']
for i in items:
 lines.append('| `'+Path(i['path']).name+('/' if i['kind']=='directory' else '')+'` | '+i['category']+' | '+str(i['files'])+' | '+f"{i['bytes']/1048576:.3f} MiB"+' |')
lines.extend(['',f"共 {len(items)} 项、{sum(i['files'] for i in items)} 个普通文件、约 {sum(i['bytes'] for i in items)/1048576:.2f} MiB（不跟随 symlink）。",'',
 '重点顺序：final-status → a5-foreground/cursor-calibration → a5-native-input3 → a5-verification → a5-final/result-5、image-5-2、result-12–15 → B 权限对照/XML → ts-h264/typing-overlap/media → C command-probe。',
 'a5-native-input/input2 保留为拒绝点击的对照：各只发送了绝对鼠标移动，未点击/发键；不能只看 input_test.sent=false 就声称无任何鼠标事件。',
 'windows-spike-cua-bridge/result-3.json 为完整 schema；旧 CP936 损坏响应只是编码失败证据，不能重新当作有效 JSON。',
 'Windows `%TEMP%\\miffan-windows-spike` 的备份与文件另见主笔记。当前 cua-driver-serve action 依赖其中 cua-logged-daemon.ps1，恢复任务或迁移包装前不能删除整个目录。'])
args.markdown.write_text('\n'.join(lines)+'\n');print(f'Inventoried {len(items)} items; no deletion. Markdown: {args.markdown}')
