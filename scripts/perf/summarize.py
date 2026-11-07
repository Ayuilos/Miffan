#!/usr/bin/env python3
"""Print a compact table from androidx.benchmark macrobenchmark JSON output (Python 3 standard library only).

  python3 scripts/perf/summarize.py <file-or-directory> [more ...]

Columns (medians over the iterations unless noted):
  ttid / ttfd   time to initial / full display, ms (StartupTimingMetric)
  open          ThreadOpen trace slice: tapping a chat to its messages being loaded, ms
  p50 p90 p99   frame CPU time over all frames of all iterations, ms (FrameTimingMetric, frameDurationCpuMs);
                gfxinfo frame time percentiles when only the gfx metric ran
  jank%         frames that missed their deadline (frameOverrunMs > 0), over all frames of all iterations
  gfx%          jank percentage from dumpsys gfxinfo (FrameTimingGfxInfoMetric), median over the iterations
  frames        frame count over all iterations
"""
import glob
import json
import os
import statistics
import sys


def files(args):
    for arg in args:
        if os.path.isdir(arg):
            yield from sorted(glob.glob(os.path.join(arg, "**", "*.json"), recursive=True))
        else:
            yield arg


def median(metrics, *names):
    for name in names:
        value = metrics.get(name)
        if value:
            return value.get("median")
    return None


def fmt(value, digits=1):
    return "-" if value is None else f"{value:.{digits}f}"


def row(benchmark):
    metrics = benchmark.get("metrics", {})
    sampled = benchmark.get("sampledMetrics", {})
    cpu = sampled.get("frameDurationCpuMs", {})
    # Without FrameTimingMetric (emulator runs) the gfxinfo percentiles stand in; they come in 5 ms steps.
    gfx = {key: median(metrics, f"gfxFrameTime{n}thPercentileMs") for key, n in (("P50", 50), ("P90", 90), ("P99", 99))}
    overrun = sampled.get("frameOverrunMs", {})
    runs = [value for run in overrun.get("runs", []) for value in run]
    jank = 100.0 * sum(1 for value in runs if value > 0) / len(runs) if runs else None
    frames = sum(len(run) for run in cpu.get("runs", [])) or median(metrics, "gfxFrameTotalCount")
    iterations = benchmark.get("repeatIterations") or len(next(iter(metrics.values()), {}).get("runs", [])) or 0
    return [
        benchmark["name"],
        str(iterations),
        fmt(median(metrics, "timeToInitialDisplayMs")),
        fmt(median(metrics, "timeToFullDisplayMs")),
        fmt(median(metrics, "ThreadOpenFirstMs", "ThreadOpenMs")),
        fmt(cpu.get("P50", gfx["P50"])),
        fmt(cpu.get("P90", gfx["P90"])),
        fmt(cpu.get("P99", gfx["P99"])),
        fmt(jank),
        fmt(median(metrics, "gfxFrameJankPercent")),
        "-" if frames is None else str(int(frames)),
    ]


def main():
    header = ["test", "n", "ttid", "ttfd", "open", "p50", "p90", "p99", "jank%", "gfx%", "frames"]
    rows = []
    context = None
    for path in files(sys.argv[1:] or ["."]):
        try:
            data = json.load(open(path))
        except (OSError, ValueError):
            continue
        for benchmark in data.get("benchmarks", []):
            rows.append(row(benchmark))
        context = context or data.get("context")
    if not rows:
        print("no benchmark results found", file=sys.stderr)
        return 1
    rows.sort(key=lambda r: r[0])
    widths = [max(len(r[i]) for r in [header] + rows) for i in range(len(header))]
    if context:
        build = context.get("build", {})
        print(f"device: {build.get('brand', '?')} {build.get('model', '?')} (API {build.get('version', {}).get('sdk', '?')})"
              f"{'  [emulator: frame numbers are not meaningful]' if 'sdk_' in str(build.get('model', '')).lower() or 'emulator' in str(build.get('device', '')).lower() or str(build.get('device', '')).startswith('emu') else ''}")
    line = lambda r: "  ".join(c.ljust(widths[i]) if i == 0 else c.rjust(widths[i]) for i, c in enumerate(r))
    print(line(header))
    print("  ".join("-" * w for w in widths))
    for r in rows:
        print(line(r))
    return 0


if __name__ == "__main__":
    sys.exit(main())
