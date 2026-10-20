#!/usr/bin/env python3
"""Hermetic checks for `miffan task`; a temporary HOME and stubbed schedulers, no real timers."""
import base64
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import time

HELPER = Path(__file__).resolve().parents[1] / "src/miffan.sh"
SH = os.environ.get("HELPER_SH", "/bin/sh")  # CI runs dash; set HELPER_SH=/bin/dash to match it elsewhere.
TASK_ID = "0f7c2a4e-1b2c-4d5e-8f90-123456789abc"
MOCK = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
name=Path(sys.argv[0]).name
args=sys.argv[1:]
root=Path(os.environ['FIXTURE'])
f=root/'state.json'
s=json.loads(f.read_text()) if f.exists() else {}
with (root/'argv.jsonl').open('a') as log: log.write(json.dumps([name]+args)+'\n')
def save(): f.write_text(json.dumps(s))
if name=='uname': print(s.get('os','Linux') if args==['-s'] else 'x86_64')
elif name=='systemctl':
    if 'show-environment' in args: sys.exit(0 if s.get('systemd',True) else 1)
    if 'is-active' in args: sys.exit(0 if args[-1] in s.get('active',[]) else 3)
    if 'enable' in args or 'restart' in args:
        s.setdefault('active',[]); args[-1] in s['active'] or s['active'].append(args[-1]); save()
    if 'disable' in args: s['active']=[a for a in s.get('active',[]) if a!=args[-1]]; save()
    if 'start' in args: s.setdefault('started',[]).append(args[-1]); save()
elif name=='loginctl': print(s.get('linger','yes'))
elif name=='timedatectl': print('Asia/Shanghai')
elif name=='launchctl':
    if args[0]=='bootstrap':
        if s.get('no_gui'): sys.exit(5)
        s.setdefault('loaded',[]).append(args[2]); save()
    elif args[0]=='bootout': s['loaded']=[p for p in s.get('loaded',[]) if not p.endswith(args[1].rsplit('/',1)[-1]+'.plist')]; save()
    elif args[0]=='print': sys.exit(0 if any(p.endswith(args[1].rsplit('/',1)[-1]+'.plist') for p in s.get('loaded',[])) else 113)
elif name=='crontab':
    tab=root/'crontab'
    if args==['-l']:
        if not tab.exists(): print('no crontab for user',file=sys.stderr); sys.exit(1)
        sys.stdout.write(tab.read_text())
    elif args==['-']: tab.write_text(sys.stdin.read())
else: raise SystemExit('unexpected '+name)
'''


class Host:
    def __init__(self, base, os_name="Linux", **state):
        self.base = Path(base)
        self.home = self.base / "home"
        self.bin = self.base / "bin"
        self.home.mkdir()
        self.bin.mkdir()
        mock = self.bin / "mock"
        mock.write_text(MOCK)
        mock.chmod(0o755)
        for name in ["uname", "systemctl", "loginctl", "timedatectl", "launchctl", "crontab"]:
            (self.bin / name).symlink_to(mock)
        self.state(os=os_name, **state)

    def state(self, **values):
        path = self.base / "state.json"
        current = json.loads(path.read_text()) if path.exists() else {}
        current.update(values)
        path.write_text(json.dumps(current))

    def read_state(self):
        return json.loads((self.base / "state.json").read_text())

    def calls(self, name):
        lines = (self.base / "argv.jsonl").read_text().splitlines() if (self.base / "argv.jsonl").exists() else []
        return [call[1:] for call in map(json.loads, lines) if call[0] == name]

    def run(self, *args, stdin="", extra_env=None):
        env = {
            "HOME": str(self.home),
            "PATH": f"{self.bin}:/usr/bin:/bin:/usr/sbin:/sbin",
            "FIXTURE": str(self.base),
            "SHELL": "/bin/sh",
            "XDG_RUNTIME_DIR": str(self.base / "run"),
            "DBUS_SESSION_BUS_ADDRESS": "unix:path=/dev/null",
        }
        env.update(extra_env or {})
        return subprocess.run([SH, str(HELPER), "task", *args], input=stdin, text=True,
                              capture_output=True, env=env, timeout=60)

    def json(self, *args, stdin="", ok=True):
        result = self.run(*args, stdin=stdin)
        assert result.stdout.strip(), (args, result.stderr)
        data = json.loads(result.stdout.strip().splitlines()[-1])
        if ok:
            assert result.returncode == 0 and data.get("success", True), (args, result.stdout, result.stderr)
        return data

    def create(self, schedule=("daily", "09:00"), run="echo hello\n", prompt="", meta='{"name":"t"}', timeout="600", task_id=TASK_ID, ok=True, env=""):
        for part, content in [("meta", meta), ("run", run), ("prompt", prompt), ("env", env)]:
            self.json("stage", task_id, part, stdin=content)
        return self.json("commit", task_id, timeout, *schedule, ok=ok)

    def task_dir(self, task_id=TASK_ID):
        return self.home / ".miffan/tasks" / task_id


def runs(host, task_id=TASK_ID):
    return [json.loads(p.read_text()) for p in sorted((host.task_dir(task_id) / "runs").glob("*.json"))]


def test_systemd_lifecycle(base):
    host = Host(base)
    probe = host.json("probe")
    assert probe["scheduler"] == "systemd" and probe["linger"] is True and probe["login_required"] is False
    created = host.create(("weekly", "0,3,5", "07:30"))
    assert created == {"success": True, "backend": "systemd", "enabled": True}
    units = host.home / ".config/systemd/user"
    timer = (units / f"miffan-task-{TASK_ID}.timer").read_text()
    assert "OnCalendar=Sun,Wed,Fri *-*-* 07:30:00" in timer and "Persistent=true" in timer and "AccuracySec=1s" in timer
    service = (units / f"miffan-task-{TASK_ID}.service").read_text()
    assert f'ExecStart=/bin/sh "%h/.miffan/bin/miffan-task-runner" task exec {TASK_ID}' in service
    runner = host.home / ".miffan/bin/miffan-task-runner"
    assert runner.read_bytes() == HELPER.read_bytes()
    assert not (host.home / ".miffan/tasks/.staging" / TASK_ID).exists()
    assert oct(host.task_dir().stat().st_mode & 0o777) == "0o700"
    assert oct((host.task_dir() / "run").stat().st_mode & 0o777) == "0o600"

    listed = host.json("list")["tasks"]
    assert len(listed) == 1 and listed[0]["enabled"] and listed[0]["timer_ok"]
    assert base64.b64decode(listed[0]["meta_b64"]) == b'{"name":"t"}' and listed[0]["last_run"] is None

    host.json("disable", TASK_ID)
    assert not host.json("list")["tasks"][0]["timer_ok"]
    host.json("enable", TASK_ID)
    assert host.json("list")["tasks"][0]["timer_ok"]

    # Updating keeps run history and the enabled state.
    host.run("exec", TASK_ID)
    host.json("disable", TASK_ID)
    host.create(("interval", "30"), run="echo updated\n")
    assert len(runs(host)) == 1 and not (host.task_dir() / "enabled").exists()
    assert not host.json("list")["tasks"][0]["timer_ok"]
    host.json("enable", TASK_ID)
    assert "OnUnitActiveSec=30min" in (units / f"miffan-task-{TASK_ID}.timer").read_text()

    host.json("run", TASK_ID)
    assert host.read_state()["started"] == [f"miffan-task-{TASK_ID}.service"]

    host.json("remove", TASK_ID)
    assert not host.task_dir().exists() and not list(units.glob("miffan-task-*")) and not runner.exists()


def test_validation(base):
    host = Host(base)
    for schedule in [("daily", "24:00"), ("daily", "9:00"), ("weekly", "35", "09:00"), ("weekly", "1,1", "09:00"),
                     ("weekly", "7", "09:00"), ("weekly", ",1", "09:00"), ("interval", "45"), ("hourly",), ("daily",)]:
        result = host.create(schedule, ok=False)
        assert result["success"] is False and result["detail"] == "invalid schedule", schedule
    assert host.create(timeout="30", ok=False)["detail"] == "invalid timeout"
    assert host.json("stage", "../escape", "run", stdin="x", ok=False)["detail"] == "invalid task id"
    assert host.json("stage", TASK_ID, "other", stdin="x", ok=False)["detail"] == "invalid part"
    assert host.json("stage", TASK_ID, "prompt", stdin="x" * 262145, ok=False)["detail"] == "task file too large"
    host.json("stage", TASK_ID, "meta", stdin="{}")
    host.json("stage", TASK_ID, "run", stdin="true")
    host.json("stage", TASK_ID, "env", stdin="PATH\n")
    missing = host.json("commit", TASK_ID, "600", "daily", "09:00", ok=False)
    assert missing["detail"] == "missing staged prompt" and not host.task_dir().exists()
    for names in ["1BAD\n", "OK\nBAD-NAME\n", "A B\n", "X;rm\n", "\n".join(f"V{i}" for i in range(65))]:
        assert host.json("stage", TASK_ID, "env", stdin=names, ok=False)["detail"] in ("invalid variable name", "too many variables"), names
    assert host.json("commit", TASK_ID, "600", ok=False)["detail"] == "missing schedule"
    assert host.json("log", TASK_ID, "../../x", ok=False)["detail"] == "invalid run id"
    assert host.json("sync", "x;y", ok=False)["detail"] == "invalid cursor"


def test_exec_records(base):
    host = Host(base)
    host.create(run='cat; printf " from run\\n"\n', prompt="prompt text")
    assert host.run("exec", TASK_ID).returncode == 0
    record = runs(host)[0]
    assert record["status"] == "ok" and record["exit_code"] == 0 and record["truncated"] is False
    log = (host.task_dir() / "runs" / f"{record['run_id']}.log").read_text()
    assert log == "prompt text from run\n" and record["log_bytes"] == len(log)

    host.create(run="echo bad >&2; exit 3\n")
    host.run("exec", TASK_ID)
    assert runs(host)[-1]["status"] == "failed" and runs(host)[-1]["exit_code"] == 3

    host.create(run="head -c 1200000 /dev/zero | tr '\\0' a\n")
    host.run("exec", TASK_ID)
    big = runs(host)[-1]
    assert big["truncated"] is True and big["log_bytes"] == 1200000
    assert (host.task_dir() / "runs" / f"{big['run_id']}.log").stat().st_size == 1048576

    # Timeout kills the whole tree; the helper's own floor is 60 s, so shorten it in place.
    host.create(run="sleep 30 & wait\n")
    (host.task_dir() / "timeout").write_text("1\n")
    started = time.time()
    host.run("exec", TASK_ID)
    assert time.time() - started < 15 and runs(host)[-1]["status"] == "timeout"
    assert not (host.task_dir() / "lock").exists()


def test_overlap_and_pruning(base):
    host = Host(base)
    host.create(run="true\n")
    lock = host.task_dir() / "lock"
    lock.mkdir()
    (lock / "pid").write_text(f"{os.getpid()}\n")
    host.run("exec", TASK_ID)
    assert runs(host)[-1]["status"] == "skipped_overlap" and lock.exists()
    # A lock left by a process that no longer exists is taken over.
    dead = subprocess.Popen(["true"])
    dead.wait()
    (lock / "pid").write_text(f"{dead.pid}\n")
    host.run("exec", TASK_ID)
    assert runs(host)[-1]["status"] == "ok" and not lock.exists()
    for _ in range(31):
        host.run("exec", TASK_ID)
    assert len(runs(host)) == 30
    assert len(list((host.task_dir() / "runs").glob("*.log"))) == 30


def test_sync(base):
    host = Host(base)
    host.create(run="echo first\n")
    host.run("exec", TASK_ID)
    first = host.json("sync")
    assert len(first["runs"]) == 1 and first["cursor"] == first["runs"][0]["run_id"]
    assert base64.b64decode(first["runs"][0]["excerpt_b64"]) == b"first\n"
    assert host.json("sync", first["cursor"])["runs"] == []
    time.sleep(1.1)
    host.create(run="printf '\"quoted\" \\\\ line\\n'\n")
    host.run("exec", TASK_ID)
    second = host.json("sync", first["cursor"])
    assert len(second["runs"]) == 1 and second["cursor"] > first["cursor"]
    assert base64.b64decode(second["runs"][0]["excerpt_b64"]) == b'"quoted" \\ line\n'
    run_id = second["runs"][0]["run_id"]
    assert host.run("log", TASK_ID, run_id).stdout == '"quoted" \\ line\n'


def test_cron_backend(base):
    host = Host(base, systemd=False)
    (host.base / "crontab").write_text("0 1 * * * backup.sh\n")
    assert host.json("probe")["scheduler"] == "cron"
    host.create(("interval", "120"))
    other = "0f7c2a4e-0000-4d5e-8f90-123456789abc"
    host.create(("weekly", "1,2", "18:05"), task_id=other)
    tab = (host.base / "crontab").read_text().splitlines()
    assert tab[0] == "0 1 * * * backup.sh"
    assert f'0 */2 * * * /bin/sh "{host.home}/.miffan/bin/miffan-task-runner" task exec {TASK_ID} # miffan-task:{TASK_ID}' in tab
    assert f'5 18 * * 1,2 /bin/sh "{host.home}/.miffan/bin/miffan-task-runner" task exec {other} # miffan-task:{other}' in tab
    host.json("disable", TASK_ID)
    assert not any(TASK_ID in line for line in (host.base / "crontab").read_text().splitlines())
    host.json("remove", "--all")
    assert (host.base / "crontab").read_text() == "0 1 * * * backup.sh\n"
    assert not list((host.home / ".miffan/tasks").iterdir()) and not (host.home / ".miffan/bin/miffan-task-runner").exists()


def test_launchd_backend(base):
    host = Host(base, os_name="Darwin")
    probe = host.json("probe")
    assert probe["scheduler"] == "launchd" and probe["login_required"] is True
    host.create(("weekly", "0,6", "08:05"))
    plist_path = host.home / f"Library/LaunchAgents/me.ayuilos.miffan.task.{TASK_ID}.plist"
    plist = plistlib.loads(plist_path.read_bytes())
    assert plist["Label"] == f"me.ayuilos.miffan.task.{TASK_ID}"
    assert plist["ProgramArguments"] == ["/bin/sh", f"{host.home}/.miffan/bin/miffan-task-runner", "task", "exec", TASK_ID]
    assert plist["StartCalendarInterval"] == [{"Weekday": 0, "Hour": 8, "Minute": 5}, {"Weekday": 6, "Hour": 8, "Minute": 5}]
    host.create(("interval", "15"))
    assert plistlib.loads(plist_path.read_bytes())["StartInterval"] == 900
    assert host.json("list")["tasks"][0]["timer_ok"]

    host.json("run", TASK_ID)
    now = plistlib.loads((host.task_dir() / "now.plist").read_bytes())
    assert now["RunAtLoad"] is True and "StartInterval" not in now

    host.json("disable", TASK_ID)
    assert not plist_path.exists()
    host.json("remove", TASK_ID)
    assert not host.task_dir().exists()

    # Without a login session launchd refuses the job; a new task is rolled back entirely.
    host.state(no_gui=True)
    failed = host.create(ok=False)
    assert failed["detail"] == "login_required" and not host.task_dir().exists() and not plist_path.exists()


def test_login_env(base):
    host = Host(base)
    # Interactive sh sources $ENV, standing in for ~/.zshrc: banner noise, a proxy, a multi-line
    # value, and a secret the task did not ask for.
    rc = host.base / "rc"
    rc.write_text("echo 'welcome banner'\nexport https_proxy='http://127.0.0.1:7890'\n"
                  "export MULTI='a\nb'\nexport SECRET=hidden\nexport EMPTY=\n")
    run = 'printf "%s|%s|%s|%s|%s\\n" "${https_proxy-unset}" "$MULTI" "${SECRET-unset}" "${EMPTY-unset}" "${MISSING-unset}"; echo "$MIFFAN_LOGIN_ENV"\n'
    host.create(run=run, env="https_proxy\nMULTI\nEMPTY\nMISSING\n")
    host.run("exec", TASK_ID, extra_env={"ENV": str(rc)})
    record = runs(host)[-1]
    log = (host.task_dir() / "runs" / f"{record['run_id']}.log").read_text()
    assert log == "http://127.0.0.1:7890|a\nb|unset||unset\nok\n", log
    # Values are read at run time and never written into the task.
    assert "7890" not in "".join(p.read_text() for p in host.task_dir().iterdir() if p.is_file())
    # A login shell that prints nothing leaves the timer environment alone.
    host.run("exec", TASK_ID, extra_env={"SHELL": "/usr/bin/false"})
    record = runs(host)[-1]
    log = (host.task_dir() / "runs" / f"{record['run_id']}.log").read_text()
    assert log == "unset||unset|unset|unset\n\n", log


def test_which(base):
    host = Host(base)
    tools = host.base / "tools"
    tools.mkdir()
    tool = tools / "agent-cli"
    tool.write_text("#!/bin/sh\n[ \"$1\" = --version ] && { echo; echo 'agent-cli 1.2.3'; echo extra; }\n")
    tool.chmod(0o755)
    # Interactive sh sources $ENV: noisy startup output and a PATH only the login shell has.
    rc = host.base / "rc"
    rc.write_text(f"echo 'welcome banner'\nPATH=\"{tools}:$PATH\"\nalias fake=true\n")
    env = {"ENV": str(rc)}
    found = json.loads(host.run("which", "agent-cli", "--version", extra_env=env).stdout)
    assert found["path"] == str(tool) and found["version"] == "agent-cli 1.2.3"
    assert found["path_env"].startswith(f"{tools}:")
    assert json.loads(host.run("which", "fake", extra_env=env).stdout)["path"] is None
    assert json.loads(host.run("which", "missing-cli", extra_env=env).stdout)["path"] is None
    absolute = json.loads(host.run("which", str(tool)).stdout)
    assert absolute["path"] == str(tool) and absolute["version"] is None
    bad = host.run("which", "agent-cli", "--version;rm")
    assert json.loads(bad.stdout)["detail"] == "invalid version argument"


def main():
    tests = [test_systemd_lifecycle, test_validation, test_exec_records, test_overlap_and_pruning, test_sync,
             test_cron_backend, test_launchd_backend, test_login_env, test_which]
    for test in tests:
        with tempfile.TemporaryDirectory() as base:
            test(base)
        print(f"PASS: {test.__name__}")


if __name__ == "__main__":
    main()
