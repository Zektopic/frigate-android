# libtorrent on Android: build spike

A throwaway crate that answers one question before anything gets built on top of the answer:

> Can libtorrent (C++ with Boost) be cross-compiled for `aarch64-linux-android` and linked into a
> Rust cdylib through a [`cxx`](https://cxx.rs) bridge?

If it builds cleanly, keep libtorrent. If it can't be made to build within a day, switch to
[librqbit](https://github.com/ikatson/rqbit) before the gateway design depends on libtorrent.

The spike is isolated from the app. Nothing in the Gradle build references it, and it has its own
CI workflow. Delete this directory once the decision is made.

## What we already know

- **Boost is never compiled.** libtorrent 2.x uses only Boost's headers. Boost.System has been
  header-only since 1.69, and libtorrent's CMake links `Boost::headers` and nothing else. So the
  Boost risk is only whether the Asio headers compile under NDK clang. Nobody has to get `b2`
  cross-compiling. `build.sh` unpacks the Boost release and points CMake at it.
- **libtorrent already supports Android, except for route enumeration.** 2.0 has
  `TORRENT_ANDROID` code paths. On API 24+ it enumerates interfaces with `getifaddrs` instead of
  netlink, which Android 11 blocks for apps. Nothing replaces the netlink *route* dump, though:
  `enum_routes` returns "operation not supported", so every session start posts a
  `listen_failed_alert` with op `enum_route`. libtorrent treats that as informational and listens
  on the unspecified address instead of expanding it per interface (`session_impl.cpp`, "For
  Android API >= 24"). Its own comment notes the cost: the IPv6 DHT then does not follow BEP 32
  and BEP 45. `lt-smoke` prints the alert as a warning rather than failing on it.
- **The host build passes.** With these same pins on x86_64 Linux, the loopback transfer passes
  with and without OpenSSL. CI runs that check on every change.

## Run it

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018   # r27c, or anything newer

./build.sh                     # arm64-v8a, API 26, OpenSSL on
./build.sh --openssl off       # libtorrent's built-in SHA-1/SHA-256, no TLS
./build.sh --abi x86_64        # for an emulator
./build.sh --host              # Linux host build, also runs the smoke test
```

You need cmake 3.20 or newer, a C/C++ toolchain, perl (for OpenSSL), curl, tar and rustup. The
sources are pinned by version and SHA-256 at the top of `build.sh`, and downloads and builds are
cached under `.cache/`. Each run writes `liblt_spike.so`, `lt-smoke` and `report.md` to
`out/<target>/`.

To run the smoke test on a device (the build does not do this for you):

```bash
adb push out/android-arm64-v8a-api26-openssl-on/lt-smoke /data/local/tmp/
adb shell /data/local/tmp/lt-smoke /data/local/tmp/lt-spike 67108864
```

`lt-smoke` times each phase separately:

- `hash`: building the torrent, before any session starts.
- `transfer`: from adding the leech until it holds every piece, hash-checked.
- `teardown`: destroying both sessions, which flushes their disk threads.

The total on the `PASS` line covers the sessions only, not hashing.

To compare configurations on a device without rebuilding, set any of these (unset keeps
libtorrent's default):

| Variable | Values | libtorrent default |
|---|---|---|
| `LT_SPIKE_DISK_IO` | `mmap`, `posix` | `mmap` on 64-bit |
| `LT_SPIKE_TORRENT` | `hybrid`, `v1`, `v2` | `hybrid` (SHA-1 and SHA-256 on every piece) |
| `LT_SPIKE_HASHING_THREADS` | positive integer | 1 |
| `LT_SPIKE_AIO_THREADS` | positive integer | 10 |

```bash
adb shell LT_SPIKE_DISK_IO=posix /data/local/tmp/lt-smoke /data/local/tmp/lt-spike 268435456
```

## What counts as a pass

| Check | Why it matters |
|---|---|
| cdylib links with `-Wl,--no-undefined` | Every libtorrent, OpenSSL and libc++ symbol resolves at build time, so none can fail later at `System.loadLibrary()` |
| exports `lt_spike_loopback_transfer` and contains `libtorrent::` symbols | Confirms the linker kept the library instead of discarding it |
| `NEEDED` lists only libc, libm, libdl and liblog | libc++ is linked statically, so there is no `libc++_shared.so` to ship |
| LOAD segments are 16 KB aligned | Required on Android 15+ devices that use 16 KB pages |
| `lt-smoke` prints `PASS` | Two libtorrent sessions move a file over 127.0.0.1 and the bytes match |

`lt-smoke` generates a file, creates a torrent from it, seeds it from one session and downloads it
into a second session. DHT, LSD, UPnP, NAT-PMP and uTP are off, and it never leaves loopback. Any
torrent error or file error alert fails the run immediately, before the timeout.

## Pins and choices

| | |
|---|---|
| libtorrent 2.0.15 | Latest release of the stable 2.0 series. To try 2.1.x, change `LT_VERSION` and its hash |
| Boost 1.92.0 | Headers only (see above) |
| OpenSSL 3.5.8 | LTS release. It is only needed for TLS: HTTPS trackers, HTTPS web seeds (which Archive.org torrents rely on), and SSL torrents. Protocol encryption (PE) works without it |
| NDK r27c, API 26 | LTS NDK. API 26 is this repo's `minSdk`, and libtorrent's Android code paths want at least 24 |
| `c++_static` | The NDK's recommendation when an app ships a single native library. That library is the Rust cdylib, and FFmpeg and whisper.cpp would link into it later |
| `deprecated-functions=OFF` | Smaller binary, and only the 2.x API is available |

## What this spike does not cover

- **The app sandbox.** `adb shell` runs in the `shell` SELinux domain, but an APK runs as
  `untrusted_app`, which Android 11+ restricts further, netlink included. If the build passes, the
  next step is to load the cdylib from a minimal APK over JNI and run the same transfer there.
- **32-bit ABIs** (armeabi-v7a, x86).
- **A custom `disk_interface`** that maps pieces onto the 256 MiB pack files.
- **A device or emulator run in CI.** CI only builds and checks the Android binaries. It runs the
  smoke test on the Linux host.
