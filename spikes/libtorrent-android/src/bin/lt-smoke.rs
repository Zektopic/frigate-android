//! Prints what was linked, then runs the loopback transfer.
//!
//!     lt-smoke [WORK_DIR] [SIZE_BYTES]
//!
//! Exit status 0 means libtorrent moved real bytes on this machine, not just
//! that it linked. On Android, WORK_DIR defaults to /data/local/tmp/lt-spike.
//!
//! These environment variables change the configuration under test; unset
//! keeps libtorrent's defaults:
//!
//!     LT_SPIKE_DISK_IO=mmap|posix
//!     LT_SPIKE_TORRENT=hybrid|v1|v2
//!     LT_SPIKE_HASHING_THREADS=N
//!     LT_SPIKE_AIO_THREADS=N

use std::path::PathBuf;
use std::process::ExitCode;

use lt_spike::ffi;

const DEFAULT_SIZE: u64 = 16 << 20;
const TIMEOUT_MS: u64 = 120_000;

fn options_from_env() -> Result<ffi::TransferOptions, String> {
    let text = |name: &str| std::env::var(name).unwrap_or_default();
    let count = |name: &str| match std::env::var(name) {
        Err(_) => Ok(0),
        Ok(v) => match v.parse::<i32>() {
            Ok(n) if n > 0 => Ok(n),
            _ => Err(format!("{name} must be a positive integer, got {v:?}")),
        },
    };
    Ok(ffi::TransferOptions {
        disk_io: text("LT_SPIKE_DISK_IO"),
        torrent: text("LT_SPIKE_TORRENT"),
        hashing_threads: count("LT_SPIKE_HASHING_THREADS")?,
        aio_threads: count("LT_SPIKE_AIO_THREADS")?,
    })
}

fn main() -> ExitCode {
    let mut args = std::env::args().skip(1);
    let work_dir = args
        .next()
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::temp_dir().join("lt-spike"));
    let size = match args.next().map(|s| s.parse::<u64>()) {
        None => DEFAULT_SIZE,
        Some(Ok(n)) if n > 0 => n,
        Some(_) => {
            eprintln!("usage: lt-smoke [WORK_DIR] [SIZE_BYTES > 0]");
            return ExitCode::from(2);
        }
    };
    let Some(work_dir) = work_dir.to_str() else {
        eprintln!("work dir is not valid UTF-8: {}", work_dir.display());
        return ExitCode::from(2);
    };

    let options = match options_from_env() {
        Ok(o) => o,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::from(2);
        }
    };
    let or_default = |s: &str| if s.is_empty() { "default".to_owned() } else { s.to_owned() };
    let or_default_n = |n: i32| if n > 0 { n.to_string() } else { "default".to_owned() };

    println!("libtorrent {}", ffi::libtorrent_version());
    println!("boost      {}", ffi::boost_version());
    println!("crypto     {}", ffi::crypto_backend());
    println!(
        "options    disk_io={} torrent={} hashing_threads={} aio_threads={}",
        or_default(&options.disk_io),
        or_default(&options.torrent),
        or_default_n(options.hashing_threads),
        or_default_n(options.aio_threads),
    );
    println!("transfer   {size} bytes via {work_dir}");

    match ffi::loopback_transfer(work_dir, size, TIMEOUT_MS, &options) {
        Ok(r) => {
            let mib_s = |ms: u64| r.bytes as f64 / (1024.0 * 1024.0) / (ms.max(1) as f64 / 1000.0);
            for w in &r.warnings {
                println!("warning    {w}");
            }
            println!(
                "phases     hash {} ms ({:.1} MiB/s), transfer {} ms ({:.1} MiB/s), teardown {} ms",
                r.hash_ms,
                mib_s(r.hash_ms),
                r.transfer_ms,
                mib_s(r.transfer_ms),
                r.teardown_ms,
            );
            // Last line, so build.sh's report can quote it on its own.
            println!(
                "PASS       {} bytes in {} ms ({:.1} MiB/s), {} pieces x {} KiB, info-hash {}",
                r.bytes,
                r.elapsed_ms,
                mib_s(r.elapsed_ms),
                r.num_pieces,
                r.piece_length / 1024,
                r.info_hash,
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("FAIL       {e}");
            ExitCode::FAILURE
        }
    }
}
