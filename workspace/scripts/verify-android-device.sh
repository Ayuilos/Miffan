#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_dir" && -f "$repo_dir/local.properties" ]]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$repo_dir/local.properties" | head -1 | sed 's/\\:/:/g; s/\\\\/\\/g')"
fi
if [[ -z "$sdk_dir" ]]; then
  echo "ANDROID_SDK_ROOT/ANDROID_HOME or local.properties sdk.dir is required" >&2
  exit 2
fi

adb_bin="$sdk_dir/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then
  echo "adb not found: $adb_bin" >&2
  exit 2
fi

serial="${ANDROID_SERIAL:-}"
expected_abi=""
skip_build=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="${2:-}"
      shift 2
      ;;
    --expected-abi)
      expected_abi="${2:-}"
      shift 2
      ;;
    --skip-build)
      skip_build=1
      shift
      ;;
    *)
      echo "Usage: $0 [--serial SERIAL] [--expected-abi arm64-v8a|x86_64] [--skip-build]" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$serial" ]]; then
  devices="$("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  device_count="$(printf '%s\n' "$devices" | awk 'NF { count++ } END { print count + 0 }')"
  if [[ "$device_count" -ne 1 ]]; then
    echo "Exactly one ready device is required, or pass --serial" >&2
    exit 2
  fi
  serial="$devices"
fi

adb=("$adb_bin" -s "$serial")
device_state="$("${adb[@]}" get-state 2>/dev/null || true)"
if [[ "$device_state" != "device" ]]; then
  echo "Device is not ready: $serial ($device_state)" >&2
  exit 2
fi

abi="$("${adb[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
api="$("${adb[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
model="$("${adb[@]}" shell getprop ro.product.model | tr -d '\r')"
fingerprint="$("${adb[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
case "$abi" in
  arm64-v8a|x86_64) ;;
  *)
    echo "Unsupported verification ABI: $abi" >&2
    exit 2
    ;;
esac
if [[ -n "$expected_abi" && "$abi" != "$expected_abi" ]]; then
  echo "ABI mismatch: expected $expected_abi, device reports $abi" >&2
  exit 2
fi
if [[ ! "$api" =~ ^[0-9]+$ || "$api" -lt 26 ]]; then
  echo "Android API 26 or newer is required; device reports '$api'" >&2
  exit 2
fi

init_script=""
cleanup() {
  if [[ -n "$init_script" ]]; then rm -f "$init_script"; fi
}
trap cleanup EXIT

if [[ "$skip_build" -eq 0 ]]; then
  ndk_version="${WORKSPACE_NDK_VERSION:-}"
  if [[ -z "$ndk_version" ]]; then
    while IFS= read -r candidate; do
      ndk_version="$(basename "$(dirname "$candidate")")"
    done < <(find "$sdk_dir/ndk" -mindepth 2 -maxdepth 2 -name source.properties -print 2>/dev/null | sort -V)
  fi
  if [[ -z "$ndk_version" || ! -f "$sdk_dir/ndk/$ndk_version/source.properties" ]]; then
    echo "No complete Android NDK installation was found (source.properties is required)" >&2
    exit 2
  fi
  init_script="$(mktemp "${TMPDIR:-/tmp}/rikkahub-workspace-ndk.XXXXXX")"
  printf '%s\n' \
    "allprojects {" \
    "  plugins.withId('com.android.application') { android.ndkVersion = '$ndk_version' }" \
    "  plugins.withId('com.android.library') { android.ndkVersion = '$ndk_version' }" \
    "}" > "$init_script"
  gradle_args=(
    -I "$init_script"
    :workspace:assembleDebugAndroidTest
    --no-configuration-cache
    --console=plain
  )
  if [[ "${WORKSPACE_VERIFY_OFFLINE:-0}" == "1" ]]; then gradle_args+=(--offline); fi
  (cd "$repo_dir" && ./gradlew "${gradle_args[@]}")
fi

apk="$repo_dir/workspace/build/outputs/apk/androidTest/debug/workspace-debug-androidTest.apk"
if [[ ! -f "$apk" ]]; then
  echo "Workspace instrumentation APK is missing: $apk" >&2
  exit 2
fi

report_dir="$repo_dir/workspace/build/reports/android-device"
mkdir -p "$report_dir"
safe_serial="${serial//[^A-Za-z0-9_.-]/_}"
report="$report_dir/${safe_serial}-${abi}.txt"
raw_report="$(mktemp "${TMPDIR:-/tmp}/rikkahub-workspace-instrumentation.XXXXXX")"
trap 'rm -f "$raw_report"; cleanup' EXIT

"${adb[@]}" install -r "$apk"
rootfs_archive="${WORKSPACE_VERIFY_ROOTFS_ARCHIVE:-}"
rootfs_provisioned=0
if [[ -n "$rootfs_archive" ]]; then
  if [[ ! -f "$rootfs_archive" ]]; then
    echo "Provisioned Rootfs archive is missing: $rootfs_archive" >&2
    exit 2
  fi
  device_archive="/data/local/tmp/rikkahub-workspace-rootfs-${serial//[^A-Za-z0-9_.-]/_}.tar.gz"
  "${adb[@]}" push "$rootfs_archive" "$device_archive"
  "${adb[@]}" shell run-as me.rerere.workspace.test \
    cp "$device_archive" cache/provisioned-rootfs.tar.gz
  "${adb[@]}" shell rm -f "$device_archive"
  rootfs_provisioned=1
fi
set +e
"${adb[@]}" shell am instrument -w -r \
  me.rerere.workspace.test/androidx.test.runner.AndroidJUnitRunner > "$raw_report" 2>&1
instrument_status=$?
set -e

{
  echo "serial=$serial"
  echo "model=$model"
  echo "abi=$abi"
  echo "api=$api"
  echo "fingerprint=$fingerprint"
  echo "rootfs_provisioned=$rootfs_provisioned"
  "${adb[@]}" shell cmd package list packages -U \
    me.rerere.workspace.test | tr -d '\r'
  echo "utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  cat "$raw_report"
} | tee "$report"

if [[ "$instrument_status" -ne 0 ]] ||
   grep -Eq 'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=' "$raw_report" ||
   ! grep -Eq 'OK \([0-9]+ tests?\)' "$raw_report"; then
  echo "Workspace Android verification failed; report: $report" >&2
  exit 1
fi

assumptions="$(grep -c 'INSTRUMENTATION_STATUS_CODE: -4' "$raw_report" || true)"
echo "Workspace Android verification passed ($abi, API $api, assumptions=$assumptions)"
echo "Report: $report"
