#!/usr/bin/env python3
"""Hermetic checks for `miffan snapshot serve`: a temporary HOME and a PATH holding only the tools the helper
needs, with stubbed uname and image encoders, so no real converter on the machine changes the result."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HELPER = Path(__file__).resolve().parents[1] / "src/miffan.sh"
SH = os.environ.get("HELPER_SH", "/bin/sh")  # CI runs dash; set HELPER_SH=/bin/dash to match it elsewhere.
REAL_TOOLS = ["sh", "cksum", "awk", "wc", "tr", "mkdir", "chmod", "rm", "cat", "sed", "head", "dirname"]
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
elif name == "magick":
    # Like ffmpeg, take whatever stdin holds; the helper must not hand it the request stream.
    os.set_blocking(0, False)
    try: taken = sys.stdin.buffer.read() or b""
    except (BlockingIOError, TypeError): taken = b""
    (root / "magick-stdin").write_bytes((root / "magick-stdin").read_bytes() + taken if (root / "magick-stdin").exists() else taken)
    Path(args[-1]).write_bytes(b"\xff\xd8\xffmagick")
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

    def env(self):
        return {"HOME": str(self.home), "PATH": str(self.bin), "FIXTURE": str(self.root)}

    def run(self, *args):
        done = subprocess.run([SH, str(HELPER), "snapshot", *args], capture_output=True, text=True, env=self.env(), timeout=30)
        return done.returncode, done.stdout.strip(), done.stderr

    def serve(self):
        return Serve(subprocess.Popen([SH, str(HELPER), "snapshot", "serve"], stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env()))

    def calls(self, name):
        log = self.root / "argv.jsonl"
        return [c for c in map(json.loads, log.read_text().splitlines()) if c[0] == name] if log.exists() else []


class Serve:
    """One `snapshot serve` process: `ready PATH` first, then one reply per request line."""

    def __init__(self, process):
        self.process = process
        ready = self.line()
        assert ready.startswith("ready "), ready
        self.path = Path(ready[len("ready "):])

    def line(self):
        raw = self.process.stdout.readline()
        assert raw.endswith(b"\n"), (raw, self.process.stderr.read() if self.process.poll() is not None else "")
        return raw[:-1].decode()

    def send(self, quality, since=None):
        self.process.stdin.write((f"{quality} {since}" if since else f"{quality}").encode() + b"\n")
        self.process.stdin.flush()

    def ask(self, quality, since=None):
        self.send(quality, since)
        return self.reply()

    def reply(self):
        words = self.line().split(" ")
        if words[0] in ("jpeg", "png"):
            data = self.process.stdout.read(int(words[2]))
            return {"format": words[0], "digest": words[1], "data": data}
        if words[0] == "same":
            return {"same": words[1]}
        assert words[0] == "error", words
        return {"error": words[1]}

    def picture(self, data=PNG):
        self.path.write_bytes(data)

    def close(self):
        self.process.stdin.close()
        assert self.process.wait(timeout=10) == 0
        assert self.process.stdout.read() == b""


def fixture(os_name, encoders=()):
    root = Path(tempfile.mkdtemp(prefix="miffan-snapshot-"))
    return Fixture(root, os_name, encoders)


def test_serve_names_the_capture_in_a_private_folder():
    fx = fixture("Linux")
    serve = fx.serve()
    assert serve.path == fx.home / ".miffan/snapshot/desktop.png"
    assert (serve.path.parent.stat().st_mode & 0o777) == 0o700
    serve.close()


def test_a_request_before_any_capture_is_an_error_and_serving_goes_on():
    fx = fixture("Linux")
    serve = fx.serve()
    assert serve.ask(55) == {"error": "no_snapshot"}
    serve.picture()
    assert serve.ask(55)["data"] == PNG
    serve.close()


def test_linux_without_an_encoder_sends_the_raw_png_and_then_same():
    fx = fixture("Linux")
    serve = fx.serve()
    serve.picture()
    first = serve.ask(55)
    assert first["format"] == "png" and first["data"] == PNG and first["digest"]
    assert serve.ask(55, first["digest"]) == {"same": first["digest"]}
    serve.picture(PNG + b"changed")
    changed = serve.ask(55, first["digest"])
    assert changed["digest"] != first["digest"] and changed["data"] == PNG + b"changed"
    serve.close()


def test_binary_bytes_pass_unchanged():
    fx = fixture("Linux")
    serve = fx.serve()
    data = bytes(range(256)) * 300 + b"\n\r\x00"
    serve.picture(data)
    assert serve.ask(55)["data"] == data
    serve.close()


def test_linux_encodes_with_imagemagick_and_clamps_quality():
    fx = fixture("Linux", encoders=["magick"])
    serve = fx.serve()
    serve.picture()
    out = serve.ask(55)
    assert out["format"] == "jpeg" and out["data"] == b"\xff\xd8\xffmagick"
    for given, used in [("5", "20"), ("200", "90"), ("abc", "55")]:
        serve.ask(given)
        assert fx.calls("magick")[-1][2:4] == ["-quality", used], fx.calls("magick")[-1]
    serve.close()
    # The JPEG is sent, not left behind next to the capture.
    assert not (fx.home / ".miffan/snapshot/desktop.jpg").exists()


def test_an_encoder_cannot_swallow_the_next_request():
    fx = fixture("Linux", encoders=["magick"])
    serve = fx.serve()
    serve.picture()
    # Requests sent ahead wait on stdin while the first picture is encoded.
    for _ in range(3):
        serve.send(60)
    for _ in range(3):
        assert serve.reply()["format"] == "jpeg"
    assert all(call[-1].endswith("desktop.jpg") for call in fx.calls("magick"))
    assert (fx.root / "magick-stdin").read_bytes() == b""
    serve.close()


def test_macos_encodes_with_sips():
    fx = fixture("Darwin", encoders=["sips"])
    serve = fx.serve()
    serve.picture()
    out = serve.ask(60)
    assert out["format"] == "jpeg" and out["data"] == b"\xff\xd8\xffsips"
    call = fx.calls("sips")[-1]
    assert call[1:6] == ["-s", "format", "jpeg", "-s", "formatOptions"] and call[6] == "60"
    serve.close()


def test_usage():
    fx = fixture("Linux")
    for args in [("bogus",), ("encode", "55"), ("path",)]:
        code, _, err = fx.run(*args)
        assert code == 2 and "usage" in err, args


def main():
    tests = [(name, fn) for name, fn in sorted(globals().items()) if name.startswith("test_") and callable(fn)]
    for name, fn in tests:
        fn()
        print("PASS:", name)


if __name__ == "__main__":
    main()
