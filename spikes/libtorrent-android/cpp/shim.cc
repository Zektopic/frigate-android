// C++ half of the cxx bridge declared in src/lib.rs.
//
// This file exists to pull enough of libtorrent through the linker that a
// clean link means the library really came along: session setup, torrent
// creation and hashing, the peer wire protocol, and the default disk backend.

#include "lt-spike/cpp/shim.h"
#include "lt-spike/src/lib.rs.h"

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/bencode.hpp>
#include <libtorrent/config.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/mmap_disk_io.hpp>
#include <libtorrent/posix_disk_io.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/session_params.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/version.hpp>

#include <boost/version.hpp>

#if defined TORRENT_USE_OPENSSL
#include <openssl/opensslv.h>
#endif

#include <sys/resource.h>
#include <sys/stat.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace lt_spike {
namespace {

using clock = std::chrono::steady_clock;

[[noreturn]] void fail(std::string const& what) { throw std::runtime_error(what); }

void make_dir(std::string const& path) {
  if (::mkdir(path.c_str(), 0755) != 0 && errno != EEXIST)
    fail("mkdir " + path + ": " + std::strerror(errno));
}

// Deterministic pseudo-random bytes, so a short read or a misplaced piece
// can't pass the final comparison by accident.
void write_payload(std::string const& path, std::uint64_t size) {
  std::ofstream out(path, std::ios::binary | std::ios::trunc);
  if (!out) fail("cannot create " + path);
  std::uint64_t x = 0x9e3779b97f4a7c15ull;
  std::vector<char> block(1 << 16);
  for (std::uint64_t written = 0; written < size;) {
    for (char& b : block) {
      x ^= x << 13;
      x ^= x >> 7;
      x ^= x << 17;
      b = static_cast<char>(x);
    }
    auto const n = std::min<std::uint64_t>(block.size(), size - written);
    out.write(block.data(), static_cast<std::streamsize>(n));
    written += n;
  }
  if (!out) fail("short write to " + path);
}

std::vector<char> read_file(std::string const& path) {
  std::ifstream in(path, std::ios::binary);
  if (!in) fail("cannot open " + path);
  return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

lt::settings_pack loopback_settings(TransferOptions const& opt) {
  lt::settings_pack p;
  // An OS-chosen port on loopback only: the spike must not touch the network.
  p.set_str(lt::settings_pack::listen_interfaces, "127.0.0.1:0");
  p.set_bool(lt::settings_pack::enable_dht, false);
  p.set_bool(lt::settings_pack::enable_lsd, false);
  p.set_bool(lt::settings_pack::enable_upnp, false);
  p.set_bool(lt::settings_pack::enable_natpmp, false);
  p.set_bool(lt::settings_pack::enable_outgoing_utp, false);
  p.set_bool(lt::settings_pack::enable_incoming_utp, false);
  auto mask = lt::alert_category::error | lt::alert_category::status | lt::alert_category::storage;
  if (opt.trace)
    mask |= lt::alert_category::connect | lt::alert_category::peer |
            lt::alert_category::performance_warning;
  p.set_int(lt::settings_pack::alert_mask, mask);
  if (opt.hashing_threads > 0) p.set_int(lt::settings_pack::hashing_threads, opt.hashing_threads);
  if (opt.aio_threads > 0) p.set_int(lt::settings_pack::aio_threads, opt.aio_threads);
  return p;
}

lt::disk_io_constructor_type disk_io_for(TransferOptions const& opt) {
  std::string const d(opt.disk_io);
  if (d.empty()) return lt::default_disk_io_constructor;
  if (d == "posix") return lt::posix_disk_io_constructor;
#if TORRENT_HAVE_MMAP
  if (d == "mmap") return lt::mmap_disk_io_constructor;
#endif
  fail("unknown disk_io \"" + d + "\" (mmap or posix)");
}

lt::create_flags_t torrent_format(TransferOptions const& opt) {
  std::string const t(opt.torrent);
  if (t.empty() || t == "hybrid") return {};
  if (t == "v1") return lt::create_torrent::v1_only;
  if (t == "v2") return lt::create_torrent::v2_only;
  fail("unknown torrent format \"" + t + "\" (hybrid, v1 or v2)");
}

lt::session_params loopback_params(TransferOptions const& opt) {
  lt::session_params params(loopback_settings(opt));
  params.disk_io_constructor = disk_io_for(opt);
  return params;
}

std::uint64_t ms_since(clock::time_point t0) {
  return static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(clock::now() - t0).count());
}

// User plus system CPU time of the whole process, every thread included.
std::uint64_t process_cpu_ms() {
  rusage ru{};
  ::getrusage(RUSAGE_SELF, &ru);
  auto const ms = [](timeval const& tv) {
    return static_cast<std::uint64_t>(tv.tv_sec) * 1000 + static_cast<std::uint64_t>(tv.tv_usec) / 1000;
  };
  return ms(ru.ru_utime) + ms(ru.ru_stime);
}

// Where alerts go once popped: recoverable failures into the report, and
// everything into stderr when tracing.
struct AlertSink {
  rust::Vec<rust::String>& warnings;
  bool trace;
  // libtorrent's clock, which alert timestamps use. It is not steady_clock
  // everywhere (libstdc++ makes high_resolution_clock the system clock).
  lt::time_point t0 = lt::clock_type::now();
};

// Throws on the alerts that mean the transfer cannot succeed. Waiting out
// the timeout instead would hide the one line of evidence the spike exists
// to collect. Returns the TCP listen port if this batch reported one.
int check_alerts(lt::session& s, char const* who, AlertSink& sink) {
  std::vector<lt::alert*> alerts;
  s.pop_alerts(&alerts);
  int port = 0;
  for (lt::alert const* a : alerts) {
    if (sink.trace) {
      auto const t = std::chrono::duration<double>(a->timestamp() - sink.t0).count();
      std::fprintf(stderr, "%9.3f %-5s %s: %s\n", t, who, a->what(), a->message().c_str());
    }
    if (auto const* ok = lt::alert_cast<lt::listen_succeeded_alert>(a)) {
      if (ok->socket_type == lt::socket_type_t::tcp) port = ok->port;
    } else if (auto const* lf = lt::alert_cast<lt::listen_failed_alert>(a)) {
      // libtorrent posts these when it cannot enumerate interfaces or routes,
      // then keeps going. Android builds for API 24+ have no route
      // enumeration at all (config.hpp turns netlink off), so enum_route
      // fails on every start there, and listening on an explicit 127.0.0.1
      // needs neither list. Any other listen failure is real.
      std::string const msg = std::string(who) + ": " + a->message();
      if (lf->op != lt::operation_t::enum_route && lf->op != lt::operation_t::enum_if) fail(msg);
      if (std::none_of(sink.warnings.begin(), sink.warnings.end(),
                       [&](rust::String const& w) { return std::string(w) == msg; }))
        sink.warnings.push_back(msg);
    } else if (lt::alert_cast<lt::torrent_error_alert>(a) ||
               lt::alert_cast<lt::file_error_alert>(a)) {
      fail(std::string(who) + ": " + a->message());
    }
  }
  return port;
}

void check_deadline(clock::time_point deadline, std::string const& waiting_for) {
  if (clock::now() > deadline) fail("timed out waiting for " + waiting_for);
}

}  // namespace

rust::String libtorrent_version() { return lt::version(); }

rust::String boost_version() {
  return std::to_string(BOOST_VERSION / 100000) + "." +
         std::to_string(BOOST_VERSION / 100 % 1000) + "." +
         std::to_string(BOOST_VERSION % 100);
}

rust::String crypto_backend() {
#if defined TORRENT_USE_OPENSSL
  return OPENSSL_VERSION_TEXT;
#else
  return "built-in SHA-1/SHA-256, no TLS (HTTPS trackers and web seeds unavailable)";
#endif
}

TransferReport loopback_transfer(rust::Str work_dir, std::uint64_t size_bytes,
                                 std::uint64_t timeout_ms, TransferOptions const& options) {
  if (size_bytes == 0) fail("size_bytes must be > 0");

  std::string const root(work_dir);
  std::string const seed_dir = root + "/seed";
  std::string const leech_dir = root + "/leech";
  std::string const name = "payload.bin";
  make_dir(root);
  make_dir(seed_dir);
  make_dir(leech_dir);
  // A complete copy left by an earlier run would make the leech a seed on
  // its first hash check, and the "transfer" would move zero bytes.
  std::remove((leech_dir + "/" + name).c_str());
  write_payload(seed_dir + "/" + name, size_bytes);

  TransferReport report{};
  lt::file_storage fs;
  lt::add_files(fs, seed_dir + "/" + name);
  lt::create_torrent ct(fs, 0, torrent_format(options));
  auto const hash_start = clock::now();
  auto const hash_cpu_start = process_cpu_ms();
  {
    // Same disk backend and thread counts as the sessions below.
    lt::error_code ec;
    lt::set_piece_hashes(ct, seed_dir, loopback_settings(options), disk_io_for(options),
                         [](lt::piece_index_t) {}, ec);
    if (ec) fail("hashing " + seed_dir + ": " + ec.message());
  }
  report.hash_ms = ms_since(hash_start);
  report.hash_cpu_ms = process_cpu_ms() - hash_cpu_start;
  std::vector<char> torrent;
  lt::bencode(std::back_inserter(torrent), ct.generate());
  auto const ti = std::make_shared<lt::torrent_info>(torrent, lt::from_span);

  report.bytes = size_bytes;
  report.piece_length = static_cast<std::uint32_t>(ti->piece_length());
  report.num_pieces = static_cast<std::uint32_t>(ti->num_pieces());
  {
    std::ostringstream hash;
    hash << ti->info_hashes().get_best();
    report.info_hash = hash.str();
  }

  auto const start = clock::now();
  auto const session_cpu_start = process_cpu_ms();
  auto const deadline = start + std::chrono::milliseconds(timeout_ms);
  clock::time_point teardown_start;
  AlertSink sink{report.warnings, options.trace};
  {
    lt::session seed(loopback_params(options));
    lt::session leech(loopback_params(options));

    int port = 0;
    while (port == 0) {
      check_deadline(deadline, "the seed to listen");
      seed.wait_for_alert(std::chrono::milliseconds(50));
      port = check_alerts(seed, "seed", sink);
    }

    // The default flags add a torrent paused and auto-managed, so it only
    // starts on the session's next tick (every 500 ms), and it already
    // reports is_seeding while paused. Start both torrents immediately.
    auto const started_now = lt::torrent_flags::paused | lt::torrent_flags::auto_managed;

    lt::add_torrent_params sp;
    sp.ti = ti;
    sp.save_path = seed_dir;
    sp.flags |= lt::torrent_flags::seed_mode;
    sp.flags &= ~started_now;
    lt::torrent_handle const seeding = seed.add_torrent(std::move(sp));
    // The leech gets one peer and no trackers. A paused seed drops its
    // connection, and the leech then waits min_reconnect_time (60 s) before
    // retrying, so wait until the seed is running, not just complete.
    for (;;) {
      lt::torrent_status const st = seeding.status();
      if (st.is_seeding && !(st.flags & lt::torrent_flags::paused)) break;
      check_deadline(deadline, "the seed torrent to start");
      seed.wait_for_alert(std::chrono::milliseconds(50));
      check_alerts(seed, "seed", sink);
    }

    lt::add_torrent_params lp;
    lp.ti = std::make_shared<lt::torrent_info>(*ti);
    lp.save_path = leech_dir;
    lp.flags &= ~started_now;
    lp.peers.emplace_back(lt::make_address_v4("127.0.0.1"),
                          static_cast<std::uint16_t>(port));
    auto const transfer_start = clock::now();
    lt::torrent_handle const downloading = leech.add_torrent(std::move(lp));

    for (;;) {
      check_alerts(seed, "seed", sink);
      check_alerts(leech, "leech", sink);
      lt::torrent_status const st = downloading.status();
      if (st.is_seeding) {
        report.transfer_ms = ms_since(transfer_start);
        break;
      }
      if (clock::now() > deadline) {
        fail("timed out with " + std::to_string(st.total_wanted_done) + " of " +
             std::to_string(size_bytes) + " bytes, " + std::to_string(st.num_peers) +
             " peer(s) connected");
      }
      leech.wait_for_alert(std::chrono::milliseconds(50));
    }
    // Leaving this scope destroys both sessions, which blocks until their
    // disk threads have flushed everything, so the comparison below reads
    // what libtorrent actually wrote.
    teardown_start = clock::now();
  }
  report.teardown_ms = ms_since(teardown_start);
  report.elapsed_ms = ms_since(start);
  report.session_cpu_ms = process_cpu_ms() - session_cpu_start;

  if (read_file(seed_dir + "/" + name) != read_file(leech_dir + "/" + name))
    fail("downloaded file differs from the seeded one");
  return report;
}

}  // namespace lt_spike
