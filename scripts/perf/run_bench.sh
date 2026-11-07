#!/usr/bin/env bash
# Builds the macrobenchmark variant, runs the IM benchmarks on a device and prints a compact table.
#
#   scripts/perf/run_bench.sh [serial] [options]
#
#   serial                 adb serial (default: $ANDROID_SERIAL, else the only connected device)
#   --tests A,B            only these tests of ImBenchmarks (startupCold, startupWarm, chatsScroll,
#                          threadOpenAndScroll, tabSwitch, streaming) or "startup" for StartupBenchmarks
#   --compilation MODE     partial (default; uses the app's baseline profile), none or full
#   --iterations N         override the iteration count of every test
#   --frames MODE          both (default on a phone), timeline (FrameTimingMetric) or gfx (dumpsys gfxinfo only).
#                          The emulator renders in software and often records no frame-timeline slices, which makes
#                          FrameTimingMetric abort the test, so emulator serials default to gfx.
#   --no-llm               do not start scripts/perf/fake_llm.py (the streaming test then fails)
#
# The tests load the performance fixture themselves (docs/development/PERF_FIXTURE.md). On a phone the
# fake model is reached through `adb reverse tcp:18799 tcp:18799`, which this script sets up; the emulator
# reaches the host's loopback through its global HTTP proxy. Results: the table below and the JSON under
# app/baselineprofile/build/outputs/connected_android_test_additional_output/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SERIAL="${ANDROID_SERIAL:-}"
TESTS=""
COMPILATION="partial"
ITERATIONS=""
FRAMES=""
START_LLM=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tests) TESTS="$2"; shift 2 ;;
    --compilation) COMPILATION="$2"; shift 2 ;;
    --iterations) ITERATIONS="$2"; shift 2 ;;
    --frames) FRAMES="$2"; shift 2 ;;
    --no-llm) START_LLM=0; shift ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) SERIAL="$1"; shift ;;
  esac
done
if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -2)"
  [[ "$(wc -w <<<"$SERIAL")" == 1 ]] || { echo "pass a serial; connected: $(adb devices | awk 'NR>1 && $2=="device" {print $1}' | tr '\n' ' ')" >&2; exit 2; }
fi
export ANDROID_SERIAL="$SERIAL"
adb -s "$SERIAL" get-state >/dev/null

LLM_PID=""
cleanup() { [[ -n "$LLM_PID" ]] && kill "$LLM_PID" 2>/dev/null || true; }
trap cleanup EXIT
if (( START_LLM )); then
  if ! (exec 3<>/dev/tcp/127.0.0.1/18799) 2>/dev/null; then
    python3 "$ROOT/scripts/perf/fake_llm.py" 2>"${TMPDIR:-/tmp}/fake_llm.log" &
    LLM_PID=$!
    sleep 1
  fi
  if [[ "$SERIAL" != emulator-* ]]; then
    adb -s "$SERIAL" reverse tcp:18799 tcp:18799 >/dev/null
  fi
fi

if [[ -z "$FRAMES" ]]; then
  if [[ "$SERIAL" == emulator-* ]]; then FRAMES="gfx"; else FRAMES="both"; fi
fi
ARGS=("-Pandroid.testInstrumentationRunnerArguments.compilation=$COMPILATION"
      "-Pandroid.testInstrumentationRunnerArguments.frames=$FRAMES")
if [[ -n "$ITERATIONS" ]]; then
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.iterations=$ITERATIONS")
fi
case "$TESTS" in
  "") ;;
  startup) ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.baselineprofile.StartupBenchmarks") ;;
  *) CLASSES=""
     IFS=',' read -ra NAMES <<<"$TESTS"
     for name in "${NAMES[@]}"; do CLASSES+="me.rerere.baselineprofile.ImBenchmarks#$name,"; done
     ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=${CLASSES%,}") ;;
esac

OUT="$ROOT/app/baselineprofile/build/outputs/connected_android_test_additional_output"
rm -rf "$OUT/benchmarkRelease"
cd "$ROOT"
./gradlew :app:baselineprofile:connectedBenchmarkReleaseAndroidTest "${ARGS[@]}"

echo
python3 "$ROOT/scripts/perf/summarize.py" "$OUT/benchmarkRelease"
