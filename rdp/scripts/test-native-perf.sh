#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TASK_TMP="$(mktemp -d)"
trap 'rm -rf "$TASK_TMP"' EXIT
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -I "$ROOT/rdp/src/main/cpp" \
  "$ROOT/rdp/src/test/cpp/frame_ack_test.cpp" -o "$TASK_TMP/frame_ack_test"
"$TASK_TMP/frame_ack_test"
echo 'Native frame ACK parser checks passed'
