#!/usr/bin/env python3
"""Extract verified sources into our build directory; never patch the shared cache."""
import hashlib, pathlib, shutil, subprocess, tarfile
root = pathlib.Path(__file__).resolve().parents[2]
out = root / 'stream/build/sources'
archives = [('common-c.tar.gz', 'be204cebb6687acd2d65dab548ea6d9c8d60bd277984ef49719bad5cbc0385db', out),
 ('common-c/enet.tar.gz', '6bb1a151e6d21e1756baeff5a95eaf85a5b6731aeda6d0bc255651192ba4a32d', out / 'enet'),
 ('common-c/nanors.tar.gz', '41edc0309b255b0eeb5e8eb1ad79f7c7e9e6c31db1bd79a16d73271c62867003', out / 'nanors')]
for name, digest, _ in archives:
    assert hashlib.sha256((root / '.deps/p6a' / name).read_bytes()).hexdigest() == digest, name
if out.exists(): shutil.rmtree(out)
for name, _, target in archives:
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(root / '.deps/p6a' / name) as archive:
        for entry in archive.getmembers():
            parts = pathlib.PurePosixPath(entry.name).parts[1:]
            if not parts: continue
            assert '..' not in parts and not entry.issym() and not entry.islnk()
            entry.name = str(pathlib.PurePosixPath(*parts))
            archive.extract(entry, target, filter='data')
for patch in sorted((root / 'stream/scripts/patches').glob('*.patch')):
    subprocess.run(['patch', '--batch', '--fuzz=0', '-p1', '-d', str(out), '-i', str(patch)], check=True)
