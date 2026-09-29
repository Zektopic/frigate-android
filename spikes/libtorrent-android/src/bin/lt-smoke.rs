//! Prints what was linked, then runs the loopback transfer.
//!
//!     lt-smoke [WORK_DIR] [SIZE_BYTES]
//!
//! Exit status 0 means libtorrent moved real bytes on this machine, not just
//! that it linked. On Android, WORK_DIR defaults to /data/local/tmp/lt-spike.

use std::path::PathBuf;
use std::process::ExitCode;

use lt_spike::ffi;

const DEFAULT_SIZE: u64 = 16 << 20;
const TIMEOUT_MS: u64 = 120_000;

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

    println!("libtorrent {}", ffi::libtorrent_version());
    println!("boost      {}", ffi::boost_version());
    println!("crypto     {}", ffi::crypto_backend());
    println!("transfer   {size} bytes via {work_dir}");

    match ffi::loopback_transfer(work_dir, size, TIMEOUT_MS) {
        Ok(r) => {
            let secs = (r.elapsed_ms.max(1) as f64) / 1000.0;
            let mib_s = r.bytes as f64 / (1024.0 * 1024.0) / secs;
            println!(
                "PASS       {} bytes in {} ms ({mib_s:.1} MiB/s), {} pieces x {} KiB, info-hash {}",
                r.bytes,
                r.elapsed_ms,
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
