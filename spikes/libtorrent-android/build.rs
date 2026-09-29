//! Compiles the C++ side of the bridge against the libtorrent that build.sh
//! installed into $LT_SPIKE_PREFIX, and links it.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=LT_SPIKE_PREFIX");
    println!("cargo:rerun-if-changed=cpp/shim.h");
    println!("cargo:rerun-if-changed=cpp/shim.cc");

    let prefix = PathBuf::from(env::var("LT_SPIKE_PREFIX").expect(
        "LT_SPIKE_PREFIX is not set. Run ./build.sh, which builds libtorrent first and sets it.",
    ));
    let lib_dir = prefix.join("lib");
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap();

    let mut build = cxx_build::bridge("src/lib.rs");
    build
        .file("cpp/shim.cc")
        .std("c++17")
        .include(prefix.join("include"));
    // libtorrent's public defines (TORRENT_*, BOOST_ASIO_*) change struct
    // layouts, so the shim must see exactly what the library was built with.
    // The installed pkg-config file is the one place CMake writes them down.
    for flag in pkg_config_cflags(&lib_dir.join("pkgconfig/libtorrent-rasterbar.pc"), &prefix) {
        if let Some(dir) = flag.strip_prefix("-I") {
            build.include(dir);
        } else if let Some(def) = flag.strip_prefix("-D") {
            match def.split_once('=') {
                Some((name, value)) => build.define(name, value),
                None => build.define(def, None),
            };
        } else {
            build.flag(&flag);
        }
    }
    build.compile("lt_spike_shim");

    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    println!("cargo:rustc-link-lib=static=torrent-rasterbar");
    if lib_dir.join("libssl.a").exists() {
        println!("cargo:rustc-link-lib=static=ssl");
        println!("cargo:rustc-link-lib=static=crypto");
    }

    if target_os == "android" {
        // build.sh sets CXXSTDLIB=c++_static so cc links libc++ statically:
        // the NDK's advice when an app ships a single native library. The
        // static archive does not pull in the ABI library on its own.
        println!("cargo:rustc-link-lib=c++abi");
        // Resolve every symbol at link time instead of at System.loadLibrary().
        println!("cargo:rustc-cdylib-link-arg=-Wl,--no-undefined");
        // 16 KB page alignment, required on Android 15+ devices that use it.
        // NDK r28 does this by default; r27 needs the flag.
        println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
    }
}

/// Reads the `Cflags:` line of a pkg-config file, expanding `${prefix}`.
fn pkg_config_cflags(pc: &Path, prefix: &Path) -> Vec<String> {
    let text = fs::read_to_string(pc).unwrap_or_else(|e| {
        panic!("cannot read {}: {e}. Did build.sh finish the libtorrent stage?", pc.display())
    });
    let prefix = prefix.to_str().expect("LT_SPIKE_PREFIX must be UTF-8");
    text.lines()
        .find_map(|line| line.strip_prefix("Cflags:"))
        .unwrap_or_default()
        .replace("${prefix}", prefix)
        .split_whitespace()
        .map(str::to_owned)
        .collect()
}
