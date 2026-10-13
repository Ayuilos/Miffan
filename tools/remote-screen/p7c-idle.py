#!/usr/bin/env python3
"""Read-only connected-idle test: leave SSH stdin open, expect bridge EOF within 135s."""
from importlib.util import spec_from_file_location, module_from_spec
from pathlib import Path
import time

spec = spec_from_file_location('p7c_mcp', Path(__file__).with_name('p7c-mcp.py'))
module = module_from_spec(spec); spec.loader.exec_module(module)
client = module.Mcp()
try:
    assert client.rpc('tools/list')['tools']
    start = time.monotonic()
    print('Waiting for connected bridge to idle out (stdin deliberately open)', flush=True)
    assert client.selector.select(135), 'Bridge did not exit while idle'
    assert client.process.stdout.readline() == b'', 'Unexpected unsolicited message while idle'
    seconds = round(time.monotonic() - start, 2)
    (module.OUT/'idle.txt').write_text(f'Connected bridge EOF after {seconds}s; stdin left open.\n')
    print(f'PASS connected idle EOF after {seconds}s', flush=True)
finally:
    client.close()
