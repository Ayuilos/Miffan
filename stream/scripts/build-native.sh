#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
CMAKE="${CMAKE:-$SDK/cmake/3.22.1/bin/cmake}"
python3 "$ROOT/stream/scripts/prepare.py"
ABIS=("${@:-}"); [[ -n "${ABIS[0]}" ]] || ABIS=(arm64-v8a x86_64)
for ABI in "${ABIS[@]}"; do
  "$CMAKE" -S "$ROOT/stream/src/main/cpp" -B "$ROOT/stream/build/native/$ABI" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$(dirname "$CMAKE")/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$SDK/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
  "$CMAKE" --build "$ROOT/stream/build/native/$ABI" -j "${JOBS:-8}"
  ls -lh "$ROOT/stream/build/native/$ABI/libmiffanstream.so"
done
