#!/usr/bin/env python3
"""Hermetic checks for `miffan snapshot`: a temporary HOME and a PATH holding only the tools the helper
needs, with stubbed uname and image encoders, so no real converter on the machine changes the result."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HELPER = Path(__file__).resolve().parents[1] / "src/miffan.sh"
SH = os.environ.get("HELPER_SH", "/bin/sh")  # CI runs dash; set HELPER_SH=/bin/dash to match it elsewhere.
REAL_TOOLS = ["sh", "cksum", "awk", "base64", "tr", "mkdir", "chmod", "rm", "cat", "sed", "head", "dirname"]
PNG = b"\x89PNG\r\n\x1a\nfixture-picture"

STUB = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
name = sys.argv[1]
args = sys.argv[2:]
root = Path(os.environ["FIXTURE"])
with (root / "argv.jsonl").open("a") as log: log.write(json.dumps([name] + args) + "\n")
if name == "uname": print((root / "os").read_text().strip() if args == ["-s"] else "x86_64")
elif name == "sips": Path(args[args.index("--out") + 1]).write_bytes(b"\xff\xd8\xffsips")
elif name == "magick": Path(args[-1]).write_bytes(b"\xff\xd8\xffmagick")
else: raise SystemExit("unexpected " + name)
'''


class Fixture:
    def __init__(self, root: Path, os_name: str, encoders=()):
        self.root, self.home, self.bin = root, root / "home", root / "bin"
        self.home.mkdir()
        self.bin.mkdir()
        (root / "os").write_text(os_name)
        stub = root / "stub.py"
        stub.write_text(STUB)
        for tool in ["uname", *encoders]:
            target = self.bin / tool
            target.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{stub}" {tool} "$@"\n')
            target.chmod(0o755)
        for tool in REAL_TOOLS:
            found = shutil.which(tool)
            assert found, tool
            (self.bin / tool).symlink_to(found)

    def run(self, *args):
        env = {"HOME": str(self.home), "PATH": str(self.bin), "FIXTURE": str(self.root)}
        done = subprocess.run([SH, str(HELPER), "snapshot", *args], capture_output=True, text=True, env=env, timeout=30)
        return done.returncode, done.stdout.strip(), done.stderr

    def json(self, *args, ok=True):
        code, out, err = self.run(*args)
        assert (code == 0) == ok, (args, code, out, err)
        return json.loads(out.splitlines()[-1])

    def calls(self, name):
        log = self.root / "argv.jsonl"
        return [c for c in map(json.loads, log.read_text().splitlines()) if c[0] == name] if log.exists() else []

    def picture(self, data=PNG):
        path = Path(self.json("path")["path"])
        path.write_bytes(data)
        return path


def fixture(os_name, encoders=()):
    root = Path(tempfile.mkdtemp(prefix="miffan-snapshot-"))
    return Fixture(root, os_name, encoders)


def test_path_creates_a_private_folder():
    fx = fixture("Linux")
    path = Path(fx.json("path")["path"])
    assert path == fx.home / ".miffan/snapshot/desktop.png"
    assert (path.parent.stat().st_mode & 0o777) == 0o700


def test_encode_without_a_capture_fails():
    fx = fixture("Linux")
    fx.json("path")
    assert fx.json("encode", "55", ok=False) == {"error": "no_snapshot"}


def test_linux_without_an_encoder_sends_the_png_and_then_same():
    fx = fixture("Linux")
    fx.picture()
    first = fx.json("encode", "55")
    assert first["format"] == "png" and base64.b64decode(first["data"]) == PNG and first["digest"]
    assert fx.json("encode", "55", first["digest"]) == {"same": True, "digest": first["digest"]}
    fx.picture(PNG + b"changed")
    changed = fx.json("encode", "55", first["digest"])
    assert changed["digest"] != first["digest"] and "data" in changed


def test_linux_encodes_with_imagemagick_and_clamps_quality():
    fx = fixture("Linux", encoders=["magick"])
    fx.picture()
    out = fx.json("encode", "55")
    assert out["format"] == "jpeg" and base64.b64decode(out["data"]) == b"\xff\xd8\xffmagick"
    for given, used in [("5", "20"), ("200", "90"), ("abc", "55")]:
        fx.json("encode", given)
        assert fx.calls("magick")[-1][2:4] == ["-quality", used], fx.calls("magick")[-1]
    # The JPEG is sent, not left behind next to the capture.
    assert not (fx.home / ".miffan/snapshot/desktop.jpg").exists()


def test_macos_encodes_with_sips():
    fx = fixture("Darwin", encoders=["sips"])
    fx.picture()
    out = fx.json("encode", "60")
    assert out["format"] == "jpeg" and base64.b64decode(out["data"]) == b"\xff\xd8\xffsips"
    call = fx.calls("sips")[-1]
    assert call[1:6] == ["-s", "format", "jpeg", "-s", "formatOptions"] and call[6] == "60"


def test_usage():
    fx = fixture("Linux")
    code, _, err = fx.run("bogus")
    assert code == 2 and "usage" in err


def main():
    tests = [(name, fn) for name, fn in sorted(globals().items()) if name.startswith("test_") and callable(fn)]
    for name, fn in tests:
        fn()
        print("PASS:", name)


if __name__ == "__main__":
    main()
