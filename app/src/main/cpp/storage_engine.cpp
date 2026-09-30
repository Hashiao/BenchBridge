#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <barrier>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <linux/aio_abi.h>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {
using U = std::uint64_t;
constexpr U MiB = 1048576, PoolBytes = 64 * MiB, Seed = 0xB16B00B5ULL;
U now() { timespec ts{}; if (clock_gettime(CLOCK_MONOTONIC, &ts)) throw std::runtime_error("CLOCK_FAILED"); return U(ts.tv_sec) * 1000000000 + ts.tv_nsec; }
std::string quote(const std::string& s) {
    std::string o = "\""; for (unsigned char c : s) { if (c == '"' || c == '\\') o += '\\'; if (c >= 32) o += char(c); } return o + '"';
}
std::string errorJson(const std::string& error) { return "{\"status\":\"FAILED\",\"error\":" + quote(error) + "}"; }
void fail(const char* action) { throw std::runtime_error(std::string(action) + ":" + std::to_string(errno)); }
struct Fd {
    int n = -1; explicit Fd(int v = -1) : n(v) {} ~Fd() { if (n >= 0) close(n); }
    Fd(const Fd&) = delete; Fd& operator=(const Fd&) = delete;
};
struct Buffer {
    void* p = nullptr; explicit Buffer(std::size_t n) { if (posix_memalign(&p, 65536, n)) throw std::bad_alloc(); }
    ~Buffer() { free(p); } Buffer(const Buffer&) = delete; Buffer& operator=(const Buffer&) = delete;
};
U mix(U z) { z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL; z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL; return z ^ (z >> 31); }
U wordAt(U i) { return mix(Seed + (i + 1) * 0x9e3779b97f4a7c15ULL); }
U randomU(U& state, U count) {
    const U threshold = -count % count; U r;
    do { state += 0x9e3779b97f4a7c15ULL; r = mix(state); } while (r < threshold);
    return r % count;
}
struct Session {
    std::string dir; U bytes, budget; Fd directory, lock;
    std::atomic<bool> cancelled{false}, quota{false};
    std::atomic<U> reserved{0}, written{0}, prepared{0};
    std::atomic<int> phase{0}; std::unique_ptr<Buffer> pool;
    Session(std::string path, U size, U limit) : dir(std::move(path)), bytes(size), budget(limit == 0 ? std::numeric_limits<U>::max() : limit),
        directory(open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)),
        lock(directory.n < 0 ? -1 : openat(directory.n, "active.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600)) {
        if (directory.n < 0 || lock.n < 0) fail("OPEN_RUN");
        if (flock(lock.n, LOCK_EX | LOCK_NB)) fail("LOCK_RUN");
    }
    bool reserve(U n) {
        U old = reserved.load();
        do { if (n > budget || old > budget - n) { quota = true; return false; } } while (!reserved.compare_exchange_weak(old, old + n));
        return true;
    }
    void makePool() {
        if (pool) return;
        pool = std::make_unique<Buffer>(PoolBytes);
        auto* p = static_cast<U*>(pool->p); for (U i = 0; i < PoolBytes / 8; ++i) p[i] = wordAt(i);
    }
    void fill(void* p, U offset, U size) const {
        auto* dst = static_cast<char*>(p); auto* src = static_cast<const char*>(pool->p);
        while (size) { U start = offset % PoolBytes, n = std::min(size, PoolBytes - start); std::memcpy(dst, src + start, n); size -= n; offset += n; dst += n; }
    }
};
std::mutex sessionsMutex;
std::map<jlong, std::shared_ptr<Session>> sessions;
jlong nextId = 1;
std::shared_ptr<Session> get(jlong id) { std::lock_guard guard(sessionsMutex); auto it = sessions.find(id); if (it == sessions.end()) throw std::runtime_error("SESSION_NOT_FOUND"); return it->second; }

std::string prepare(Session& s) {
    s.phase = 1; s.makePool();
    Fd fd(openat(s.directory.n, "data-000.bin", O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (fd.n < 0) fail("CREATE_DATA");
    Buffer b(MiB);
    for (U offset = 0; offset < s.bytes;) {
        if (s.cancelled || !s.reserve(std::min(MiB, s.bytes - offset))) break;
        U count = std::min(MiB, s.bytes - offset); s.fill(b.p, offset, count);
        ssize_t result; do { result = pwrite(fd.n, b.p, count, off_t(offset)); } while (result < 0 && errno == EINTR && !s.cancelled);
        if (result > 0) { s.written += U(result); offset += U(result); s.prepared = offset; }
        if (result != ssize_t(count)) { if (s.cancelled) break; fail("INITIALIZE_SHORT_WRITE"); }
    }
    if (fdatasync(fd.n)) fail("INITIALIZE_SYNC");
    if (fsync(s.directory.n)) fail("SYNC_DIRECTORY");
    return std::string("{\"status\":\"") + (s.prepared == s.bytes ? "COMPLETED" : "INTERRUPTED") + "\",\"prepared_bytes\":" + std::to_string(s.prepared) + "}";
}

// io_destroy 会等待未完成请求，必须先于缓冲区和文件描述符析构。
// io_destroy drains pending requests and must run before buffers and file descriptors are destroyed.
struct Aio {
    aio_context_t ctx = 0;
    explicit Aio(unsigned depth) { if (syscall(__NR_io_setup, depth, &ctx) < 0) fail("IO_SETUP"); }
    ~Aio() { if (ctx) { while (syscall(__NR_io_destroy, ctx) < 0 && errno == EINTR) {} } }
    Aio(const Aio&) = delete; Aio& operator=(const Aio&) = delete;
};
struct Histogram {
    std::array<U, 4096> bins{};
    U count = 0, sum = 0, maximum = 0;
    void add(U n) {
        unsigned exp = n ? 63U - unsigned(__builtin_clzll(n)) : 0;
        U base = U(1) << exp;
        unsigned sub = unsigned(((n - (n ? base : 0)) * 64) / base);
        unsigned index = std::min(4095U, exp * 64 + sub);
        ++bins[index]; ++count; sum += n; maximum = std::max(maximum, n);
    }
    U percentile(double fraction) const {
        U threshold = U(std::ceil(count * fraction)), cumulative = 0;
        for (unsigned i = 0; i < bins.size(); ++i) {
            cumulative += bins[i]; if (cumulative >= threshold) {
                U base = U(1) << (i / 64); return std::min(maximum, base + base / 64 * ((i % 64) + 1));
            }
        }
        return maximum;
    }
    void merge(const Histogram& other) { count += other.count; sum += other.sum; maximum = std::max(maximum, other.maximum); for (unsigned i = 0; i < bins.size(); ++i) bins[i] += other.bins[i]; }
};
struct Stats {
    U operations = 0, bytes = 0, end = 0, submitNs = 0, depthArea = 0, lastDepthAt = 0;
    unsigned depth = 0, maxDepth = 0; Histogram latency; std::string error;
    std::array<U, 65> depthTime{};
    void depthChange(int delta, U t) { U span = t - lastDepthAt; depthArea += U(depth) * span; depthTime[depth] += span; lastDepthAt = t; depth = unsigned(int(depth) + delta); maxDepth = std::max(maxDepth, depth); }
};
struct Slot { Buffer buffer; iocb cb{}; U started = 0; bool pending = false; explicit Slot(std::size_t size) : buffer(size) {} };
struct Worker {
    Fd fd; std::vector<std::unique_ptr<Slot>> slots;
    // 最后声明的资源先析构，确保 AIO 先排空。
    // Declare AIO last so destruction drains it before other resources.
    std::unique_ptr<Aio> aio;
    U firstBlock, blocks, cursor = 0, rng; Stats stats;
    Worker(Session& s, int block, int q, int threads, int index, bool direct, bool write) :
        fd(openat(s.directory.n, "data-000.bin", (write ? O_RDWR : O_RDONLY) | O_NOFOLLOW | O_CLOEXEC | (direct ? O_DIRECT : 0))),
        firstBlock((s.bytes / block) * index / threads), blocks((s.bytes / block) * (index + 1) / threads - firstBlock), rng(Seed + index) {
        if (fd.n < 0) fail("OPEN_IO");
        for (int i = 0; i < q; ++i) slots.push_back(std::make_unique<Slot>(block));
        if (q > 1) aio = std::make_unique<Aio>(q);
    }
};

void work(Session& s, Worker& w, int block, bool random, bool write, U start, U deadline,
          std::atomic<unsigned>& globalDepth, std::atomic<unsigned>& maxGlobal) {
    Stats& st = w.stats; st.lastDepthAt = start; st.end = start;
    auto change = [&](int delta, U timestamp) {
        st.depthChange(delta, timestamp);
        unsigned depth = delta > 0 ? globalDepth.fetch_add(1) + 1 : globalDepth.fetch_sub(1) - 1;
        unsigned old = maxGlobal.load(); while (depth > old && !maxGlobal.compare_exchange_weak(old, depth)) {}
    };
    auto complete = [&](Slot& slot, long result, long secondary) {
        U end = now(); change(-1, end); slot.pending = false;
        if (result > 0 && write) s.written += U(result);
        if (result != block || secondary != 0) {
            st.error = "IO_COMPLETION:" + std::to_string(result) + ":" + std::to_string(secondary); s.cancelled = true; return;
        }
        ++st.operations; st.bytes += block; st.end = end; st.latency.add(end - slot.started);
    };
    bool finished = false;
    while (!finished || st.depth) {
        finished = finished || s.cancelled || s.quota || now() >= deadline;
        bool submitted = false;
        if (!finished) for (auto& entry : w.slots) {
            auto& slot = *entry; if (slot.pending) continue;
            if (s.cancelled || s.quota || now() >= deadline) { finished = true; break; }
            U selected = random ? randomU(w.rng, w.blocks) : w.cursor++ % w.blocks;
            U offset = (w.firstBlock + selected) * U(block);
            if (write) { if (!s.reserve(block)) { finished = true; break; } s.fill(slot.buffer.p, offset, block); }
            // 缓冲区填充计入总吞吐耗时，但位于单请求延迟计时之前。
            // Buffer construction counts toward throughput time, before per-request latency timing.
            slot.started = now();
            if (slot.started >= deadline || s.cancelled) { if (write) s.reserved -= block; finished = true; break; }
            if (!w.aio) {
                slot.pending = true; change(1, slot.started);
                ssize_t result;
                do { result = write ? pwrite(w.fd.n, slot.buffer.p, block, off_t(offset)) : pread(w.fd.n, slot.buffer.p, block, off_t(offset)); } while (result < 0 && errno == EINTR && !s.cancelled);
                int saved = errno; st.submitNs += now() - slot.started;
                complete(slot, result < 0 ? -saved : result, 0); submitted = true;
            } else {
                slot.cb = {}; slot.cb.aio_data = reinterpret_cast<U>(&slot); slot.cb.aio_fildes = unsigned(w.fd.n);
                slot.cb.aio_lio_opcode = write ? IOCB_CMD_PWRITE : IOCB_CMD_PREAD;
                slot.cb.aio_buf = reinterpret_cast<U>(slot.buffer.p); slot.cb.aio_nbytes = block; slot.cb.aio_offset = off_t(offset);
                iocb* list[] = { &slot.cb };
                long result = syscall(__NR_io_submit, w.aio->ctx, 1, list); int saved = errno;
                st.submitNs += now() - slot.started;
                if (result == 1) { slot.pending = true; change(1, now()); submitted = true; }
                else {
                    if (write) s.reserved -= block;
                    if (result < 0 && (saved == EINTR || saved == EAGAIN)) { std::this_thread::yield(); break; }
                    st.error = "IO_SUBMIT:" + std::to_string(saved); s.cancelled = true; finished = true; break;
                }
            }
        }
        if (w.aio && st.depth) {
            std::array<io_event, 64> events{}; timespec timeout{0, 50000000};
            long result = syscall(__NR_io_getevents, w.aio->ctx, 1, w.slots.size(), events.data(), &timeout);
            if (result < 0 && errno != EINTR) { st.error = "IO_GETEVENTS:" + std::to_string(errno); s.cancelled = true; break; }
            for (long i = 0; i < result; ++i) complete(*reinterpret_cast<Slot*>(events[i].data), events[i].res, events[i].res2);
        } else if (!submitted && !finished) std::this_thread::yield();
    }
    if (st.depth) {
        // 即使出现错误，也先等待内核释放请求，再回收或复用缓冲区。
        // Drain kernel-owned requests before freeing or reusing buffers, including on errors.
        w.aio.reset(); st.error = st.error.empty() ? "AIO_DRAIN_FAILED" : st.error;
    }
}

bool verify(Session& s) {
    Fd fd(openat(s.directory.n, "data-000.bin", O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    if (fd.n < 0) fail("VERIFY_OPEN");
    Buffer data(4096), expected(4096);
    for (U i = 0; i < 16; ++i) {
        U off = (s.bytes / 4096 - 1) * i / 15 * 4096;
        if (pread(fd.n, data.p, 4096, off_t(off)) != 4096) return false;
        s.fill(expected.p, off, 4096); if (std::memcmp(data.p, expected.p, 4096)) return false;
    }
    return true;
}

std::string run(Session& s, bool random, bool write, int block, int queue, int threads, int warmMs, int durationMs, bool direct) {
    if (block < 4096 || block > 4 * int(MiB) || block % 4096 || queue < 1 || queue > 64 || threads < 1 || threads > 16 || queue * threads > 512 || U(block) * queue * threads > 256 * MiB || s.bytes / block < U(threads) * 2 || durationMs < 50 || durationMs > 30000 || warmMs < 0 || warmMs > 10000 || s.prepared != s.bytes) return errorJson("PARAM_INVALID");
    if (s.cancelled || s.quota) return "{\"status\":\"INTERRUPTED\",\"error\":\"CANCELLED_OR_BUDGET\"}";
    s.makePool(); s.phase = 2;
    std::vector<std::unique_ptr<Worker>> workers;
    for (int i = 0; i < threads; ++i) workers.push_back(std::make_unique<Worker>(s, block, queue, threads, i, direct, write));
    auto stage = [&](int ms) -> std::pair<U,U> {
        std::atomic<unsigned> globalDepth{0}, maximumDepth{0};
        U start = 0;
        std::barrier ready(threads + 1, [&]() noexcept { start = now(); });
        std::vector<std::thread> jobs;
        try {
            for (auto& ptr : workers) {
                auto* w = ptr.get(); w->stats = {};
                jobs.emplace_back([&, w] {
                    ready.arrive_and_wait();
                    try { work(s, *w, block, random, write, start, start + U(ms) * 1000000, globalDepth, maximumDepth); }
                    catch (const std::exception& e) { w->stats.error = e.what(); s.cancelled = true; w->aio.reset(); }
                });
            }
        } catch (...) {
            s.cancelled = true;
            for (std::size_t missing = jobs.size(); missing < std::size_t(threads); ++missing) ready.arrive_and_drop();
            ready.arrive_and_wait(); for (auto& job : jobs) job.join(); throw;
        }
        ready.arrive_and_wait(); for (auto& job : jobs) job.join();
        return {start, maximumDepth.load()};
    };
    U warmOps = 0;
    if (warmMs) { stage(warmMs); for (auto& w : workers) { warmOps += w->stats.operations; if (!w->stats.error.empty()) return errorJson(w->stats.error); } }
    if (s.cancelled || s.quota) return "{\"status\":\"INTERRUPTED\",\"error\":\"CANCELLED_OR_BUDGET\"}";
    s.phase = 3;
    const auto [start, maxDepth] = stage(durationMs);
    U end = start, ops = 0, bytes = 0, submitNs = 0, depthArea = 0; Histogram histogram; std::string error;
    for (auto& w : workers) {
        end = std::max(end, w->stats.end); ops += w->stats.operations; bytes += w->stats.bytes;
        submitNs += w->stats.submitNs; depthArea += w->stats.depthArea; histogram.merge(w->stats.latency);
        if (!w->stats.error.empty()) error = w->stats.error;
    }
    s.phase = 4; U flush = 0;
    if (write) { U begin = now(); if (fdatasync(workers[0]->fd.n)) error = "FDATASYNC:" + std::to_string(errno); flush = now() - begin; }
    s.phase = 5;
    const bool verified = error.empty() && verify(s);
    if (!verified && error.empty()) error = "DATA_VERIFY_FAILED";
    const char* status = !error.empty() ? "FAILED" : (s.cancelled || s.quota) ? "INTERRUPTED" : ops ? "COMPLETED" : "FAILED";
    if (!ops && error.empty() && !s.cancelled && !s.quota) error = "NO_COMPLETED_IO";
    U elapsed = std::max(U(1), end - start);
    std::ostringstream o;
    o << "{\"status\":" << quote(status) << ",\"verified\":" << (verified ? "true" : "false")
      << ",\"error\":" << (error.empty() ? "null" : quote(error)) << ",\"operations\":" << ops
      << ",\"completed_bytes\":" << bytes << ",\"elapsed_ns\":" << elapsed
      << ",\"configured_duration_ms\":" << durationMs << ",\"warmup_operations\":" << warmOps
      << ",\"warmup_ms\":" << warmMs << ",\"block_bytes\":" << block << ",\"queue_per_thread\":" << queue
      << ",\"threads\":" << threads << ",\"requested_qd\":" << queue * threads
      << ",\"observed_application_qd_max\":" << maxDepth << ",\"application_qd_mean\":" << double(depthArea) / elapsed
      << ",\"submission_time_ns\":" << submitNs << ",\"flush_ns_separate\":" << flush
      << ",\"latency_mean_ns\":" << (ops ? double(histogram.sum) / ops : 0)
      << ",\"latency_p50_ns\":" << histogram.percentile(.50) << ",\"latency_p95_ns\":" << histogram.percentile(.95)
      << ",\"latency_p99_ns\":" << histogram.percentile(.99) << ",\"latency_max_ns\":" << histogram.maximum
      << ",\"latency_sum_ns\":" << histogram.sum << ",\"latency_histogram_scheme\":\"log2-64-upper-bound-ns\",\"latency_histogram\":[";
    bool first = true; for (unsigned i = 0; i < histogram.bins.size(); ++i) if (histogram.bins[i]) { if (!first) o << ','; first = false; o << '[' << i << ',' << histogram.bins[i] << ']'; }
    o << "],\"per_thread_qd_time_ns\":[";
    for (std::size_t i = 0; i < workers.size(); ++i) {
        if (i) o << ',';
        o << '[';
        auto times = workers[i]->stats.depthTime;
        if (end > workers[i]->stats.lastDepthAt) times[0] += end - workers[i]->stats.lastDepthAt;
        for (int d = 0; d <= queue; ++d) { if (d) o << ','; o << times[d]; }
        o << ']';
    }
    o << "],\"per_thread\":[";
    for (std::size_t i = 0; i < workers.size(); ++i) {
        const auto& w = *workers[i]; if (i) o << ',';
        o << "{\"index\":" << i << ",\"first_block\":" << w.firstBlock << ",\"block_count\":" << w.blocks
          << ",\"operations\":" << w.stats.operations << ",\"completed_bytes\":" << w.stats.bytes
          << ",\"submission_ns\":" << w.stats.submitNs << ",\"last_completion_offset_ns\":" << (w.stats.end - start) << '}';
    }
    o << "],\"engine\":" << quote(queue == 1 ? "pread-pwrite" : "linux-native-aio")
      << ",\"cache_mode\":" << quote(direct ? "O_DIRECT" : "buffered")
      << ",\"timer\":\"CLOCK_MONOTONIC\",\"verification\":\"16-position-4k-pattern-sample\",\"write_budget_exhausted\":" << (s.quota ? "true" : "false") << '}';
    return o.str();
}

std::string probe(const std::string& dir, bool aio) {
    Session s(dir, 8 * MiB, 16 * MiB);
    if (!aio) {
        prepare(s);
        bool read = false, write = false; int readError = 0, writeError = 0;
        Fd fd(openat(s.directory.n, "data-000.bin", O_RDWR | O_DIRECT | O_CLOEXEC | O_NOFOLLOW));
        if (fd.n >= 0) {
            Buffer b(4096); s.fill(b.p, 0, 4096); errno = 0;
            read = pread(fd.n, b.p, 4096, 0) == 4096 && pread(fd.n, b.p, 4096, 4096) == 4096; readError = errno;
            s.fill(b.p, 4096, 4096); errno = 0; write = pwrite(fd.n, b.p, 4096, 4096) == 4096; writeError = errno;
            if (write && fdatasync(fd.n)) { write = false; writeError = errno; }
        } else readError = writeError = errno;
        return std::string("{\"buffered\":true,\"direct_read\":") + (read ? "true" : "false") + ",\"direct_write\":" + (write ? "true" : "false") + ",\"direct_read_errno\":" + std::to_string(readError) + ",\"direct_write_errno\":" + std::to_string(writeError) + '}';
    }
    // 能力探测复用已完整初始化的文件。
    // Capability probing reuses an already initialized file.
    s.prepared = s.bytes;
    auto result = run(s, true, false, 4096, 32, 1, 0, 50, true);
    return result;
}

std::string cleanup(const std::string& root, const std::string& name, const std::string& expected) {
    if (name.size() != 43 || name.rfind("bb-run-", 0) != 0 || name.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789-", 7) != std::string::npos || expected.size() > 8192) return "{\"state\":\"REFUSED\",\"error\":\"INVALID_OWNER\"}";
    Fd base(open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)); if (base.n < 0) fail("CLEANUP_ROOT");
    Fd dir(openat(base.n, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (dir.n < 0) { if (errno == ENOENT) return "{\"state\":\"CLEANED\"}"; fail("CLEANUP_RUN"); }
    Fd lock(openat(dir.n, "active.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (lock.n < 0 || flock(lock.n, LOCK_EX | LOCK_NB)) return "{\"state\":\"PENDING\",\"error\":\"ACTIVE_OR_LOCK_FAILED\"}";
    DIR* list = fdopendir(dup(dir.n)); if (!list) fail("LIST_RUN");
    std::vector<std::string> names; bool safe = true;
    while (auto* e = readdir(list)) {
        std::string n(e->d_name); if (n == "." || n == "..") continue;
        struct stat st{};
        if ((n != "owner.json" && n != "owner.json.new" && n != "data-000.bin" && n != "active.lock") || fstatat(dir.n, n.c_str(), &st, AT_SYMLINK_NOFOLLOW) || !S_ISREG(st.st_mode) || st.st_nlink != 1) { safe = false; break; }
        names.push_back(n);
    }
    closedir(list);
    if (!safe) return "{\"state\":\"REFUSED\",\"error\":\"UNKNOWN_ENTRY_OR_SYMLINK\"}";
    Fd owner(openat(dir.n, "owner.json", O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    if (owner.n < 0) {
        if (std::find(names.begin(), names.end(), "data-000.bin") != names.end()) return "{\"state\":\"REFUSED\",\"error\":\"OWNER_MISSING\"}";
    } else {
        std::array<char, 8193> buffer{}; ssize_t n = read(owner.n, buffer.data(), buffer.size());
        if (n < 0 || std::string(buffer.data(), std::size_t(n)) != expected) return "{\"state\":\"REFUSED\",\"error\":\"OWNER_MISMATCH\"}";
    }
    for (const char* n : {"data-000.bin", "owner.json.new", "owner.json", "active.lock"}) if (unlinkat(dir.n, n, 0) && errno != ENOENT) fail("UNLINK_OWNED_FILE");
    if (unlinkat(base.n, name.c_str(), AT_REMOVEDIR)) fail("REMOVE_OWNED_DIR");
    if (fsync(base.n)) fail("SYNC_CLEANUP");
    return "{\"state\":\"CLEANED\"}";
}
std::string fromJava(JNIEnv* env, jstring value) { if (!value) throw std::runtime_error("NULL_STRING"); const char* p = env->GetStringUTFChars(value, nullptr); if (!p) throw std::bad_alloc(); std::string s(p); env->ReleaseStringUTFChars(value, p); return s; }
template<class F> jstring json(JNIEnv* env, F f) { try { return env->NewStringUTF(f().c_str()); } catch (const std::exception& e) { return env->NewStringUTF(errorJson(e.what()).c_str()); } }
}

extern "C" JNIEXPORT jlong JNICALL Java_io_benchbridge_app_storage_StorageNative_createSession(JNIEnv* env, jobject, jstring path, jlong bytes, jlong budget) {
    try {
        if (bytes < 8 * jlong(MiB) || bytes > 65536LL * jlong(MiB) || bytes % 4096 || budget < 0 || (budget > 0 && budget < bytes) || budget > 1048576LL * jlong(MiB)) throw std::runtime_error("PARAM_INVALID");
        std::lock_guard guard(sessionsMutex); if (!sessions.empty()) throw std::runtime_error("NATIVE_BUSY");
        auto s = std::make_shared<Session>(fromJava(env, path), bytes, budget); jlong id = nextId++; sessions.emplace(id, std::move(s)); return id;
    } catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what()); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_storage_StorageNative_prepare(JNIEnv* env, jobject, jlong id) { return json(env, [&] { return prepare(*get(id)); }); }
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_storage_StorageNative_runRound(JNIEnv* env, jobject, jlong id, jboolean random, jboolean write, jint block, jint q, jint t, jint warm, jint duration, jboolean direct) { return json(env, [&] { return run(*get(id), random, write, block, q, t, warm, duration, direct); }); }
extern "C" JNIEXPORT void JNICALL Java_io_benchbridge_app_storage_StorageNative_cancelSession(JNIEnv*, jobject, jlong id) { try { get(id)->cancelled = true; } catch (...) {} }
extern "C" JNIEXPORT void JNICALL Java_io_benchbridge_app_storage_StorageNative_releaseSession(JNIEnv*, jobject, jlong id) { std::lock_guard guard(sessionsMutex); sessions.erase(id); }
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_storage_StorageNative_progress(JNIEnv* env, jobject, jlong id) { return json(env, [&] { auto s = get(id); return "{\"native_phase\":" + std::to_string(s->phase) + ",\"prepared_bytes\":" + std::to_string(s->prepared) + ",\"written_bytes\":" + std::to_string(s->written) + ",\"write_reserved_bytes\":" + std::to_string(s->reserved) + ",\"budget_exhausted\":" + (s->quota ? "true" : "false") + '}'; }); }
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_storage_StorageNative_probe(JNIEnv* env, jobject, jstring path, jboolean aio) { return json(env, [&] { return probe(fromJava(env, path), aio); }); }
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_storage_StorageNative_cleanup(JNIEnv* env, jobject, jstring root, jstring name, jstring owner) { return json(env, [&] { return cleanup(fromJava(env, root), fromJava(env, name), fromJava(env, owner)); }); }
