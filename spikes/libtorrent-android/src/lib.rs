//! Throwaway spike: can libtorrent (C++, Boost.Asio) be linked into a Rust
//! cdylib for Android through a cxx bridge? See README.md for what counts as
//! a pass and what the result decides.

use std::ffi::{c_char, CStr};

#[cxx::bridge(namespace = "lt_spike")]
pub mod ffi {
    /// Outcome of a successful two-session transfer over loopback.
    #[derive(Debug)]
    struct TransferReport {
        bytes: u64,
        /// Session start to session teardown: the seed's startup plus
        /// `transfer_ms` and `teardown_ms`. Excludes `hash_ms`.
        elapsed_ms: u64,
        /// Hashing the payload into a torrent, before any session starts. The
        /// file was just written, so this is mostly SHA-1 and SHA-256 over
        /// page-cached data.
        hash_ms: u64,
        /// Leech added until it holds every piece, hash-checked.
        transfer_ms: u64,
        /// Destroying both sessions, which flushes their disk threads.
        teardown_ms: u64,
        /// CPU time (user + system, all threads) spent in `hash_ms`.
        hash_cpu_ms: u64,
        /// CPU time spent in `elapsed_ms`, seed and leech together: on a
        /// phone, CPU per byte matters more than loopback throughput.
        session_cpu_ms: u64,
        piece_length: u32,
        num_pieces: u32,
        info_hash: String,
        /// Alerts libtorrent reports as failures but recovers from, such as
        /// route enumeration on Android. They do not fail the run.
        warnings: Vec<String>,
    }

    /// Configuration to compare on a device. Empty strings and zeros keep
    /// libtorrent's defaults.
    #[derive(Debug, Default)]
    struct TransferOptions {
        /// Disk backend: "mmap" (libtorrent 2.0's default on 64-bit) or "posix".
        disk_io: String,
        /// Torrent format: "hybrid" (create_torrent's default), "v1" or "v2".
        /// Hybrid hashes every piece with both SHA-1 and SHA-256.
        torrent: String,
        /// `settings_pack::hashing_threads` (libtorrent default 1).
        hashing_threads: i32,
        /// `settings_pack::aio_threads` (libtorrent default 10).
        aio_threads: i32,
        /// Print every connection, peer and performance alert to stderr,
        /// timestamped, as it is popped.
        trace: bool,
    }

    unsafe extern "C++" {
        include!("lt-spike/cpp/shim.h");

        /// Version reported by the linked library, not the headers.
        fn libtorrent_version() -> String;
        fn boost_version() -> String;
        /// Which SHA-1/TLS backend libtorrent was built against.
        fn crypto_backend() -> String;

        /// Generates a `size_bytes` file under `work_dir`, seeds it from one
        /// session and downloads it into a second over 127.0.0.1, with DHT,
        /// LSD, UPnP, NAT-PMP and uTP off. Fails on any torrent or file error
        /// alert, on timeout, or if the downloaded bytes differ.
        fn loopback_transfer(
            work_dir: &str,
            size_bytes: u64,
            timeout_ms: u64,
            options: &TransferOptions,
        ) -> Result<TransferReport>;
    }
}

/// C ABI entry point for the cdylib.
///
/// It exists so the shared library exports something that reaches the whole
/// session path. Without it the linker could drop libtorrent entirely and the
/// `--no-undefined` link in build.rs would prove nothing.
///
/// Returns 0 on success and -1 on any failure.
///
/// # Safety
///
/// `work_dir` must be null or point to a NUL-terminated string.
#[no_mangle]
pub unsafe extern "C" fn lt_spike_loopback_transfer(
    work_dir: *const c_char,
    size_bytes: u64,
    timeout_ms: u64,
) -> i32 {
    if work_dir.is_null() {
        return -1;
    }
    let Ok(dir) = unsafe { CStr::from_ptr(work_dir) }.to_str() else {
        return -1;
    };
    match ffi::loopback_transfer(dir, size_bytes, timeout_ms, &ffi::TransferOptions::default()) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}
