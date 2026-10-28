#!/usr/bin/env python3
"""Hermetic checks for `miffan space`; a temporary HOME and stub cua/docker/curl/systemctl, no real Spaces."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

HELPER = Path(__file__).resolve().parents[1] / "src/miffan.sh"
SH = os.environ.get("HELPER_SH", "/bin/sh")  # CI runs dash; set HELPER_SH=/bin/dash to match it elsewhere.
NAME = "miffan-0123abcd"
TOKEN = "t0k3n" + "0123456789abcdef0123456789a"  # 32 characters
MOCK = r'''#!/usr/bin/env python3
import json, os, shutil, subprocess, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
root = Path(os.environ['FIXTURE'])
sf = root / 'state.json'
s = json.loads(sf.read_text())
with (root / 'argv.jsonl').open('a') as log:
    log.write(json.dumps([name] + args) + '\n')
def save(): sf.write_text(json.dumps(s))
def space_path(text): return text.replace('/home/cua', str(root / 'space_home'))
def info(n):
    sp = s['spaces'][n]
    out = {'id': 'local:' + n, 'name': n, 'runtime': 'runc', 'state': sp['state'], 'status': 'ready',
           'endpoints': {'env': 'http://127.0.0.1:%d' % sp['port']} if sp['state'] == 'running' else {},
           'services': {'env': 3211} if sp['state'] == 'running' else {}}
    return json.dumps(out, indent=2)
def fail(msg, rc=1):
    sys.stderr.write(msg + '\n'); sys.exit(rc)

if name == 'uname':
    print(s.get('os', 'Linux') if args == ['-s'] else 'x86_64')
elif name == 'df':
    print('Filesystem 1024-blocks Used Available Capacity Mounted')
    print('/dev/x 999 1 %d 1%% /' % s.get('free_kb', 100 * 1048576))
elif name == 'systemctl':
    if 'is-active' in args:
        if s.get('ufw'): print('active')
        else: print('inactive'); sys.exit(3)
    else: sys.exit(1)
elif name == 'docker':
    if s.get('docker') == 'permission': fail('permission denied while trying to connect to the Docker daemon socket')
    print('27.0.1')
elif name == 'curl':
    if '-K' in args:
        if 'Authorization: Bearer' not in sys.stdin.read() and 'authorization: Bearer' not in open(os.devnull).read():
            pass
        print(json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': {'serverInfo': {'name': 'cua-driver', 'version': s.get('driver', '0.38.0')}}}))
    elif '-o' in args:
        if s.get('install_fail'): fail('curl: (22) The requested URL returned error: 404', 22)
        Path(args[args.index('-o') + 1]).write_text(
            '#!/bin/sh\necho "installer: $*" >&2\necho "installer stdout noise"\n'
            'mkdir -p "$HOME/.local/bin" && ln -sf "%s/cua" "$HOME/.local/bin/cua"\n' % os.environ['STUBBIN'])
    else: fail('unexpected curl')
elif name == 'cua':
    if args == ['--version']: print('cua 0.4.4'); sys.exit(0)
    if args and args[0] == '--json': args = args[1:]
    if args[:2] == ['daemon', 'status']:
        if s.get('daemon'): print('cua daemon 0.9.0 (pid 4242) socket=/x loopback=http://127.0.0.1:1')
        else: print('cua daemon is not running'); sys.exit(1)
    elif args[:2] == ['daemon', 'start']:
        (root / 'daemon_env.json').write_text(json.dumps({k: os.environ.get(k) for k in ('HTTPS_PROXY', 'NO_PROXY', 'CUA_TELEMETRY')}))
        if s.get('daemon_fail'): fail('cannot bind the control socket')
        s['daemon'] = True; save()
    elif args[:2] == ['sb', 'ls']:
        items = [{'id': 'local:' + n, 'name': n, 'state': sp['state'], 'endpoints': {}} for n, sp in s['spaces'].items()]
        items += [{'id': 'direct:100.64.0.5:3213', 'name': 'Omarchy', 'state': 'running'}]
        print(json.dumps(items))
    elif args[:2] == ['sb', 'info']:
        if args[2] not in s['spaces']: fail('no such sandbox')
        print(info(args[2]))
    elif args[:2] == ['spaces', 'create']:
        n = args[args.index('--name') + 1]
        if s.get('create_error'): print(json.dumps({'spaces': [{'error': s['create_error']}]})); sys.exit(0)
        if s.get('create_stderr'): fail(s['create_stderr'])
        s['seq'] = s.get('seq', 40000) + 1
        s['spaces'][n] = {'state': 'stopped' if s.get('never_running') else 'running', 'port': s['seq']}
        save()
        print(json.dumps({'spaces': [{'id': 'local:' + n, 'name': n}]}))
    elif args[:2] == ['spaces', 'start']:
        n = args[2].split(':', 1)[1]
        if s.get('start_fail'): fail('runtime vm failed to boot')
        s['seq'] = s.get('seq', 40000) + 1
        s['spaces'][n].update(state='running', port=s['seq']); save()
        print('Resumed local:' + n + '.')
    elif args[:2] == ['spaces', 'stop']:
        s['spaces'][args[2].split(':', 1)[1]]['state'] = 'stopped'; save()
    elif args[:2] == ['spaces', 'delete']:
        if s.get('delete_fail'): fail('delete refused')
        s['spaces'].pop(args[2].split(':', 1)[1], None); save()
    elif args[:2] == ['sb', 'exec']:
        cmd = args[3]
        if 'NETCHECK' in cmd:
            if s.get('netcheck') is None: sys.exit(7)
            print(s['netcheck']); sys.exit(0)
        r = subprocess.run(['/bin/sh', '-c', space_path(cmd)])
        sys.exit(r.returncode)
    elif args[:2] == ['sb', 'cp']:
        n = args[2 if ':' in args[2] and args[2].startswith('miffan-') else 3].split(':', 1)[0]
        src, dst = args[2], args[3]
        src = space_path(src.split(':', 1)[1]) if src.startswith(n + ':') else src
        dst = space_path(dst.split(':', 1)[1]) if dst.startswith(n + ':') else dst
        if not Path(dst).parent.is_dir(): fail('destination directory is missing')
        shutil.copyfile(src, dst)
    else: fail('unexpected cua ' + ' '.join(args))
else: fail('unexpected ' + name)
'''


class Host:
    def __init__(self, base, os_name="Linux", cua=True, with_docker=True, **state):
        self.base = Path(base)
        self.home = self.base / "home"
        self.bin = self.base / "bin"
        self.folder = self.base / "hostfolder"
        self.home.mkdir()
        self.bin.mkdir()
        self.stderr = []
        mock = self.bin / "mock"
        mock.write_text(MOCK)
        mock.chmod(0o755)
        names = ["uname", "df", "systemctl", "curl"] + (["docker"] if with_docker else [])
        for tool in names:
            (self.bin / tool).symlink_to(mock)
        (self.bin / "cua-stub").symlink_to(mock)
        self.stubbin = self.base / "stubbin"
        self.stubbin.mkdir()
        (self.stubbin / "cua").symlink_to(mock)
        if cua:
            (self.home / ".local/bin").mkdir(parents=True)
            (self.home / ".local/bin/cua").symlink_to(mock)
        self.state(**{"os": os_name, "daemon": True, "spaces": {}, **state})
        creds = self.home / ".cua"
        creds.mkdir()
        (creds / "spaces-credentials.json").write_text(json.dumps({
            f"local:{NAME}": {"token": TOKEN},
            "direct:100.64.0.5:3213": {"url": "http://100.64.0.5:3213", "token": "x" * 32}}, indent=2))

    def state(self, **values):
        path = self.base / "state.json"
        current = json.loads(path.read_text()) if path.exists() else {}
        current.update(values)
        path.write_text(json.dumps(current))

    def read_state(self):
        return json.loads((self.base / "state.json").read_text())

    def add_space(self, name=NAME, state="running", port=41000):
        spaces = self.read_state()["spaces"]
        spaces[name] = {"state": state, "port": port}
        self.state(spaces=spaces)

    def calls(self, name="cua"):
        path = self.base / "argv.jsonl"
        lines = path.read_text().splitlines() if path.exists() else []
        return [call[1:] for call in map(json.loads, lines) if call[0] == name]

    def space_home(self):
        return self.base / "space_home"

    def run(self, *args, stdin="", extra_env=None):
        env = {
            "HOME": str(self.home),
            "PATH": f"{self.bin}:/usr/bin:/bin:/usr/sbin:/sbin",
            "FIXTURE": str(self.base),
            "STUBBIN": str(self.stubbin),
            "MIFFAN_SPACE_WAIT": "2",
        }
        env.update(extra_env or {})
        result = subprocess.run([SH, str(HELPER), "space", *args], input=stdin, text=True,
                                capture_output=True, env=env, timeout=120)
        self.stderr.append(result.stderr)
        return result

    def json(self, *args, stdin="", extra_env=None, rc=0):
        result = self.run(*args, stdin=stdin, extra_env=extra_env)
        assert result.stdout.strip(), (args, result.stderr)
        data = json.loads(result.stdout.strip().splitlines()[-1])
        assert result.returncode == rc, (args, result.returncode, result.stdout, result.stderr)
        return data

    def error(self, *args, stdin="", extra_env=None, rc=1):
        data = self.json(*args, stdin=stdin, extra_env=extra_env, rc=rc)
        assert set(data) == {"error", "detail"}, data
        return data

    def handoff(self, direction, payload, rc=0):
        return self.json("handoff", direction, NAME, stdin=json.dumps(payload, ensure_ascii=False), rc=rc)

    def all_logs(self):
        path = self.base / "argv.jsonl"
        return (path.read_text() if path.exists() else "") + "".join(self.stderr)


def sub(base, name, **kwargs):
    path = Path(base) / name
    path.mkdir()
    return Host(path, **kwargs)


def check_no_token(host):
    assert TOKEN not in host.all_logs(), "the Space token leaked into stderr or an argument list"


def test_probe(base):
    host = Host(base, cua=False)
    data = host.json("probe")
    assert data["cua"] == {"path": None, "version": None} and data["daemon"] == {"running": False}
    assert data["spaces"] == [] and data["docker"] == {"ok": True, "detail": None} and data["disk_free_gb"] == 100

    host = sub(base, "b", os_name="Darwin")
    host.add_space(NAME, "running")
    host.add_space("miffan-ffffffff", "stopped")
    data = host.json("probe")
    assert data["cua"]["version"] == "0.4.4" and data["cua"]["path"].endswith(".local/bin/cua")
    assert data["daemon"] == {"running": True} and data["docker"] is None
    # Only the miffan-* Spaces; the stub's direct: machine and anything else is left out.
    assert data["spaces"] == [{"id": f"local:{NAME}", "state": "running"}, {"id": "local:miffan-ffffffff", "state": "stopped"}]
    host.state(daemon=False)
    assert host.json("probe")["spaces"] == []  # not asked: a stopped daemon would be started by listing

    perm = sub(base, "c", docker="permission")
    docker = perm.json("probe")["docker"]
    assert docker["ok"] is False and "permission denied" in docker["detail"]
    nodocker = sub(base, "d", with_docker=False)
    assert nodocker.json("probe")["docker"] == {"ok": False, "detail": "docker not installed"}


def test_install(base):
    host = Host(base, cua=False, daemon=False)
    result = host.run("install", extra_env={"HTTPS_PROXY": "http://proxy.local:8080"})
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout.strip().splitlines()[-1])
    assert data["cua"]["version"] == "0.4.4" and data["daemon"] == {"running": True}
    # The installer ran with the three flags and its stdout noise stayed off our stdout.
    assert "--cli-only --yes --no-modify-path --no-onboarding" in result.stderr
    assert result.stdout.strip().count("\n") == 0 and "noise" not in result.stdout
    env = json.loads((host.base / "daemon_env.json").read_text())
    assert env == {"HTTPS_PROXY": "http://proxy.local:8080", "NO_PROXY": "127.0.0.1,localhost", "CUA_TELEMETRY": "0"}

    # Already installed: no download, just the daemon.
    before = len(host.calls("curl"))
    assert host.json("install")["cua"]["version"] == "0.4.4"
    assert len(host.calls("curl")) == before
    failing = sub(base, "x", cua=False, install_fail=True)
    assert failing.error("install")["error"] == "download_failed"
    broken = sub(base, "y", daemon=False, daemon_fail=True)
    assert broken.error("install")["error"] == "daemon_failed"


def test_create(base):
    host = Host(base, daemon=False)
    created = host.json("create", NAME, extra_env={"HTTPS_PROXY": "http://proxy.local:8080"})
    assert created == {"id": f"local:{NAME}", "state": "running"}
    # The daemon was started first, with the proxy, and every create asked for runc.
    assert json.loads((host.base / "daemon_env.json").read_text())["HTTPS_PROXY"] == "http://proxy.local:8080"
    creates = [c for c in host.calls() if c[:2] == ["spaces", "create"]]
    assert len(creates) == 1 and "--runtime" in creates[0] and creates[0][creates[0].index("--runtime") + 1] == "runc"
    assert "--name" in creates[0] and "--on" in creates[0] and creates[0][creates[0].index("--on") + 1] == "local"
    check_no_token(host)


def test_create_errors(base):
    cases = [
        ({"create_error": "pull ghcr.io/trycua/linux:24.04: i/o timeout"}, "download_failed"),
        ({"create_error": "Docker responded with status code 404: pull access denied"}, "download_failed"),
        ({"create_error": "write /var/lib/x: no space left on device"}, "disk_low"),
        ({"create_error": "Cannot connect to the Docker daemon at unix:///var/run/docker.sock"}, "needs_docker"),
        ({"create_error": "name already in use"}, "create_failed"),
        ({"create_stderr": "something odd happened"}, "create_failed"),
        ({"create_stderr": "failed to download the Linux runtime: dial tcp 1.2.3.4:443"}, "download_failed"),
    ]
    for i, (state, code) in enumerate(cases):
        host = sub(base, str(i), **state)
        err = host.error("create", NAME)
        assert err["error"] == code and err["detail"], (state, err)
        assert host.read_state()["spaces"] == {}
    host = sub(base, "p", docker="ok")
    host.state(docker="permission")
    err = host.error("create", NAME)
    assert err["error"] == "needs_docker" and "permission denied" in err["detail"]
    assert not [c for c in host.calls() if c[:2] == ["spaces", "create"]]
    mac = sub(base, "m", os_name="Darwin", with_docker=False)  # macOS has no docker precheck
    assert mac.json("create", NAME)["state"] == "running"
    low = sub(base, "l", free_kb=1024 * 1024)
    assert low.error("create", NAME)["error"] == "disk_low"
    stuck = sub(base, "w", never_running=True)
    assert stuck.error("create", NAME)["error"] == "create_failed"
    assert sub(base, "n", cua=False).error("create", NAME) == {"error": "cua_missing", "detail": None}
    assert sub(base, "k", daemon=False, daemon_fail=True).error("create", NAME)["error"] == "daemon_failed"


def test_name_validation(base):
    host = Host(base)
    for command in ["create", "info", "start", "stop", "delete", "reset", "netcheck"]:
        for bad in ["", "miffan-ABCDEF01", "miffan-123", "miffan-0123abcdef", "../x", "other-0123abcd", "miffan-0123abcd;x", "local:miffan-0123abcd"]:
            args = [command] + ([bad] if bad else [])
            result = host.run(*args)
            assert result.returncode == 2, (args, result.stdout)
            assert json.loads(result.stdout) == {"error": "bad_name", "detail": None}
    for direction in ["in", "out"]:
        result = host.run("handoff", direction, "../x", stdin="{}")
        assert result.returncode == 2 and json.loads(result.stdout)["error"] == "bad_name"
    assert not host.calls(), "invalid names must not reach cua"
    assert host.run("bogus").returncode == 2


def test_info(base):
    host = Host(base)
    host.add_space(NAME, "stopped", port=41000)
    info = host.json("info", NAME)
    assert info["id"] == f"local:{NAME}" and info["state"] == "running" and info["token"] == TOKEN
    assert info["port"] == 40001 and info["driver_version"] == "0.38.0"  # started first: a fresh port
    assert [c for c in host.calls() if c[:2] == ["spaces", "start"]] == [["spaces", "start", f"local:{NAME}"]]
    # A running Space is not started again, and the port is read every time.
    host.add_space(NAME, "running", port=42222)
    again = host.json("info", NAME)
    assert again["port"] == 42222
    assert len([c for c in host.calls() if c[:2] == ["spaces", "start"]]) == 1
    # Only the JSON line on stdout has the token.
    result = host.run("info", NAME)
    assert TOKEN in result.stdout
    check_no_token(host)

    host.state(driver="0.33.1")
    assert host.json("info", NAME)["driver_version"] == "0.33.1"
    assert host.error("info", "miffan-ffffffff")["error"] == "not_found"

    host.add_space(NAME, "stopped")
    host.state(start_fail=True)
    assert host.error("info", NAME)["error"] == "start_failed"


def test_lifecycle(base):
    host = Host(base)
    host.add_space(NAME)
    assert host.json("stop", NAME) == {"id": f"local:{NAME}", "state": "stopped"}
    assert host.json("start", NAME) == {"id": f"local:{NAME}", "state": "running"}
    assert host.json("delete", NAME) == {"id": f"local:{NAME}", "deleted": True}
    assert [c for c in host.calls() if c[:2] == ["spaces", "delete"]] == [["spaces", "delete", f"local:{NAME}", "--force"]]
    for command in ["stop", "start", "delete", "reset", "info", "netcheck"]:
        assert host.error(command, NAME)["error"] == "not_found", command

    host.add_space(NAME)
    host.state(delete_fail=True)
    assert host.error("delete", NAME)["error"] == "exec_failed"
    host.state(delete_fail=False)

    # reset: delete, then create under the same name with runc.
    mark = len(host.calls())
    assert host.json("reset", NAME) == {"id": f"local:{NAME}", "state": "running"}
    steps = [c[:2] for c in host.calls()[mark:] if c[:1] == ["spaces"]]
    assert steps == [["spaces", "delete"], ["spaces", "create"]]
    create = [c for c in host.calls() if c[:2] == ["spaces", "create"]][-1]
    assert create[create.index("--runtime") + 1] == "runc" and create[create.index("--name") + 1] == NAME
    check_no_token(host)


def test_netcheck(base):
    host = Host(base, netcheck="NETCHECK dns=1 https=1 fail=")
    host.add_space()
    assert host.json("netcheck", NAME) == {"dns": True, "https": True, "detail": None, "hint": None}

    host.state(netcheck="NETCHECK dns=0 https=0 fail= dns:www.cloudflare.com https:www.cloudflare.com dns:www.baidu.com https:www.baidu.com")
    blocked = host.json("netcheck", NAME)
    assert blocked["dns"] is False and blocked["https"] is False and blocked["hint"] is None
    assert "dns:www.baidu.com" in blocked["detail"]
    host.state(ufw=True)
    assert host.json("netcheck", NAME)["hint"] == "ufw"
    # Half-working networks are not blamed on the firewall's absence, and a healthy one never gets a hint.
    host.state(netcheck="NETCHECK dns=1 https=1 fail= https:www.cloudflare.com")
    healthy = host.json("netcheck", NAME)
    assert healthy["https"] is True and healthy["hint"] is None and healthy["detail"] == "https:www.cloudflare.com"
    assert not any(c[0] == "systemctl" for c in []), "ufw is never changed"
    assert all(a[:1] != ["enable"] for a in host.calls("systemctl"))
    mac = sub(base, "m", os_name="Darwin", with_docker=False, ufw=True,
               netcheck="NETCHECK dns=0 https=0 fail= dns:www.baidu.com")
    mac.add_space()
    assert mac.json("netcheck", NAME)["hint"] is None

    # A check that cannot finish in the Space (a hanging resolver) reads as offline, firewall hint included.
    host.state(netcheck=None)
    unfinished = host.json("netcheck", NAME)
    assert unfinished["dns"] is False and unfinished["https"] is False and unfinished["hint"] == "ufw"
    check_no_token(host)


def test_handoff_out(base):
    host = Host(base)
    host.add_space()
    box = host.space_home() / "交接"
    (box / "dir/sub").mkdir(parents=True)
    (box / "报告.txt").write_text("one")
    (box / "notes").write_text("n1")
    (box / ".env").write_text("dot")
    (box / "dir/a.tar.gz").write_text("a")
    (box / "dir/sub/b.txt").write_text("b")
    (box / "it's here.txt").write_text("quote")
    folder = host.folder

    assert host.handoff("out", {"folder": str(folder), "path": "报告.txt"}) == {"copied": ["报告.txt"]}
    assert (folder / "报告.txt").read_text() == "one"
    assert oct(folder.stat().st_mode & 0o777) == "0o700"
    # Never overwritten: the extension stays last.
    assert host.handoff("out", {"folder": str(folder), "path": "报告.txt"}) == {"copied": ["报告 (2).txt"]}
    assert host.handoff("out", {"folder": str(folder), "path": "报告.txt"}) == {"copied": ["报告 (3).txt"]}
    assert (folder / "报告.txt").read_text() == "one"
    host.handoff("out", {"folder": str(folder), "path": "notes"})
    assert host.handoff("out", {"folder": str(folder), "path": "notes"}) == {"copied": ["notes (2)"]}
    host.handoff("out", {"folder": str(folder), "path": ".env"})
    assert host.handoff("out", {"folder": str(folder), "path": ".env"}) == {"copied": [".env (2)"]}
    assert host.handoff("out", {"folder": str(folder), "path": "it's here.txt"}) == {"copied": ["it's here.txt"]}

    # A directory copies every file below it, merging into folders that exist.
    result = host.handoff("out", {"folder": str(folder), "path": "dir/"})
    assert sorted(result["copied"]) == ["dir/a.tar.gz", "dir/sub/b.txt"]
    again = host.handoff("out", {"folder": str(folder), "path": "dir"})
    assert sorted(again["copied"]) == ["dir/a (2).tar.gz", "dir/sub/b (2).txt"]
    assert (folder / "dir/sub/b.txt").read_text() == "b"

    for bad in ["../x", "dir/../../x", "/etc/passwd", "a//b", "./a", ".."]:
        err = host.handoff("out", {"folder": str(folder), "path": bad}, rc=1)
        assert err["error"] == "bad_input", bad
    assert host.handoff("out", {"folder": str(folder), "path": "missing.txt"}, rc=1)["error"] == "not_found"
    for payload in [{"path": "a"}, {"folder": "relative/dir", "path": "a"}, {"folder": str(folder) + "/../x", "path": "a"},
                    {"folder": str(folder)}, {"folder": str(folder), "path": 5}]:
        assert host.handoff("out", payload, rc=1)["error"] == "bad_input", payload
    assert host.run("handoff", "out", NAME, stdin="not json").returncode == 1
    assert json.loads(host.run("handoff", "out", NAME, stdin="not json").stdout)["error"] == "bad_input"
    assert not (host.base / "x").exists()
    check_no_token(host)


def test_handoff_json_strings(base):
    host = Host(base)
    host.add_space()
    box = host.space_home() / "交接"
    box.mkdir(parents=True)
    (box / 'say "hi".txt').write_text("q")
    odd = host.base / 'odd "q" \\ folder'
    assert host.handoff("out", {"folder": str(odd), "path": 'say "hi".txt'}) == {"copied": ['say "hi".txt']}
    assert (odd / 'say "hi".txt').read_text() == "q"
    # Raw JSON with escaped slash and spacing around colons; other escapes are refused.
    raw = '{ "path" : "say \\"hi\\".txt" , "folder" : "' + str(host.base).replace("/", "\\/") + '\\/esc" }'
    assert json.loads(host.run("handoff", "out", NAME, stdin=raw).stdout) == {"copied": ['say "hi".txt']}
    assert (host.base / "esc" / 'say "hi".txt').exists()
    err = json.loads(host.run("handoff", "out", NAME, stdin='{"folder":"/tmp/a\\nb","path":"x"}').stdout)
    assert err["error"] == "bad_input"


def test_handoff_in(base):
    host = Host(base)
    host.add_space()
    folder = host.folder
    folder.mkdir()
    (folder / "论文.pdf").write_text("p1")
    (folder / "sub").mkdir()
    (folder / "sub/x.txt").write_text("x1")
    (folder / ".DS_Store").write_text("junk")
    (folder / "link").symlink_to(folder / "论文.pdf")
    box = host.space_home() / "交接"

    first = host.handoff("in", {"folder": str(folder)})
    assert sorted(first["copied"]) == ["sub/x.txt", "论文.pdf"]
    assert (box / "论文.pdf").read_text() == "p1" and (box / "sub/x.txt").read_text() == "x1"
    assert not (box / ".DS_Store").exists() and not (box / "link").exists()
    state = host.home / ".miffan/space" / f"{NAME}.handoff"
    assert state.exists() and not state.with_suffix(".handoff.new").exists()
    assert oct(folder.stat().st_mode & 0o777) == oct(folder.stat().st_mode & 0o777)

    # Nothing new since the last run.
    assert host.handoff("in", {"folder": str(folder)}) == {"copied": []}
    # A newer file with the name of one already in the Space is renamed, never overwritten.
    changed = folder / "论文.pdf"
    changed.write_text("p2")
    later = time.time() + 30
    os.utime(changed, (later, later))
    (folder / "fresh").write_text("f")
    os.utime(folder / "fresh", (later, later))
    again = host.handoff("in", {"folder": str(folder)})
    assert sorted(again["copied"]) == ["fresh", "论文 (2).pdf"]
    assert (box / "论文.pdf").read_text() == "p1" and (box / "论文 (2).pdf").read_text() == "p2"

    # A missing host folder is created with mode 700.
    fresh = host.base / "brand/new"
    assert host.handoff("in", {"folder": str(fresh)}) == {"copied": []}
    assert oct(fresh.stat().st_mode & 0o777) == "0o700"
    assert host.handoff("in", {"folder": "relative"}, rc=1)["error"] == "bad_input"
    assert host.handoff("in", {"folder": str(folder) + "/../elsewhere"}, rc=1)["error"] == "bad_input"
    check_no_token(host)


def test_exec_failures(base):
    host = Host(base)
    host.add_space()
    host.state(spaces={NAME: {"state": "running", "port": 1}})
    # A host folder that cannot be created is an exec failure, not a crash.
    blocker = host.base / "blocker"
    blocker.write_text("file")
    err = host.handoff("in", {"folder": str(blocker / "inside")}, rc=1)
    assert err["error"] == "exec_failed"


def main():
    tests = [test_probe, test_install, test_create, test_create_errors, test_name_validation, test_info,
             test_lifecycle, test_netcheck, test_handoff_out, test_handoff_json_strings, test_handoff_in,
             test_exec_failures]
    for test in tests:
        with tempfile.TemporaryDirectory() as base:
            test(base)
        print(f"PASS: {test.__name__}")


if __name__ == "__main__":
    main()
