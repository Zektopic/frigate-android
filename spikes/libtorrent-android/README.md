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
| `LT_SPIKE_TRACE` | `1` prints timestamped connection, peer and performance alerts to stderr | off |

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

## Results on a device

These results are from a Redmi Note 13 (23129RAA4G) running Android 16 with 4 KB pages. Its
Snapdragon 685 (SM6225) has 4 Cortex-A73 cores at 2.8 GHz, 4 A53 cores at 1.9 GHz, and the ARMv8
SHA-1/SHA-256 instructions. `lt-smoke` ran from `adb shell` with a 256 MiB payload. Each figure is
the median of 5 runs, with the variants interleaved and the big cores below 45 °C before every run.

| Build and configuration | Hash (create torrent) | Transfer | CPU s per GiB: hash / sessions |
|---|---|---|---|
| OpenSSL off, libtorrent defaults | 3.81 s (67 MiB/s) | 3.51 s (73 MiB/s) | 16.6 / 66.0 |
| OpenSSL on, libtorrent defaults | 0.55 s (466 MiB/s) | 2.74 s (94 MiB/s) | 2.9 / 40.6 |
| OpenSSL on, `aio_threads=2` | 0.55 s | 2.59 s (99 MiB/s) | 2.9 / 25.8 |
| OpenSSL on, v1-only torrent, `aio_threads=2` | 0.32 s (810 MiB/s) | 2.50 s (102 MiB/s) | 1.7 / 23.7 |
| OpenSSL on, 4 hashing threads | 0.17 s (1506 MiB/s) | 2.74 s | 2.6 / 40.7 |
| OpenSSL on, posix disk backend | 0.68 s (375 MiB/s) | 2.41 s (106 MiB/s) | 2.7 / 13.5 |
| OpenSSL off, posix disk backend | 3.97 s | 5.59 s (46 MiB/s) | 15.7 / 38.7 |

At 1 GiB (3 runs each), going from OpenSSL off with defaults to OpenSSL on with a v1-only torrent
and `aio_threads=2` takes:

- hashing from 15.2 s to 1.2 s;
- the transfer from 10.8 s to 6.5 s;
- session CPU from 60.6 to 19.3 s per GiB.

**What the numbers say for the gateway:**

- **Build with OpenSSL.** Without it, libtorrent hashes in portable C. OpenSSL is the path to the
  CPU's SHA instructions: hashing is about 7× faster at a sixth of the CPU. The cost is 6.4 MiB on
  the stripped library (10.5 MiB against 4.1 MiB).
- **Set `aio_threads` to 2 on phones.** Compared with libtorrent's default of 10, it cut session
  CPU by 36% (40.6 to 25.8 s per GiB) and the transfer was slightly faster. 4 threads sits in
  between, and 1 is too few: the transfer takes 57% longer.
- **`hashing_threads` only shortens wall time.** Four threads make torrent creation 3.2× faster
  for the same CPU, so raise it only where creation latency matters.
- **Single-format torrents hash once instead of twice.** Hybrid runs SHA-1 and SHA-256 over every
  piece. With OpenSSL, v1-only or v2-only cut hashing CPU from 2.9 to 1.7–1.9 s per GiB, a small
  saving, so choose the format for compatibility. Without OpenSSL the difference is large, because
  portable-C SHA-256 is the slow half: v1-only needs 4.5 s per GiB against hybrid's 16.6.
- **Consider the posix disk backend, but test it first.** With OpenSSL it used the least CPU of
  anything here. It is single-threaded, though: every read, write and hash runs on the session's
  network thread. That is why it gains with fast hashing and loses badly with slow hashing, and
  why a one-peer loopback test can't show how it behaves with many peers or stalling flash.
- **Add torrents unpaused and not auto-managed.** A torrent added with libtorrent's default flags
  only starts on its session's next 500 ms tick. A peer that connects to it while it is still
  paused is dropped and waits `min_reconnect_time` (60 s) before retrying. This is what made early
  device runs take 65 s.

**Read these numbers as upper bounds:**

- Both peers share one process, so the session CPU covers two peers. Compare rows rather than
  reading absolutes.
- The seed's payload is already in the page cache, so this measures CPU and memory, not flash or
  radio.
- About 0.5 s of each transfer is libtorrent waiting for its first tick before it connects.

## Pins and choices

| | |
|---|---|
| libtorrent 2.0.15 | Latest release of the stable 2.0 series. To try 2.1.x, change `LT_VERSION` and its hash |
| Boost 1.92.0 | Headers only (see above) |
| OpenSSL 3.5.8 | LTS release. It is needed for TLS: HTTPS trackers, HTTPS web seeds (which Archive.org torrents rely on), and SSL torrents. Protocol encryption (PE) works without it. On ARM it is also the only way libtorrent reaches the CPU's SHA instructions (see [Results on a device](#results-on-a-device)) |
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
