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
        elapsed_ms: u64,
        piece_length: u32,
        num_pieces: u32,
        info_hash: String,
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
    match ffi::loopback_transfer(dir, size_bytes, timeout_ms) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}
