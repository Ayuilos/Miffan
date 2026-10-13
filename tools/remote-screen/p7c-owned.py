#!/usr/bin/env python3
"""Open only a newly created P7c file, then identify its exact title without reading other windows.
Notify Claude about possible visible launch first. No clipboard or foreground delivery here.
"""
import argparse
import json
from pathlib import Path
import sys
import time
import uuid
from importlib.util import spec_from_file_location, module_from_spec

SPEC = spec_from_file_location('mcp_test', Path(__file__).with_name('p7c-mcp.py'))
module = module_from_spec(SPEC); SPEC.loader.exec_module(module)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows-helper-tests'))
from remote import run


def structured(result):
    if result.get('structuredContent'):
        return result['structuredContent']
    for content in result.get('content', []):
        if content.get('type') == 'text':
            try:
                return json.loads(content['text'])
            except json.JSONDecodeError:
                pass
    raise RuntimeError('No structured result')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--foreground-notified', action='store_true')
    args = parser.parse_args()
    if not args.foreground_notified:
        parser.error('Notify Claude with send-text then send-keys enter before possible visible launch')
    marker = 'p7c-owned-' + uuid.uuid4().hex[:12]
    setup = run(r"""
$root=Join-Path $env:LOCALAPPDATA 'Miffan\p7c-tests';$null=New-Item -ItemType Directory -Path $root -Force
$path=Join-Path $root 'MARKER.txt'
$file=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try{$data=(New-Object Text.UTF8Encoding($false)).GetBytes('P7c owned fixture');$file.Write($data,0,$data.Length)}finally{$file.Dispose()}
[Console]::Out.WriteLine($path)
""".replace('MARKER', marker))
    assert setup['exit_code'] == 0 and not setup['stderr'], 'Fixture creation failed'
    own_path = setup['stdout'].strip()
    client = module.Mcp()
    try:
        result = client.rpc('tools/call', {'name':'launch_app','arguments':{'path':r'C:\Windows\notepad.exe','additional_arguments':[own_path]}})
        # A launch timeout is an unknown outcome; enumerate, never replay launch.
        for _ in range(15):
            found = structured(client.rpc('tools/call', {'name':'list_windows','arguments':{}}))
            windows = found.get('windows', found if isinstance(found, list) else [])
            own = [w for w in windows if marker in w.get('title', w.get('window_title',''))]
            if len(own) == 1:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Owned window was not uniquely identified; no content read')
        window = own[0]
        record = {'marker':marker, 'pid':window['pid'], 'windowId':window.get('window_id',window.get('id')), 'file':own_path}
        assert record['windowId'] is not None
        module.OUT.mkdir(parents=True,exist_ok=True)
        (module.OUT/'owned.json').write_text(json.dumps(record,ensure_ascii=False,indent=2))
        state = client.rpc('tools/call', {'name':'get_window_state','arguments':{'pid':record['pid'],'window_id':record['windowId'],'include_screenshot':False}})
        assert not state.get('isError'), 'Owned window unavailable'
        data = structured(state)
        assert 'P7c owned fixture' in json.dumps(data,ensure_ascii=False)
        (module.OUT/'owned-state.json').write_text(json.dumps(data,ensure_ascii=False,indent=2))
        print(json.dumps(record,ensure_ascii=False))
    finally:
        client.close()

if __name__ == '__main__':
    main()
