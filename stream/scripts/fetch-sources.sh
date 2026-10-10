#!/usr/bin/env bash
# Offline archives take priority; downloads are opt-in by running this script.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fetch() {
  local path="$ROOT/.deps/p6a/$1" digest="$2" url="$3"
  mkdir -p "$(dirname "$path")"
  if [[ ! -f "$path" ]]; then
    curl --fail --location --retry 3 --output "$path.part" "$url"
    printf '%s  %s\n' "$digest" "$path.part" | shasum -a 256 -c -
    mv "$path.part" "$path"
  fi
  printf '%s  %s\n' "$digest" "$path" | shasum -a 256 -c -
}
fetch common-c.tar.gz be204cebb6687acd2d65dab548ea6d9c8d60bd277984ef49719bad5cbc0385db https://codeload.github.com/moonlight-stream/moonlight-common-c/tar.gz/f900dd4767759c7b9d0e93bcea666b55c69ea62f
fetch common-c/enet.tar.gz 6bb1a151e6d21e1756baeff5a95eaf85a5b6731aeda6d0bc255651192ba4a32d https://codeload.github.com/cgutman/enet/tar.gz/aca87840b57f045a1f7f9299e4b1b9b8e2a5e2f1
fetch common-c/nanors.tar.gz 41edc0309b255b0eeb5e8eb1ad79f7c7e9e6c31db1bd79a16d73271c62867003 https://codeload.github.com/sleepybishop/nanors/tar.gz/b1e3c22ca0cdc0bb83e3cd6ed1a2fc77869ed99a
