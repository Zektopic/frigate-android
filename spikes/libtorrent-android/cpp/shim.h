#pragma once

#include "rust/cxx.h"

#include <cstdint>

namespace lt_spike {

struct TransferReport;

rust::String libtorrent_version();
rust::String boost_version();
rust::String crypto_backend();
TransferReport loopback_transfer(rust::Str work_dir, std::uint64_t size_bytes,
                                 std::uint64_t timeout_ms);

}  // namespace lt_spike
