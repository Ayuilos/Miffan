#!/usr/bin/env bash
# CI-only opt-in downloader. Local builds use existing .deps archives without networking.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$ROOT/.deps"
fetch() {
  local name="$1" digest="$2" url="$3"
  if [[ ! -f "$ROOT/.deps/$name" ]]; then
    curl --fail --location --retry 3 --output "$ROOT/.deps/$name.part" "$url"
    printf '%s  %s\n' "$digest" "$ROOT/.deps/$name.part" | shasum -a 256 -c -
    mv "$ROOT/.deps/$name.part" "$ROOT/.deps/$name"
  fi
  printf '%s  %s\n' "$digest" "$ROOT/.deps/$name" | shasum -a 256 -c -
}
fetch FreeRDP-3.32.1.tar.gz 8803dd26ec9660550252f255cf2d672a785ddd8f544bb475834993e96807c87f \
  https://github.com/FreeRDP/FreeRDP/archive/refs/tags/3.32.1.tar.gz
fetch openssl-3.5.9.tar.gz 603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a \
  https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz
