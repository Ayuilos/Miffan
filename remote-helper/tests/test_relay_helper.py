#!/usr/bin/env python3
"""Hermetic checks for `miffan relay`: temporary HOME, stubbed service managers and curl, a fake connector.
Nothing touches the network, the real launchd/systemd, or the real home directory."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import tempfile

HELPER = Path(__file__).resolve().parents[1] / "src/miffan.sh"
SH = os.environ.get("HELPER_SH", "/bin/sh")  # CI runs dash; set HELPER_SH=/bin/dash to match it elsewhere.
DEVICE_ID = "do6p7c6wsq3x7aus7ovinattgo"
PLACEHOLDER = "0" * 64

MOCK = r'''#!/usr/bin/env python3
import json, os, shutil, sys
from pathlib import Path
name=Path(sys.argv[0]).name
args=sys.argv[1:]
root=Path(os.environ['FIXTURE'])
f=root/'state.json'
s=json.loads(f.read_text()) if f.exists() else {}
with (root/'argv.jsonl').open('a') as log: log.write(json.dumps([name]+args)+'\n')
def save(): f.write_text(json.dumps(s))
if name=='uname': print(s.get('os','Linux') if args==['-s'] else s.get('arch','x86_64'))
elif name=='sleep': pass
elif name=='systemctl':
    unit=args[-1]
    if 'show-environment' in args: sys.exit(1 if s.get('no_bus') else 0)
    if s.get('enable_fails') and 'enable' in args: sys.exit(1)
    if 'is-active' in args: sys.exit(0 if unit in s.get('active',[]) else 3)
    if 'enable' in args or 'restart' in args:
        s.setdefault('active',[]); unit in s['active'] or s['active'].append(unit); save()
    if 'disable' in args: s['active']=[a for a in s.get('active',[]) if a!=unit]; save()
elif name=='loginctl':
    if args[0]=='enable-linger':
        if s.get('linger_denied'): sys.exit(1)
        s['linger']='yes'; save()
    elif args[0]=='show-user': print(s.get('linger','no'))
elif name=='launchctl':
    if args[0]=='bootstrap':
        if s.get('no_gui'): sys.exit(5)
        s.setdefault('loaded',[]).append(args[2]); save()
    elif args[0]=='bootout': s['loaded']=[p for p in s.get('loaded',[]) if not p.endswith(args[1].rsplit('/',1)[-1]+'.plist')]; save()
    elif args[0]=='print':
        if not s.get('loaded'): sys.exit(113)
        print('state = running')
elif name=='curl':
    url=args[-1]
    out=args[args.index('-o')+1]
    served=s.get('serve',{})
    if url not in served: sys.exit(22)
    shutil.copy(served[url], out)
elif name=='scutil': pass
else: raise SystemExit('unexpected '+name)
'''

# Stands in for miffan-connector: records what it was given and answers like the real one.
FAKE_CONNECTOR = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
root=Path(os.environ['FIXTURE'])
args=sys.argv[1:]
stdin='' if sys.stdin.isatty() else sys.stdin.read()
with (root/'connector.jsonl').open('a') as log: log.write(json.dumps({'args':args,'stdin':stdin})+'\n')
cmd=args[0]
home=Path(os.environ['HOME'])/'.miffan/connector'
def out(**kw): print(json.dumps(kw, separators=(',', ':')))
if cmd=='version': out(success=True, version='1.0.0')
elif cmd=='init':
    if 'refuse' in stdin: out(success=False, reason='bad_relay', detail='relay must be an https URL'); sys.exit(1)
    home.mkdir(parents=True, exist_ok=True)
    (home/'host.key').write_bytes(b'k'*32)
    (home/'config.json').write_text('{"devices":[]}')
    out(success=True, hostId='fakehostid', relay='https://relay.example.com', target='127.0.0.1:22')
elif cmd=='pair': out(success=True, hostId='fakehostid', relay='https://relay.example.com', deviceId='fakedevice')
elif cmd=='unpair': out(success=True, removed=True, devices=[])
elif cmd=='status':
    if (root/'status_fails').exists(): out(success=False, reason='not_initialized', detail='run init first'); sys.exit(1)
    out(success=True, version='1.0.0', hostId='fakehostid', relay='https://relay.example.com', running=True, connected=True,
        lastError=None, lastConnectedAt='2026-11-01T12:00:00Z', edge='default', devices=[])
else: sys.exit(2)
'''


class Host:
    def __init__(self, base, os_name="Linux", arch="x86_64", **state):
        self.base = Path(base)
        self.home = self.base / "home"
        self.bin = self.base / "bin"
        self.home.mkdir()
        (self.home / ".miffan/bin").mkdir(parents=True)
        self.bin.mkdir()
        mock = self.bin / "mock"
        mock.write_text(MOCK)
        mock.chmod(0o755)
        for name in ["uname", "systemctl", "loginctl", "launchctl", "curl", "sleep", "scutil"]:
            (self.bin / name).symlink_to(mock)
        self.connector = self.base / "fake-connector"
        self.connector.write_text(FAKE_CONNECTOR)
        self.connector.chmod(0o755)
        self.sha = hashlib.sha256(self.connector.read_bytes()).hexdigest()
        self.state(os=os_name, arch=arch, **state)
        self.helper = HELPER

    def state(self, **values):
        path = self.base / "state.json"
        current = json.loads(path.read_text()) if path.exists() else {}
        current.update(values)
        path.write_text(json.dumps(current))

    def read_state(self):
        return json.loads((self.base / "state.json").read_text())

    def calls(self, name):
        path = self.base / "argv.jsonl"
        lines = path.read_text().splitlines() if path.exists() else []
        return [call[1:] for call in map(json.loads, lines) if call[0] == name]

    def connector_calls(self):
        path = self.base / "connector.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def run(self, *args, stdin="", extra_env=None, use_file=True, sha=None):
        env = {
            "HOME": str(self.home),
            "PATH": f"{self.bin}:/usr/bin:/bin:/usr/sbin:/sbin",
            "FIXTURE": str(self.base),
            "XDG_RUNTIME_DIR": str(self.base / "run"),
            "DBUS_SESSION_BUS_ADDRESS": "unix:path=/dev/null",
        }
        if use_file:
            env["MIFFAN_CONNECTOR_FILE"] = str(self.connector)
        if sha != "":  # sha="" leaves the override unset, so the helper's own pinned digests apply
            env["MIFFAN_CONNECTOR_SHA256"] = sha or self.sha
        env.update(extra_env or {})
        return subprocess.run([SH, str(self.helper), "relay", *args], input=stdin, text=True,
                              capture_output=True, env=env, timeout=60)

    def json(self, *args, stdin="", ok=True, **kw):
        result = self.run(*args, stdin=stdin, **kw)
        lines = result.stdout.strip().splitlines()
        assert len(lines) == 1, (args, result.stdout, result.stderr)  # exactly one line of JSON
        data = json.loads(lines[0])
        if ok:
            assert result.returncode == 0 and data.get("success", True), (args, result.stdout, result.stderr)
        else:
            assert result.returncode != 0 and data.get("success") is False, (args, result.stdout)
        return data

    def installed(self):
        return self.home / ".miffan/bin/miffan-connector"

    def leftovers(self):
        return [p.name for p in (self.home / ".miffan/bin").glob(".miffan-connector.*")]


INSTALL = json.dumps({"relay": "https://relay.example.com", "accessToken": "s3cret-\"quoted\" token"})


def test_status_before_install(base):
    host = Host(base)
    assert host.json("status") == {"installed": False}


def test_linux_install_and_status(base):
    host = Host(base)
    status = host.json("install", stdin=INSTALL)
    assert status["installed"] is True and status["service"] == "running" and status["pinnedVersion"] == "1.0.0"
    assert status["linger"] is True and status["hostId"] == "fakehostid" and status["success"] is True
    assert host.installed().read_bytes() == host.connector.read_bytes()
    assert oct(host.installed().stat().st_mode & 0o777) == "0o755"
    assert host.leftovers() == []

    # The user's JSON reaches the connector untouched and never appears on a command line.
    init = [c for c in host.connector_calls() if c["args"][0] == "init"][0]
    assert init["args"] == ["init", "--stdin"] and init["stdin"] == INSTALL
    assert all("s3cret" not in " ".join(c["args"]) for c in host.connector_calls())
    for name in ["systemctl", "loginctl", "curl"]:
        assert all("s3cret" not in " ".join(a) for a in host.calls(name))

    unit = (host.home / ".config/systemd/user/miffan-connector.service").read_text()
    assert "ExecStart=%h/.miffan/bin/miffan-connector run" in unit and "Restart=always" in unit
    assert "WantedBy=default.target" in unit
    systemctl = host.calls("systemctl")
    assert ["--user", "daemon-reload"] in systemctl
    assert ["--user", "enable", "--now", "miffan-connector.service"] in systemctl
    assert host.calls("loginctl")[0][0] == "enable-linger"
    assert host.calls("curl") == []  # MIFFAN_CONNECTOR_FILE skips the download

    again = host.json("status")
    assert again["installed"] and again["service"] == "running" and again["connected"] is True and again["pinnedVersion"] == "1.0.0"


def test_linger_refused_is_not_an_error(base):
    host = Host(base, linger_denied=True)
    status = host.json("install", stdin=INSTALL)
    assert status["linger"] is False and status["service"] == "running"


def test_macos_install(base):
    host = Host(base, os_name="Darwin", arch="arm64")
    status = host.json("install", stdin=INSTALL)
    assert status["service"] == "running" and status["linger"] is None
    plist = plistlib.loads((host.home / "Library/LaunchAgents/me.ayuilos.miffan.connector.plist").read_bytes())
    assert plist["Label"] == "me.ayuilos.miffan.connector"
    assert plist["ProgramArguments"] == [str(host.installed()), "run"]
    assert plist["RunAtLoad"] is True and plist["KeepAlive"] is True
    assert plist["StandardErrorPath"] == str(host.home / ".miffan/connector/connector.log")
    boots = [c for c in host.calls("launchctl") if c[0] == "bootstrap"]
    assert boots and boots[-1][1] == f"gui/{os.getuid()}" and boots[-1][2].endswith("me.ayuilos.miffan.connector.plist")


def test_macos_without_gui_session(base):
    host = Host(base, os_name="Darwin", arch="arm64", no_gui=True)
    failure = host.json("install", stdin=INSTALL, ok=False)
    assert failure["reason"] == "login_required"


def test_checksum_mismatch_installs_nothing(base):
    host = Host(base)
    failure = host.json("install", stdin=INSTALL, ok=False, sha="f" * 64)
    assert failure["reason"] == "checksum_mismatch"
    assert not host.installed().exists() and host.leftovers() == []
    assert host.connector_calls() == [] and host.calls("systemctl") == []
    # The shipped placeholder digests never match: an unpublished build cannot be installed.
    for sha in (PLACEHOLDER, ""):
        failure = host.json("install", stdin=INSTALL, ok=False, sha=sha)
        assert failure["reason"] == "checksum_mismatch" and not host.installed().exists(), sha


def test_download_order_and_mirrors(base):
    host = Host(base, arch="aarch64")
    release = "https://github.com/Ayuilos/Miffan/releases/download/relay-connector-v1.0.0/miffan-connector-linux-arm64"
    mirror_base = "https://mirror.example/connector"
    mirror = mirror_base + "/miffan-connector-linux-arm64"
    patched = Path(base) / "miffan-with-mirror.sh"
    text = HELPER.read_text()
    assert 'MIFFAN_CONNECTOR_MIRRORS=""' in text
    patched.write_text(text.replace('MIFFAN_CONNECTOR_MIRRORS=""', f'MIFFAN_CONNECTOR_MIRRORS="{mirror_base}"'))
    host.helper = patched

    host.state(serve={release: str(host.connector)})
    host.json("install", stdin=INSTALL, use_file=False)
    urls = [c[-1] for c in host.calls("curl")]
    assert urls == [release]
    assert host.installed().read_bytes() == host.connector.read_bytes()

    # GitHub fails → the mirror is tried next.
    host.json("uninstall")
    (host.base / "argv.jsonl").unlink()
    host.state(serve={mirror: str(host.connector)})
    host.json("install", stdin=INSTALL, use_file=False)
    assert [c[-1] for c in host.calls("curl")] == [release, mirror]

    # Everything fails.
    host.json("uninstall")
    host.state(serve={})
    failure = host.json("install", stdin=INSTALL, use_file=False, ok=False)
    assert failure["reason"] == "download_failed" and not host.installed().exists() and host.leftovers() == []


def test_same_version_skips_download(base):
    host = Host(base)
    release = "https://github.com/Ayuilos/Miffan/releases/download/relay-connector-v1.0.0/miffan-connector-linux-amd64"
    host.state(serve={release: str(host.connector)})
    host.json("install", stdin=INSTALL, use_file=False)
    assert len(host.calls("curl")) == 1
    host.json("install", stdin=INSTALL, use_file=False)
    assert len(host.calls("curl")) == 1, "an installed connector of the pinned version must not be downloaded again"


def test_install_validation_comes_from_connector(base):
    host = Host(base)
    failure = host.json("install", stdin='{"relay":"refuse"}', ok=False)
    assert failure["reason"] == "bad_relay"
    assert host.calls("systemctl") == [], "no service is set up when init fails"
    assert host.json("install", stdin="", ok=False)["reason"] == "bad_input"


def test_unsupported_platforms(base):
    for os_name, arch in [("FreeBSD", "amd64"), ("Linux", "riscv64"), ("Darwin", "ppc")]:
        with tempfile.TemporaryDirectory() as sub:
            host = Host(sub, os_name=os_name, arch=arch)
            assert host.json("install", stdin=INSTALL, ok=False)["reason"] == "unsupported_platform", (os_name, arch)
            assert host.json("status") == {"installed": False}


def test_linux_without_user_manager(base):
    host = Host(base, no_bus=True)
    assert host.json("install", stdin=INSTALL, ok=False)["reason"] == "no_service_manager"
    host2_state = {"no_bus": False, "enable_fails": True}
    host.state(**host2_state)
    assert host.json("install", stdin=INSTALL, ok=False)["reason"] == "service_failed"


def test_pair_and_unpair_forward_to_connector(base):
    host = Host(base)
    assert host.json("pair", stdin="{}", ok=False)["reason"] == "not_installed"
    assert host.json("unpair", stdin="{}", ok=False)["reason"] == "not_installed"
    host.json("install", stdin=INSTALL)

    body = json.dumps({"device": "MFkwEwYH", "name": "PGEM10 \"quoted\" 手机"})
    paired = host.json("pair", stdin=body)
    assert paired["deviceId"] == "fakedevice" and paired["hostId"] == "fakehostid"
    call = [c for c in host.connector_calls() if c["args"][0] == "pair"][0]
    assert call["args"] == ["pair", "--stdin"] and call["stdin"] == body

    removed = host.json("unpair", stdin=json.dumps({"deviceId": DEVICE_ID}))
    assert removed["removed"] is True
    call = [c for c in host.connector_calls() if c["args"][0] == "unpair"][0]
    assert call["args"] == ["unpair", f"--device-id={DEVICE_ID}"]

    before = len(host.connector_calls())
    for bad in ['{"deviceId":"short"}', '{"deviceId":"DO6P7C6WSQ3X7AUS7OVINATTGO"}', '{"deviceId":"x; rm -rf ~"}',
                '{"deviceId":"--device-id=../../etc"}', "{}", "", '{"deviceId":"do6p7c6wsq3x7aus7ovinattgo\\u0000"}']:
        assert host.json("unpair", stdin=bad, ok=False)["reason"] == "bad_input", bad
    assert len(host.connector_calls()) == before, "an invalid id must never reach the connector"


def test_status_when_connector_cannot_answer(base):
    host = Host(base)
    host.json("install", stdin=INSTALL)
    (host.base / "status_fails").write_text("")
    status = host.json("status")
    assert status["installed"] is True and status["error"] == "not_initialized" and status["service"] == "running"


def test_uninstall_keeps_identity(base):
    host = Host(base)
    host.json("install", stdin=INSTALL)
    data = host.home / ".miffan/connector"
    (data / "state.json").write_text("{}")
    (data / "run.lock").write_text("")
    result = host.json("uninstall")
    assert result == {"success": True, "identityKept": True}
    assert not host.installed().exists() and not (host.home / ".config/systemd/user/miffan-connector.service").exists()
    assert (data / "host.key").read_bytes() == b"k" * 32 and (data / "config.json").exists()
    assert not (data / "state.json").exists() and not (data / "run.lock").exists()
    assert ["--user", "disable", "--now", "miffan-connector.service"] in host.calls("systemctl")
    assert host.json("status") == {"installed": False}
    # Installing again keeps the same identity.
    host.json("install", stdin=INSTALL)
    assert (data / "host.key").read_bytes() == b"k" * 32


def test_uninstall_forget_identity(base):
    host = Host(base, os_name="Darwin", arch="x86_64")
    host.json("install", stdin=INSTALL)
    assert (host.home / "Library/LaunchAgents/me.ayuilos.miffan.connector.plist").exists()
    result = host.json("uninstall", stdin='{"forgetIdentity": true}')
    assert result == {"success": True, "identityKept": False}
    assert not (host.home / ".miffan/connector").exists() and not host.installed().exists()
    assert not (host.home / "Library/LaunchAgents/me.ayuilos.miffan.connector.plist").exists()
    assert any(c[0] == "bootout" for c in host.calls("launchctl"))


def test_log_is_trimmed_on_install(base):
    host = Host(base)
    data = host.home / ".miffan/connector"
    data.mkdir(parents=True)
    (data / "connector.log").write_bytes(b"x" * 2_000_000)
    host.json("install", stdin=INSTALL)
    assert (data / "connector.log").stat().st_size == 131072


def test_usage_and_unknown_actions(base):
    host = Host(base)
    for args in [(), ("bogus",), ("install --now",)]:
        result = host.run(*args)
        assert result.returncode == 2 and "usage: miffan relay" in result.stderr, args
    top = subprocess.run([SH, str(HELPER), "bogus"], capture_output=True, text=True)
    assert "relay" in top.stderr


def test_version_bumped_and_locked(base):
    text = HELPER.read_text()
    version = int(re.search(r"^MIFFAN_HELPER_VERSION=(\d+)$", text, re.M).group(1))
    assert version >= 33
    lock = (HELPER.parents[1] / "versions.lock").read_text().splitlines()
    assert any(line.split()[:2] == ["miffan.sh", str(version)] for line in lock if line.strip() and not line.startswith("#"))


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for test in tests:
        with tempfile.TemporaryDirectory() as base:
            test(base)
        print(f"PASS: {test.__name__}")


if __name__ == "__main__":
    main()
