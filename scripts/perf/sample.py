#!/usr/bin/env python3
"""Samples where the .bench app spends CPU in one scenario, with adb and simpleperf. Python 3 standard library only.

  scripts/perf/sample.py <serial> fixture [--recent]
  scripts/perf/sample.py <serial> stream|open|tabs|cold <out.txt> [--seconds N]
  scripts/perf/sample.py report <out.txt> [thread-substring [symbol-regex]]

Macrobenchmark answers "how many frames were late"; this answers "which code ran". It drives the benchmark app
(install it first: `./gradlew :app:assembleBenchmarkRelease`, then `adb install` the arm64 APK) by the same
resource ids the benchmarks use, records `simpleperf` samples with call stacks at 2000 Hz, and pulls them as text.
`report` prints the samples per thread, or the functions of one thread by inclusive samples.

Thread CPU and sample shares compare well between two builds on the same device, on an emulator too (its frame
times do not). Run a scenario two or three times: one run varies by about a tenth.

  fixture   loads the performance fixture (docs/development/PERF_FIXTURE.md). With --recent the newest messages
            are minutes old, so a thread opens on its messages instead of the welcome.
  stream    opens the main thread, sends a message and samples the first N seconds (default 15) of the reply.
            Start a model first: `python3 scripts/perf/fake_llm.py --rate 150 --tokens 3000`.
  open      opens the main thread and flings back through its history six times.
  tabs      visits the four tabs four times.
  cold      a cold start into the Chats tab.

Symbols of a release build are what R8 left: merged lambdas often carry the name of an unrelated class.
"""
import collections
import re
import subprocess
import sys
import time

PKG = "me.ayuilos.miffan.app.bench"
MAIN = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
SERIAL = ""


def adb(*args, check=True, timeout=300):
    return subprocess.run(["adb", "-s", SERIAL, *args], check=check, capture_output=True, text=True, timeout=timeout).stdout


def sh(command, **kw):
    return adb("shell", command, **kw)


def dump():
    for _ in range(4):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        if "<hierarchy" in out:
            return out
        time.sleep(0.4)
    return ""


def find(res):
    m = re.search(r'resource-id="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(res), dump())
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2, (x1, y1, x2, y2)


def wait(res, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        hit = find(res)
        if hit:
            return hit
        time.sleep(0.3)
    raise SystemExit(f"{res} did not appear")


def gone(res, timeout=240):
    end = time.time() + timeout
    while time.time() < end:
        if not find(res):
            return
        time.sleep(0.5)
    raise SystemExit(f"{res} did not go away")


def tap(res, timeout=20):
    x, y, _ = wait(res, timeout)
    sh(f"input tap {x} {y}")


def fixture():
    extra = f" --el anchor {int(time.time() * 1000)}" if "--recent" in sys.argv else ""
    out = sh(f"am broadcast -a me.ayuilos.miffan.PERF_FIXTURE -n {PKG}/me.ayuilos.miffan.perf.PerfFixtureReceiver{extra}")
    print(out.strip().splitlines()[-1])
    sh(f"am force-stop {PKG}")


def start():
    sh(f"am force-stop {PKG}")
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    wait(f"im_chat_row_{MAIN}", 30)
    time.sleep(1.5)


def open_thread():
    tap(f"im_chat_row_{MAIN}")
    wait("thread_list")
    time.sleep(1.0)
    if find("thread_show_history"):
        tap("thread_show_history")
        time.sleep(1.0)


class Sampling:
    def __init__(self, out, seconds):
        self.out, self.seconds = out, seconds

    def __enter__(self):
        self.process = subprocess.Popen(
            ["adb", "-s", SERIAL, "shell", f"simpleperf record --app {PKG} -e cpu-clock -f 2000 -g "
             f"--duration {int(self.seconds)} -o /data/local/tmp/perf.data"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1.5)
        return self

    def __exit__(self, *exc):
        if exc[0] is not None:
            return
        sh("pkill -INT simpleperf", check=False)
        try:
            self.process.communicate(timeout=60)
        except subprocess.TimeoutExpired:
            self.process.kill()
        sh("simpleperf report-sample --show-callchain -i /data/local/tmp/perf.data -o /data/local/tmp/perf.txt "
           ">/dev/null 2>&1", timeout=900)
        adb("pull", "/data/local/tmp/perf.txt", self.out)
        sh("rm -f /data/local/tmp/perf.data /data/local/tmp/perf.txt", check=False)
        print("samples in", self.out)


def scenario(name, out, seconds):
    if name == "stream":
        start()
        open_thread()
        tap("thread_input")
        time.sleep(0.6)
        sh("input text hello")
        time.sleep(0.5)
        tap("thread_send")
        wait("thread_stop", 20)
        with Sampling(out, seconds):
            time.sleep(seconds)
        gone("thread_stop")
    elif name == "open":
        start()
        with Sampling(out, 40):
            open_thread()
            x, _, bounds = wait("thread_list")
            for _ in range(6):
                sh(f"input swipe {x} {bounds[1] + 300} {x} {bounds[3] - 300} 60")
                time.sleep(1.2)
    elif name == "tabs":
        start()
        tabs = {tab: wait(f"im_tab_{tab}")[:2] for tab in ("partners", "discover", "me", "chats")}
        with Sampling(out, 40):
            for _ in range(4):
                for tab in ("partners", "discover", "me", "chats"):
                    sh("input tap %d %d" % tabs[tab])
                    time.sleep(0.6)
    elif name == "cold":
        sh(f"am force-stop {PKG}")
        with Sampling(out, 20):
            sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
            wait(f"im_chat_row_{MAIN}", 30)
            time.sleep(3)
    else:
        raise SystemExit(__doc__)
    sh(f"am force-stop {PKG}")


def report(path, thread=None, pattern=None):
    samples = []
    current = None
    with open(path, errors="replace") as lines:
        for line in lines:
            text = line.strip()
            if line.startswith("sample:"):
                current = {"thread": "", "stack": []}
                samples.append(current)
            elif current is None:
                continue
            elif text.startswith("thread_name:"):
                current["thread"] = text.split(":", 1)[1].strip()
            elif text.startswith("symbol:"):
                current["stack"].append(text.split(":", 1)[1].strip())
    if thread is None:
        for name, count in collections.Counter(s["thread"] for s in samples).most_common(16):
            print(f"{count:7d}  {name}")
        return
    chosen = [s for s in samples if thread in s["thread"]]
    matcher = re.compile(pattern) if pattern else None
    inclusive = collections.Counter()
    for sample in chosen:
        for symbol in set(sample["stack"]):
            if matcher is None or matcher.search(symbol):
                inclusive[symbol] += 1
    print(f"{len(chosen)} samples on threads named *{thread}*; inclusive per function:")
    for symbol, count in inclusive.most_common(60):
        print(f"{count:7d} {count / max(len(chosen), 1) * 100:5.1f}%  {symbol[:160]}")


def main():
    global SERIAL
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) >= 2 and args[0] == "report":
        return report(*args[1:4])
    if len(args) < 2:
        raise SystemExit(__doc__)
    SERIAL = args[0]
    if args[1] == "fixture":
        return fixture()
    if len(args) < 3:
        raise SystemExit(__doc__)
    seconds = 15.0
    if "--seconds" in sys.argv:
        seconds = float(sys.argv[sys.argv.index("--seconds") + 1])
        args = [a for a in args if a != sys.argv[sys.argv.index("--seconds") + 1]]
    scenario(args[1], args[2], seconds)


if __name__ == "__main__":
    main()
