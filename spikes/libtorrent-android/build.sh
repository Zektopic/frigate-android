#!/usr/bin/env bash
# Spike: cross-compile libtorrent (with Boost, optionally OpenSSL) for Android
# and link it into a Rust cdylib through a cxx bridge. See README.md.
#
#   ./build.sh                            # arm64-v8a, API 26, OpenSSL on
#   ./build.sh --abi x86_64 --openssl off
#   ./build.sh --host                     # build for this machine, run the smoke test
#
# Android builds need ANDROID_NDK_HOME (r27 or newer). All builds need cmake
# (3.20+), a C/C++ toolchain, perl (for OpenSSL), curl, tar and rustup/cargo.

set -euo pipefail

# Pinned sources. The hashes were checked against upstream when pinned.
LT_VERSION=2.0.15
LT_SHA256=5e2e79129823b7ea48721164c32b5aaf83d3fd733b5502100f6705b29f27bb02
LT_URL=https://github.com/arvidn/libtorrent/releases/download/v$LT_VERSION/libtorrent-rasterbar-$LT_VERSION.tar.gz
BOOST_VERSION=1.92.0
BOOST_SHA256=ea7b982002cc9dfbe59b0b217b206f470dc75f3de0bb2973d844118934d82411
BOOST_URL=https://github.com/boostorg/boost/releases/download/boost-$BOOST_VERSION/boost-$BOOST_VERSION-b2-nodocs.tar.xz
OPENSSL_VERSION=3.5.8
OPENSSL_SHA256=a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2
OPENSSL_URL=https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz

ABI=arm64-v8a
API=26
OPENSSL=on
HOST=0
CLEAN=0
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
die() { echo "error: $*" >&2; exit 1; }
log() { printf '\n==> %s\n' "$*"; }

while [ $# -gt 0 ]; do
  case $1 in
    --abi) ABI=$2; shift 2 ;;
    --api) API=$2; shift 2 ;;
    --openssl) OPENSSL=$2; shift 2 ;;
    --host) HOST=1; shift ;;
    --clean) CLEAN=1; shift ;;
    -j|--jobs) JOBS=$2; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done
case $OPENSSL in on|off) ;; *) die "--openssl takes on or off" ;; esac

HERE=$(cd "$(dirname "$0")" && pwd)
CACHE=$HERE/.cache

# --- target -------------------------------------------------------------------

if [ "$HOST" = 1 ]; then
  [ "$(uname -s)" = Linux ] || die "--host is Linux-only (the checks read ELF)"
  LABEL=host-openssl-$OPENSSL
  RUST_TARGET=$(rustc -vV | sed -n 's/^host: //p')
  CMAKE_TARGET_ARGS=()
  OPENSSL_TARGET=
  NDK_DESC=n/a
else
  NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
  [ -n "$NDK" ] && [ -d "$NDK" ] || die "set ANDROID_NDK_HOME to an NDK (r27 or newer)"
  case $ABI in
    arm64-v8a) RUST_TARGET=aarch64-linux-android; CLANG_TRIPLE=aarch64-linux-android; OPENSSL_TARGET=android-arm64 ;;
    x86_64) RUST_TARGET=x86_64-linux-android; CLANG_TRIPLE=x86_64-linux-android; OPENSSL_TARGET=android-x86_64 ;;
    *) die "unsupported ABI $ABI (arm64-v8a or x86_64)" ;;
  esac
  case $(uname -s) in
    Linux) NDK_HOST=linux-x86_64 ;;
    Darwin) NDK_HOST=darwin-x86_64 ;;
    *) die "unsupported build host $(uname -s)" ;;
  esac
  LLVM_BIN=$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin
  [ -x "$LLVM_BIN/clang" ] || die "no clang under $LLVM_BIN"
  NDK_DESC="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties")"
  LABEL=android-$ABI-api$API-openssl-$OPENSSL
  CMAKE_TARGET_ARGS=(
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM="android-$API"
    -DANDROID_STL=c++_static
  )
fi

PREFIX=$CACHE/prefix/$LABEL
BUILD=$CACHE/build/$LABEL
OUT=$HERE/out/$LABEL
if [ "$CLEAN" = 1 ]; then rm -rf "$PREFIX" "$BUILD" "$OUT" "$CACHE/target/$LABEL"; fi
mkdir -p "$CACHE/downloads" "$CACHE/src" "$PREFIX" "$BUILD" "$OUT"

if command -v ninja >/dev/null; then GENERATOR=(-G Ninja); else GENERATOR=(); fi
if command -v sha256sum >/dev/null; then SHA256=(sha256sum); else SHA256=(shasum -a 256); fi

stage_start() { STAGE_T0=$(date +%s); log "$1"; }
stage_end() { eval "$1=$(( $(date +%s) - STAGE_T0 ))s"; }
T_OPENSSL=cached T_LIBTORRENT=cached T_CARGO=cached
[ "$OPENSSL" = on ] || T_OPENSSL=n/a

# --- sources ------------------------------------------------------------------

# fetch URL SHA256 -> extracts into $CACHE/src, prints the source directory.
fetch() {
  local url=$1 sha=$2 file dir
  file=$CACHE/downloads/$(basename "$url")
  if ! [ -f "$file" ] || ! echo "$sha  $file" | "${SHA256[@]}" -c --status 2>/dev/null; then
    echo "downloading $url" >&2
    curl -fsSL --retry 3 -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  echo "$sha  $file" | "${SHA256[@]}" -c --status || die "checksum mismatch for $file"
  dir=$CACHE/src/$(basename "$file" | sed -E 's/(-b2-nodocs)?\.tar\.(gz|xz)$//')
  if ! [ -d "$dir" ]; then
    echo "extracting $(basename "$file")" >&2
    tar -xf "$file" -C "$CACHE/src"
  fi
  [ -d "$dir" ] || die "expected $dir after extracting $file"
  echo "$dir"
}

log "Fetching sources"
LT_SRC=$(fetch "$LT_URL" "$LT_SHA256")
# libtorrent 2.x needs only Boost's headers (Boost.System has been header-only
# since 1.69), so Boost is never compiled, for Android or anywhere else.
BOOST_SRC=$(fetch "$BOOST_URL" "$BOOST_SHA256")
if [ "$OPENSSL" = on ]; then OPENSSL_SRC=$(fetch "$OPENSSL_URL" "$OPENSSL_SHA256"); fi

# Skip the native stages when this exact configuration is already installed.
STAMP="lt=$LT_VERSION boost=$BOOST_VERSION openssl=$OPENSSL/$OPENSSL_VERSION ndk=$NDK_DESC"
if [ -f "$PREFIX/.stamp" ] && [ "$(cat "$PREFIX/.stamp")" = "$STAMP" ]; then
  log "Native libraries up to date in $PREFIX"
else
  rm -rf "$PREFIX" "$BUILD"
  mkdir -p "$PREFIX" "$BUILD"

  # --- OpenSSL ------------------------------------------------------------------

  if [ "$OPENSSL" = on ]; then
    stage_start "OpenSSL $OPENSSL_VERSION"
    rm -rf "$BUILD/openssl"
    cp -R "$OPENSSL_SRC" "$BUILD/openssl"
    (
      cd "$BUILD/openssl"
      if [ "$HOST" = 1 ]; then
        ./Configure no-shared no-tests no-docs -fPIC --prefix="$PREFIX" --libdir=lib
      else
        export ANDROID_NDK_ROOT=$NDK PATH=$LLVM_BIN:$PATH
        ./Configure "$OPENSSL_TARGET" -D__ANDROID_API__="$API" \
          no-shared no-tests no-docs -fPIC --prefix="$PREFIX" --libdir=lib
      fi
      make -j"$JOBS" build_libs >/dev/null
      make install_dev >/dev/null
    )
    stage_end T_OPENSSL
    CRYPTO_ARGS=(
      # Point FindOpenSSL straight at the files: the NDK toolchain restricts
      # find_* searches to its sysroot, so hints outside it are ignored.
      -DOPENSSL_INCLUDE_DIR="$PREFIX/include"
      -DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.a"
      -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.a"
      -DCMAKE_DISABLE_FIND_PACKAGE_GnuTLS=ON
      -DCMAKE_DISABLE_FIND_PACKAGE_LibGcrypt=ON
    )
  else
    # Without these a host build would silently pick up the system's OpenSSL.
    CRYPTO_ARGS=(
      -DCMAKE_DISABLE_FIND_PACKAGE_OpenSSL=ON
      -DCMAKE_DISABLE_FIND_PACKAGE_GnuTLS=ON
      -DCMAKE_DISABLE_FIND_PACKAGE_LibGcrypt=ON
    )
  fi

  # --- libtorrent ---------------------------------------------------------------

  stage_start "libtorrent $LT_VERSION ($LABEL)"
  cmake -S "$LT_SRC" -B "$BUILD/libtorrent" ${GENERATOR[@]+"${GENERATOR[@]}"} \
    ${CMAKE_TARGET_ARGS[@]+"${CMAKE_TARGET_ARGS[@]}"} \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_CXX_STANDARD=17 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -Ddeprecated-functions=OFF \
    -DCMAKE_POLICY_DEFAULT_CMP0167=OLD \
    -DBoost_INCLUDE_DIR="$BOOST_SRC" \
    -DBoost_NO_SYSTEM_PATHS=ON \
    -DBoost_NO_BOOST_CMAKE=ON \
    "${CRYPTO_ARGS[@]}"
  cmake --build "$BUILD/libtorrent" -j"$JOBS"
  cmake --install "$BUILD/libtorrent" >/dev/null
  stage_end T_LIBTORRENT
  echo "$STAMP" > "$PREFIX/.stamp"
fi

# --- Rust crate ---------------------------------------------------------------

stage_start "cargo build --target $RUST_TARGET"
export LT_SPIKE_PREFIX=$PREFIX
export CARGO_TARGET_DIR=$CACHE/target/$LABEL
if command -v rustup >/dev/null; then rustup target add "$RUST_TARGET" >/dev/null; fi
(
  cd "$HERE"
  if [ "$HOST" = 0 ]; then
    t=$(echo "$RUST_TARGET" | tr - _)
    T=$(echo "$t" | tr '[:lower:]' '[:upper:]')
    export "CC_$t=$LLVM_BIN/$CLANG_TRIPLE$API-clang"
    export "CXX_$t=$LLVM_BIN/$CLANG_TRIPLE$API-clang++"
    export "AR_$t=$LLVM_BIN/llvm-ar"
    export "CARGO_TARGET_${T}_LINKER=$LLVM_BIN/$CLANG_TRIPLE$API-clang"
    export CXXSTDLIB=c++_static
  fi
  cargo build --release --locked --target "$RUST_TARGET"
)
stage_end T_CARGO

SO=$CARGO_TARGET_DIR/$RUST_TARGET/release/liblt_spike.so
SMOKE=$CARGO_TARGET_DIR/$RUST_TARGET/release/lt-smoke
cp "$SO" "$SMOKE" "$OUT/"

# --- checks -------------------------------------------------------------------

CHECKS=()
FAILED=0
check() { # check NAME RESULT(0/1) DETAIL
  if [ "$2" = 0 ]; then CHECKS+=("| ✅ | $1 | $3 |"); else CHECKS+=("| ❌ | $1 | $3 |"); FAILED=1; fi
}

if [ "$HOST" = 1 ]; then
  READELF=readelf NM=nm
else
  READELF=$LLVM_BIN/llvm-readelf NM=$LLVM_BIN/llvm-nm
fi

log "Checking $SO"
# Getting here at all means the cdylib linked with --no-undefined on Android.
NEEDED=$("$READELF" -d "$SO" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')
EXPORTED=$("$NM" -D --defined-only "$SO" | grep -c ' lt_spike_loopback_transfer$' || true)
LT_SYMBOLS=$("$NM" -C "$SO" | grep -c 'libtorrent::' || true)
check "exports lt_spike_loopback_transfer" $([ "$EXPORTED" = 1 ]; echo $?) "\`$EXPORTED\` match"
check "libtorrent linked in" $([ "$LT_SYMBOLS" -gt 1000 ]; echo $?) "$LT_SYMBOLS \`libtorrent::\` symbols"

if [ "$HOST" = 0 ]; then
  UNEXPECTED=$(echo "$NEEDED" | tr ' ' '\n' | grep -v '^$' | grep -vxE 'lib(c|m|dl|log)\.so' || true)
  check "links with --no-undefined" 0 "every symbol resolved at link time"
  check "only system libraries NEEDED" $([ -z "$UNEXPECTED" ]; echo $?) "\`$NEEDED\`"
  ALIGNS=$("$READELF" -lW "$SO" | awk '$1 == "LOAD" { print $NF }' | sort -u | tr '\n' ' ')
  BAD_ALIGN=0
  for a in $ALIGNS; do [ $((a)) -ge 16384 ] || BAD_ALIGN=1; done
  check "16 KB-aligned LOAD segments" "$BAD_ALIGN" "\`$ALIGNS\`"
  SMOKE_LOG=
else
  log "Running lt-smoke"
  if SMOKE_LOG=$("$SMOKE" "$OUT/work" $((64 << 20)) 2>&1); then SMOKE_OK=0; else SMOKE_OK=1; fi
  echo "$SMOKE_LOG"
  check "loopback transfer (64 MiB)" "$SMOKE_OK" "$(echo "$SMOKE_LOG" | tail -1)"
  rm -rf "$OUT/work"
fi

size_of() { du -k "$1" | awk '{ printf "%.1f MiB", $1 / 1024 }'; }
STRIPPED=$OUT/liblt_spike.stripped.so
if [ "$HOST" = 1 ]; then strip -o "$STRIPPED" "$SO"; else "$LLVM_BIN/llvm-strip" -o "$STRIPPED" "$SO"; fi

REPORT=$OUT/report.md
{
  echo "### libtorrent spike: \`$LABEL\`"
  echo
  echo "| | Check | Detail |"
  echo "|---|---|---|"
  printf '%s\n' "${CHECKS[@]}"
  echo
  echo "| Input | Value |"
  echo "|---|---|"
  echo "| Rust target | \`$RUST_TARGET\` |"
  echo "| NDK | $NDK_DESC |"
  echo "| libtorrent / Boost / OpenSSL | $LT_VERSION / $BOOST_VERSION (headers only) / $([ "$OPENSSL" = on ] && echo "$OPENSSL_VERSION" || echo off) |"
  echo "| Build time: OpenSSL / libtorrent / cargo | $T_OPENSSL / $T_LIBTORRENT / $T_CARGO (\`-j$JOBS\`) |"
  echo "| \`liblt_spike.so\` unstripped / stripped | $(size_of "$SO") / $(size_of "$STRIPPED") |"
  if [ -n "$SMOKE_LOG" ]; then
    printf '\n```\n%s\n```\n' "$SMOKE_LOG"
  else
    echo
    echo "Smoke test not run: it needs a device or emulator (see README.md)."
  fi
} > "$REPORT"

log "Report ($REPORT)"
cat "$REPORT"
exit "$FAILED"
