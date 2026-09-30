#include "ram_kernels.h"
#include <jni.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <barrier>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <numeric>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
enum Kind { Read, Write, Copy, RandomRead, RandomWrite, Latency };
enum Phase { Idle, Preparing, Warming, Measuring, Validating };
struct Session { std::atomic<bool> cancel{false}; std::atomic<int> phase{Idle}; };
std::mutex sessions_mutex;
std::unordered_map<std::uint64_t, std::shared_ptr<Session>> sessions;
std::uint64_t next_handle = 1;

std::shared_ptr<Session> session_for(jlong handle) {
    std::lock_guard lock(sessions_mutex);
    const auto found = sessions.find(static_cast<std::uint64_t>(handle));
    return found == sessions.end() ? nullptr : found->second;
}

std::string quoted(const std::string& value) {
    std::string result = "\"";
    for (const char c : value) {
        if (c == '\\' || c == '"') result += '\\';
        if (c == '\n') result += "\\n";
        else if (c == '\r') result += "\\r";
        else if (static_cast<unsigned char>(c) >= 0x20) result += c;
    }
    return result + '"';
}

struct Timer {
    clockid_t id = CLOCK_MONOTONIC;
    const char* name = "CLOCK_MONOTONIC";
    Timer() {
        timespec value{};
        if (clock_gettime(CLOCK_MONOTONIC_RAW, &value) == 0) {
            id = CLOCK_MONOTONIC_RAW; name = "CLOCK_MONOTONIC_RAW";
        }
    }
    std::uint64_t now() const noexcept {
        timespec value{};
        if (clock_gettime(id, &value) != 0) return 0;
        return static_cast<std::uint64_t>(value.tv_sec) * 1000000000ULL + value.tv_nsec;
    }
};

struct Cancelled {};
void check_cancel(const Session& session) { if (session.cancel.load(std::memory_order_relaxed)) throw Cancelled{}; }
struct FreeMemory { void operator()(std::uint64_t* pointer) const { std::free(pointer); } };
using Buffer = std::unique_ptr<std::uint64_t, FreeMemory>;
Buffer allocate(std::size_t words) {
    void* pointer = nullptr;
    const auto page = sysconf(_SC_PAGESIZE);
    const auto alignment = static_cast<std::size_t>(std::max(16384L, page));
    if (posix_memalign(&pointer, alignment, words * sizeof(std::uint64_t)) != 0) throw std::bad_alloc{};
    return Buffer(static_cast<std::uint64_t*>(pointer));
}

struct Random {
    std::uint64_t state;
    std::uint64_t next() {
        auto z = (state += UINT64_C(0x9e3779b97f4a7c15));
        z = (z ^ (z >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
        z = (z ^ (z >> 27)) * UINT64_C(0x94d049bb133111eb);
        return z ^ (z >> 31);
    }
    std::uint64_t bounded(std::uint64_t bound) {
        const auto threshold = -bound % bound;
        std::uint64_t value;
        do { value = next(); } while (value < threshold);
        return value % bound;
    }
};

std::vector<std::uint32_t> permutation(std::size_t count, std::uint64_t seed, const Session& session) {
    std::vector<std::uint32_t> indices(count);
    for (std::size_t i = 0; i < count; ++i) {
        if ((i & 8191) == 0) check_cancel(session);
        indices[i] = static_cast<std::uint32_t>(i);
    }
    Random random{seed};
    for (std::size_t i = count - 1; i > 0; --i) {
        if ((i & 8191) == 0) check_cancel(session);
        std::swap(indices[i], indices[random.bounded(i + 1)]);
    }
    return indices;
}

struct Worker {
    Buffer data;
    Buffer destination;
    std::vector<std::uint32_t> indices;
    std::uintptr_t* node = nullptr;
    std::size_t words = 0, cursor = 0;
    std::uint64_t value = 0, total_ops = 0, warmup_ops = 0, measured_ops = 0, checksum = 0, end_ns = 0;
    bool clock_failed = false;
};

void initialize(Worker& worker, Kind kind, std::size_t bytes, std::uint64_t seed, const Session& session) {
    worker.words = bytes / 8;
    worker.data = allocate(worker.words);
    worker.value = seed ^ UINT64_C(0x9E3779B97F4A7C15);
    if (kind == Copy) worker.destination = allocate(worker.words);
    for (std::size_t i = 0; i < worker.words; ++i) {
        if ((i & 8191) == 0) check_cancel(session);
        worker.data.get()[i] = (kind == Write || kind == RandomWrite) ? ~worker.value : worker.value;
        if (kind == Copy) worker.destination.get()[i] = ~worker.value;
    }
    if (kind == RandomRead || kind == RandomWrite) {
        worker.indices = permutation(worker.words, seed, session);
    } else if (kind == Latency) {
        const auto nodes = bytes / 128;
        const auto order = permutation(nodes, seed, session);
        for (std::size_t i = 0; i < nodes; ++i) {
            if ((i & 8191) == 0) check_cancel(session);
            worker.data.get()[order[i] * 16ULL] = reinterpret_cast<std::uintptr_t>(
                worker.data.get() + order[(i + 1) % nodes] * 16ULL);
        }
        worker.node = reinterpret_cast<std::uintptr_t*>(worker.data.get() + order[0] * 16ULL);
        auto* cursor = worker.node;
        for (std::size_t i = 0; i < nodes; ++i) {
            if ((i & 8191) == 0) check_cancel(session);
            cursor = reinterpret_cast<std::uintptr_t*>(*cursor);
            if (cursor == worker.node && i + 1 != nodes) throw std::runtime_error("CHAIN_SHORT_CYCLE");
        }
        if (cursor != worker.node) throw std::runtime_error("CHAIN_NOT_CLOSED");
    }
}

void run_until(Worker& worker, Kind kind, const Timer& timer, std::uint64_t deadline, bool measured, Session& session) {
    while (!session.cancel.load(std::memory_order_relaxed)) {
        const auto now = timer.now();
        if (now == 0) { worker.clock_failed = true; session.cancel.store(true); break; }
        if (now >= deadline) break;
        std::size_t operations;
        std::uint64_t checksum = 0;
        if (kind == Latency) {
            operations = 4096;
            worker.node = bb_chase(worker.node, operations);
        } else {
            const std::size_t batch = (kind == RandomRead || kind == RandomWrite) ? 4096 : 32768;
            operations = std::min(batch, worker.words - worker.cursor);
            switch (kind) {
                case Read: checksum = bb_seq_read(worker.data.get() + worker.cursor, operations); break;
                case Write: bb_seq_write(worker.data.get() + worker.cursor, operations, worker.value); break;
                case Copy: bb_copy(worker.destination.get() + worker.cursor, worker.data.get() + worker.cursor, operations); break;
                case RandomRead: checksum = bb_random_read(worker.data.get(), worker.indices.data() + worker.cursor, operations); break;
                case RandomWrite: bb_random_write(worker.data.get(), worker.indices.data() + worker.cursor, operations, worker.value); break;
                case Latency: break;
            }
            worker.cursor = (worker.cursor + operations) % worker.words;
        }
        worker.total_ops += operations;
        if (measured) { worker.measured_ops += operations; worker.checksum += checksum; }
    }
}

bool verify(const Worker& worker, Kind kind, const Session& session) {
    if (kind == Read || kind == RandomRead) return worker.checksum == worker.measured_ops * worker.value;
    if (kind == Latency) {
        const auto node = reinterpret_cast<std::uintptr_t>(worker.node);
        const auto base = reinterpret_cast<std::uintptr_t>(worker.data.get());
        return node >= base && node < base + worker.words * 8 && (node - base) % 128 == 0;
    }
    const auto touched = std::min<std::uint64_t>(worker.total_ops, worker.words);
    for (std::size_t i = 0; i < worker.words; ++i) {
        if ((i & 8191) == 0) check_cancel(session);
        const auto index = kind == RandomWrite ? worker.indices[i] : i;
        const auto expected = i < touched ? worker.value : ~worker.value;
        const auto actual = kind == Copy ? worker.destination.get()[index] : worker.data.get()[index];
        if (expected != actual) return false;
    }
    return true;
}

std::string run_round(Session& session, int kind_value, jlong bytes, int threads, int warmup_ms, int duration_ms, std::uint64_t seed) {
    if (kind_value < Read || kind_value > Latency || bytes < 1024 * 1024 ||
        bytes > (2LL << 30) || bytes % 256 != 0 || threads < 1 || threads > 16 ||
        (kind_value == Latency && threads != 1) || warmup_ms < 0 || warmup_ms > 10000 ||
        duration_ms < 50 || duration_ms > 30000) throw std::invalid_argument("PARAM_INVALID");
    check_cancel(session);
    session.phase.store(Preparing);
    const auto kind = static_cast<Kind>(kind_value);
    const auto buffer_bytes = static_cast<std::size_t>(bytes) / (kind == Copy ? 2 : 1);
    const auto blocks = buffer_bytes / 128;
    std::vector<Worker> workers(static_cast<std::size_t>(threads));
    for (int index = 0; index < threads; ++index) {
        const auto owned_blocks = blocks / threads + (static_cast<std::size_t>(index) < blocks % threads ? 1 : 0);
        initialize(workers[index], kind, owned_blocks * 128,
            seed ^ (UINT64_C(0xD1B54A32D192ED03) * static_cast<std::uint64_t>(index + 1)), session);
    }
    const Timer timer;
    std::barrier<> phases(threads + 1);
    std::mutex gate_mutex;
    std::condition_variable gate_cv;
    bool gate_open = false, gate_abort = false;
    std::uint64_t warmup_deadline = 0, measure_deadline = 0;
    std::vector<std::thread> pool;
    pool.reserve(threads);
    try {
        for (int index = 0; index < threads; ++index) {
            pool.emplace_back([&, index] {
                { std::unique_lock lock(gate_mutex); gate_cv.wait(lock, [&] { return gate_open; }); if (gate_abort) return; }
                phases.arrive_and_wait();
                run_until(workers[index], kind, timer, warmup_deadline, false, session);
                workers[index].warmup_ops = workers[index].total_ops;
                phases.arrive_and_wait();
                phases.arrive_and_wait();
                run_until(workers[index], kind, timer, measure_deadline, true, session);
                std::atomic_thread_fence(std::memory_order_seq_cst);
                // 结束计时前完成缓存写入；该屏障不保证数据已写回 DRAM。
                // Complete cached stores before timing ends; this barrier does not flush data to DRAM.
                if (kind == Write || kind == Copy || kind == RandomWrite) {
#if defined(__aarch64__)
                    asm volatile("dsb ish" ::: "memory");
#elif defined(__x86_64__)
                    asm volatile("mfence" ::: "memory");
#endif
                }
                workers[index].end_ns = timer.now();
            });
        }
    } catch (...) {
        { std::lock_guard lock(gate_mutex); gate_abort = true; gate_open = true; }
        gate_cv.notify_all();
        for (auto& thread : pool) thread.join();
        throw;
    }
    { std::lock_guard lock(gate_mutex); gate_open = true; }
    gate_cv.notify_all();
    session.phase.store(Warming);
    warmup_deadline = timer.now() + static_cast<std::uint64_t>(warmup_ms) * 1000000;
    phases.arrive_and_wait();
    phases.arrive_and_wait();
    session.phase.store(Measuring);
    const auto start_ns = timer.now();
    measure_deadline = start_ns + static_cast<std::uint64_t>(duration_ms) * 1000000;
    phases.arrive_and_wait();
    for (auto& thread : pool) thread.join();

    std::uint64_t end_ns = start_ns, operations = 0, checksum = 0;
    for (const auto& worker : workers) {
        if (worker.clock_failed || worker.end_ns == 0 || start_ns == 0) throw std::runtime_error("CLOCK_READ_FAILED");
        end_ns = std::max(end_ns, worker.end_ns);
        operations += worker.measured_ops;
        checksum ^= worker.checksum;
    }
    bool verified = true;
    const bool interrupted = session.cancel.load();
    session.phase.store(Validating);
    if (!interrupted) {
        for (const auto& worker : workers) verified = verified && verify(worker, kind, session);
    }
    if (!interrupted && (operations == 0 || end_ns <= start_ns)) throw std::runtime_error("EMPTY_MEASUREMENT");
    const std::string status = interrupted ? "INTERRUPTED" : verified ? "COMPLETED" : "FAILED";
    const auto payload = operations * 8;
    std::ostringstream out;
    out << std::boolalpha << "{\"status\":" << quoted(status)
        << ",\"kind\":" << kind_value << ",\"payload_bytes\":" << payload
        << ",\"logical_bytes\":" << (kind == Copy ? payload * 2 : payload)
        << ",\"operations\":" << operations << ",\"elapsed_ns\":" << (end_ns - start_ns)
        << ",\"requested_duration_ms\":" << duration_ms << ",\"warmup_ms\":" << warmup_ms
        << ",\"working_set_bytes\":" << bytes << ",\"threads\":" << threads
        << ",\"verified\":" << (verified && !interrupted)
        << ",\"timer\":" << quoted(timer.name) << ",\"seed\":" << seed
        << ",\"access_bytes\":8,\"node_stride_bytes\":" << (kind == Latency ? 128 : 0)
        << ",\"independent_batch_width\":" << ((kind == RandomRead || kind == RandomWrite) ? 8 : 1)
        << ",\"kernel_id\":" << quoted(kind == Latency ? "pointer-chase-128-v1" :
            (kind == RandomRead || kind == RandomWrite) ? "indexed-independent-8-v1" : "simd-cached-v1")
        << ",\"range_policy\":\"fixed-total-disjoint-v1\",\"affinity\":\"os-default\""
        << ",\"checksum\":" << quoted(std::to_string(checksum))
        << ",\"per_thread_operations\":[";
    for (std::size_t i = 0; i < workers.size(); ++i) {
        if (i) out << ',';
        out << workers[i].measured_ops;
    }
    out << "],\"per_thread_warmup_operations\":[";
    for (std::size_t i = 0; i < workers.size(); ++i) {
        if (i) out << ',';
        out << workers[i].warmup_ops;
    }
    out << "],\"error\":" << (verified ? "null" : "\"RAM_VERIFY_MISMATCH\"") << '}';
    session.phase.store(Idle);
    return out.str();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_benchbridge_app_ram_RamNative_createSession(JNIEnv* env, jobject) {
    try {
        std::lock_guard lock(sessions_mutex);
        if (!sessions.empty()) throw std::runtime_error("NATIVE_BUSY");
        const auto handle = next_handle++;
        sessions.emplace(handle, std::make_shared<Session>());
        return static_cast<jlong>(handle);
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Could not create native session");
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_io_benchbridge_app_ram_RamNative_cancelSession(JNIEnv*, jobject, jlong handle) {
    if (const auto session = session_for(handle)) session->cancel.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_io_benchbridge_app_ram_RamNative_releaseSession(JNIEnv*, jobject, jlong handle) {
    std::lock_guard lock(sessions_mutex);
    if (const auto found = sessions.find(handle); found != sessions.end()) { found->second->cancel.store(true); sessions.erase(found); }
}
extern "C" JNIEXPORT jint JNICALL
Java_io_benchbridge_app_ram_RamNative_phase(JNIEnv*, jobject, jlong handle) {
    const auto session = session_for(handle);
    return session ? session->phase.load() : Idle;
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_benchbridge_app_ram_RamNative_capabilities(JNIEnv* env, jobject) {
    cpu_set_t mask;
    CPU_ZERO(&mask);
    const bool mask_known = sched_getaffinity(0, sizeof(mask), &mask) == 0;
    const auto cpu_count = mask_known ? CPU_COUNT(&mask) : std::max(1L, sysconf(_SC_NPROCESSORS_ONLN));
    const Timer timer;
    std::ostringstream out;
    out << "{\"allowed_cpus\":" << cpu_count << ",\"page_size_bytes\":" << sysconf(_SC_PAGESIZE)
        << ",\"timer\":" << quoted(timer.name) << ",\"affinity_mask_known\":" << (mask_known ? "true" : "false") << '}';
    return env->NewStringUTF(out.str().c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_benchbridge_app_ram_RamNative_runRound(JNIEnv* env, jobject, jlong handle, jint kind, jlong bytes,
    jint threads, jint warmup_ms, jint duration_ms, jlong seed) {
    std::string result;
    const auto session = session_for(handle);
    try {
        if (!session) throw std::invalid_argument("SESSION_NOT_FOUND");
        result = run_round(*session, kind, bytes, threads, warmup_ms, duration_ms, static_cast<std::uint64_t>(seed));
    } catch (const Cancelled&) {
        result = "{\"status\":\"INTERRUPTED\",\"error\":\"RUN_CANCELLED\"}";
    } catch (const std::bad_alloc&) {
        result = "{\"status\":\"FAILED\",\"error\":\"RAM_ALLOC_FAILED\"}";
    } catch (const std::exception& error) {
        result = "{\"status\":\"FAILED\",\"error\":" + quoted(error.what()) + '}';
    } catch (...) {
        result = "{\"status\":\"FAILED\",\"error\":\"NATIVE_UNKNOWN_ERROR\"}";
    }
    if (session) session->phase.store(Idle);
    return env->NewStringUTF(result.c_str());
}
