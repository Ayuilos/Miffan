#!/usr/bin/env python3
"""Writes ONE test clipboard value in the console session, replacing the user's clipboard.
Not run by stage.py/smoke.py. Notifies Claude through herdr before every invocation.
"""
import json
import os
from pathlib import Path
import subprocess
from remote import run


def main():
    if os.environ.get('HERDR_ENV') != '1':
        raise SystemExit('Run inside herdr so Claude can be notified before changing the clipboard.')
    herdr = str(Path.home() / '.local/bin/herdr')
    subprocess.run([herdr, 'pane', 'send-text', 'wJ:p1', 'P7b 将运行一次 clip 实测，会覆盖用户当前剪贴板：中英文、emoji、单双引号、换行/tab 文本；以普通权限 Session 1 任务写入并读回比较，用完删除任务和临时文件，不打开应用/不发粘贴键。'], check=True)
    subprocess.run([herdr, 'pane', 'send-keys', 'wJ:p1', 'enter'], check=True)
    text = 'P7b 中文剪贴板 🥣\nEnglish\ttest \' " $value `tick`\r\n尾行\n'
    result = run("& (Join-Path $env:LOCALAPPDATA 'Miffan\\p7b-tests\\miffan.ps1') clip", text)
    print(json.dumps(result, ensure_ascii=False))
    if result['exit_code'] or result['stderr'] or len(result['stdout'].splitlines()) != 1:
        raise SystemExit(1)
    decoded = json.loads(result['stdout'])
    assert decoded['success'] and decoded['verified'] and decoded['session_id'] == 1

if __name__ == '__main__':
    main()
