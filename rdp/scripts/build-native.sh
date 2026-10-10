#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEPS="$ROOT/.deps"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
CMAKE="${CMAKE:-$SDK/cmake/3.22.1/bin/cmake}"
NINJA="$(dirname "$CMAKE")/ninja"
JOBS="${JOBS:-8}"
OUT="${RDP_NATIVE_OUT:-$ROOT/rdp/build/native}"
mkdir -p "$OUT"
check() { echo "$2  $DEPS/$1" | shasum -a 256 -c -; }
check FreeRDP-3.32.1.tar.gz 8803dd26ec9660550252f255cf2d672a785ddd8f544bb475834993e96807c87f
check openssl-3.5.9.tar.gz 603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
[[ -d "$DEPS/FreeRDP-3.32.1" ]] || tar -xzf "$DEPS/FreeRDP-3.32.1.tar.gz" -C "$DEPS"
[[ -d "$DEPS/openssl-3.5.9" ]] || tar -xzf "$DEPS/openssl-3.5.9.tar.gz" -C "$DEPS"
# Recreate only sources touched by patches from the already verified archive. Applying
# all patches in order is reproducible and also handles edits to an existing patch.
PATCHED_SOURCES=(libfreerdp/codec/h264_mediacodec.c channels/rdpgfx/client/rdpgfx_main.c libfreerdp/core/client.c)
for SOURCE in "${PATCHED_SOURCES[@]}"; do
  tar -xzf "$DEPS/FreeRDP-3.32.1.tar.gz" -C "$DEPS" "FreeRDP-3.32.1/$SOURCE"
done
for PATCH in "$ROOT/rdp/scripts/patches/"*.patch; do
  patch --batch --fuzz=0 -p1 -d "$DEPS/FreeRDP-3.32.1" < "$PATCH"
done
case "$(uname -s)" in Darwin) HOST=darwin-x86_64;; Linux) HOST=linux-x86_64;; *) exit 1;; esac
export ANDROID_NDK_ROOT="$NDK"
export PATH="$NDK/toolchains/llvm/prebuilt/$HOST/bin:$PATH"
ABIS=("${@:-}")
[[ -n "${ABIS[0]}" ]] || ABIS=(arm64-v8a x86_64)
for ABI in "${ABIS[@]}"; do
  STARTED=$SECONDS
  case "$ABI" in arm64-v8a) TARGET=android-arm64;; x86_64) TARGET=android-x86_64;; *) echo "Unsupported ABI: $ABI" >&2; exit 1;; esac
  PREFIX="$OUT/$ABI"
  SSL_BUILD="$OUT/openssl-$ABI"
  mkdir -p "$SSL_BUILD"
  if [[ ! -f "$PREFIX/lib/libssl.a" ]]; then
    (cd "$SSL_BUILD"
     perl "$DEPS/openssl-3.5.9/Configure" "$TARGET" -D__ANDROID_API__=26 no-shared no-tests no-apps no-module no-engine no-dso -fPIC --prefix="$PREFIX" --libdir=lib
     make -j"$JOBS"
     make install_sw)
  fi
  CHANNELS=()
  for DIR in "$DEPS/FreeRDP-3.32.1/channels/"*/ChannelOptions.cmake; do
    NAME="$(basename "$(dirname "$DIR")" | tr '[:lower:]' '[:upper:]')"
    case "$NAME" in DRDYNVC|RDPGFX|DISP|CLIPRDR) VALUE=ON;; *) VALUE=OFF;; esac
    CHANNELS+=("-DCHANNEL_$NAME=$VALUE" "-DCHANNEL_${NAME}_CLIENT=$VALUE" "-DCHANNEL_${NAME}_SERVER=OFF")
  done
  "$CMAKE" -S "$DEPS/FreeRDP-3.32.1" -B "$OUT/freerdp-$ABI" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF \
    -DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_INCLUDE_DIR="$PREFIX/include" \
    -DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.a" -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.a" \
    -DWITH_CLIENT=OFF -DWITH_CLIENT_COMMON=ON -DWITH_SERVER=OFF -DWITH_SERVER_INTERFACE=OFF \
    -DWITH_SERVER_CHANNELS=OFF -DWITH_CLIENT_CHANNELS=ON -DWITH_CHANNELS=ON \
    -DWITH_SAMPLE=OFF -DWITH_WINPR_TOOLS=OFF -DWITH_WINPR_TOOLS_LIBRARY=OFF -DWITH_MANPAGES=OFF \
    -DWITH_FFMPEG=OFF -DWITH_SWSCALE=OFF -DWITH_OPENH264=OFF -DWITH_MEDIACODEC=ON \
    -DWITH_X11=OFF -DWITH_WAYLAND=OFF -DWITH_SDL=OFF -DWITH_OPENSLES=OFF -DWITH_AAUDIO=OFF \
    -DWITH_CUPS=OFF -DWITH_PCSC=OFF -DWITH_PKCS11=OFF -DWITH_KRB5=OFF -DWITH_GSSAPI=OFF \
    -DWITH_FUSE=OFF -DWITH_URIPARSER=OFF -DWITH_WINPR_JSON=OFF -DWITH_AAD=OFF \
    -DWITH_JPEG=OFF -DBUILD_TESTING=OFF -DWITH_CCACHE=OFF -DWITH_CLANG_FORMAT=OFF \
    "${CHANNELS[@]}"
  "$CMAKE" --build "$OUT/freerdp-$ABI" -j "$JOBS"
  "$CMAKE" --install "$OUT/freerdp-$ABI"
  echo "Native static libraries ready: $PREFIX ($((SECONDS - STARTED)) seconds)"
done
