#include "ram_kernels.h"
#include <jni.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
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
    bool repeat_small = false;
    int node_stride = 128, target_cpu = -1, start_cpu = -1, end_cpu = -1;
    std::string setup_error;
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
        const auto nodes = bytes / worker.node_stride;
        const auto stride_words = static_cast<std::size_t>(worker.node_stride / 8);
        const auto order = permutation(nodes, seed, session);
        for (std::size_t i = 0; i < nodes; ++i) {
            if ((i & 8191) == 0) check_cancel(session);
            worker.data.get()[order[i] * stride_words] = reinterpret_cast<std::uintptr_t>(
                worker.data.get() + order[(i + 1) % nodes] * stride_words);
        }
        worker.node = reinterpret_cast<std::uintptr_t*>(worker.data.get() + order[0] * stride_words);
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
        } else if (worker.repeat_small && kind <= Copy && worker.words < 32768) {
            const auto passes = std::max<std::size_t>(1, 32768 / worker.words);
            operations = worker.words * passes;
            if (kind == Read) checksum = bb_cached_read(worker.data.get(), worker.words, passes);
            else if (kind == Write) bb_cached_write(worker.data.get(), worker.words, worker.value, passes);
            else bb_cached_copy(worker.destination.get(), worker.data.get(), worker.words, passes);
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
        return node >= base && node < base + worker.words * 8 && (node - base) % worker.node_stride == 0;
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

std::string run_round(Session& session, int kind_value, jlong bytes, int threads, int warmup_ms, int duration_ms, std::uint64_t seed,
    const std::vector<jlong>& per_thread_bytes = {}, const std::vector<jint>& pinned_cpus = {}, int node_stride = 128) {
    const bool pinned = !pinned_cpus.empty();
    if (kind_value < Read || kind_value > Latency || bytes < (pinned ? 1024 : 1024 * 1024) ||
        bytes > (2LL << 30) || bytes % 256 != 0 || threads < 1 || threads > 16 ||
        (kind_value == Latency && threads != 1) || warmup_ms < 0 || warmup_ms > 10000 ||
        duration_ms < 50 || duration_ms > 30000) throw std::invalid_argument("PARAM_INVALID");
    if (node_stride < 32 || node_stride > 256 || (node_stride & (node_stride - 1))) throw std::invalid_argument("STRIDE_INVALID");
    if (pinned) {
        if (pinned_cpus.size() != static_cast<std::size_t>(threads) || per_thread_bytes.size() != pinned_cpus.size())
            throw std::invalid_argument("PLAN_SIZE_INVALID");
        cpu_set_t allowed;
        CPU_ZERO(&allowed);
        if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) throw std::runtime_error("AFFINITY_QUERY_FAILED");
        jlong sum = 0;
        for (int i = 0; i < threads; ++i) {
            if (pinned_cpus[i] < 0 || pinned_cpus[i] >= CPU_SETSIZE || !CPU_ISSET(pinned_cpus[i], &allowed))
                throw std::invalid_argument("AFFINITY_CPU_NOT_ALLOWED");
            if (std::find(pinned_cpus.begin(), pinned_cpus.begin() + i, pinned_cpus[i]) != pinned_cpus.begin() + i)
                throw std::invalid_argument("AFFINITY_DUPLICATE_CPU");
            if (per_thread_bytes[i] < 1024 || per_thread_bytes[i] > (2LL << 30) || per_thread_bytes[i] % 256)
                throw std::invalid_argument("WORKSET_INVALID");
            sum += per_thread_bytes[i];
        }
        if (sum != bytes) throw std::invalid_argument("WORKSET_SUM_MISMATCH");
    }
    check_cancel(session);
    session.phase.store(Preparing);
    const auto kind = static_cast<Kind>(kind_value);
    const auto buffer_bytes = static_cast<std::size_t>(bytes) / (kind == Copy ? 2 : 1);
    const auto blocks = buffer_bytes / 128;
    std::vector<Worker> workers(static_cast<std::size_t>(threads));
    for (int index = 0; index < threads; ++index) {
        const auto owned_blocks = blocks / threads + (static_cast<std::size_t>(index) < blocks % threads ? 1 : 0);
        workers[index].node_stride = node_stride;
        workers[index].repeat_small = pinned;
        workers[index].target_cpu = pinned ? pinned_cpus[index] : -1;
        if (!pinned) initialize(workers[index], kind, owned_blocks * 128,
            seed ^ (UINT64_C(0xD1B54A32D192ED03) * static_cast<std::uint64_t>(index + 1)), session);
    }
    const Timer timer;
    std::barrier<> phases(threads + 1);
    std::mutex gate_mutex;
    std::condition_variable gate_cv;
    bool gate_open = false, gate_abort = false;
    std::uint64_t warmup_deadline = 0, measure_deadline = 0;
    std::vector<std::thread> pool;
    std::atomic<bool> setup_failed{false};
    pool.reserve(threads);
    try {
        for (int index = 0; index < threads; ++index) {
            pool.emplace_back([&, index] {
                { std::unique_lock lock(gate_mutex); gate_cv.wait(lock, [&] { return gate_open; }); if (gate_abort) return; }
                if (pinned) {
                    try {
                        cpu_set_t mask;
                        CPU_ZERO(&mask); CPU_SET(pinned_cpus[index], &mask);
                        if (sched_setaffinity(0, sizeof(mask), &mask) != 0)
                            throw std::runtime_error("AFFINITY_SET_FAILED:" + std::to_string(errno));
                        CPU_ZERO(&mask);
                        if (sched_getaffinity(0, sizeof(mask), &mask) != 0 || CPU_COUNT(&mask) != 1 || !CPU_ISSET(pinned_cpus[index], &mask))
                            throw std::runtime_error("AFFINITY_VERIFY_FAILED");
                        // 绑核后再触页；缓存工作集和首次分配均属于所选核心。
                        // Pin before first touch so allocation and cache warmup use the selected core.
                        initialize(workers[index], kind, static_cast<std::size_t>(per_thread_bytes[index]) / (kind == Copy ? 2 : 1),
                            seed ^ (UINT64_C(0xD1B54A32D192ED03) * static_cast<std::uint64_t>(index + 1)), session);
                    } catch (const Cancelled&) { setup_failed = true; workers[index].setup_error = "RUN_CANCELLED"; }
                    catch (const std::exception& error) { setup_failed = true; workers[index].setup_error = error.what(); }
                    catch (...) { setup_failed = true; workers[index].setup_error = "WORKER_SETUP_FAILED"; }
                }
                phases.arrive_and_wait();
                if (setup_failed.load()) return;
                phases.arrive_and_wait();
                run_until(workers[index], kind, timer, warmup_deadline, false, session);
                workers[index].warmup_ops = workers[index].total_ops;
                phases.arrive_and_wait();
                phases.arrive_and_wait();
                workers[index].start_cpu = sched_getcpu();
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
                workers[index].end_cpu = sched_getcpu();
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
    phases.arrive_and_wait();
    if (setup_failed.load()) {
        for (auto& thread : pool) thread.join();
        check_cancel(session);
        for (const auto& worker : workers) if (!worker.setup_error.empty()) throw std::runtime_error(worker.setup_error);
        throw std::runtime_error("WORKER_SETUP_FAILED");
    }
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
        if (pinned && (worker.start_cpu != worker.target_cpu || worker.end_cpu != worker.target_cpu))
            throw std::runtime_error("AFFINITY_CPU_MIGRATED");
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
        << ",\"access_bytes\":8,\"node_stride_bytes\":" << (kind == Latency ? node_stride : 0)
        << ",\"independent_batch_width\":" << ((kind == RandomRead || kind == RandomWrite) ? 8 : 1)
        << ",\"kernel_id\":" << quoted(kind == Latency ? (pinned ? "pointer-chase-line-v2" : "pointer-chase-128-v1") :
            (kind == RandomRead || kind == RandomWrite) ? "indexed-independent-8-v1" : (pinned ? "simd-pinned-v2" : "simd-cached-v1"))
        << ",\"range_policy\":" << quoted(pinned ? "cache-domain-disjoint-v2" : "fixed-total-disjoint-v1")
        << ",\"affinity\":" << quoted(pinned ? "per-thread-verified" : "os-default")
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
    out << "],\"per_thread_working_set_bytes\":[";
    for (std::size_t i = 0; i < workers.size(); ++i) { if (i) out << ','; out << workers[i].words * 8 * (kind == Copy ? 2 : 1); }
    out << "],\"requested_cpus\":[";
    for (std::size_t i = 0; i < workers.size(); ++i) { if (i) out << ','; out << workers[i].target_cpu; }
    out << "],\"observed_start_cpus\":[";
    for (std::size_t i = 0; i < workers.size(); ++i) { if (i) out << ','; out << workers[i].start_cpu; }
    out << "],\"observed_end_cpus\":[";
    for (std::size_t i = 0; i < workers.size(); ++i) { if (i) out << ','; out << workers[i].end_cpu; }
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
    // Linux 向用户态提供的最小数据缓存行粒度；它不是缓存容量或共享关系。
    // Linux's user-visible minimum data-cache line granule is not a cache size or sharing map.
    long data_line = 0;
    const char* line_source = "unknown";
#if defined(__aarch64__)
    std::uint64_t ctr;
    asm volatile("mrs %0, ctr_el0" : "=r"(ctr));
    data_line = 4L << ((ctr >> 16) & 15);
    line_source = "runtime-ctr-el0-minimum";
#elif defined(_SC_LEVEL1_DCACHE_LINESIZE)
    data_line = sysconf(_SC_LEVEL1_DCACHE_LINESIZE);
    line_source = "runtime-sysconf";
#endif
    if (data_line < 32 || data_line > 256) { data_line = 0; line_source = "unknown"; }
    out << "{\"allowed_cpus\":" << cpu_count << ",\"page_size_bytes\":" << sysconf(_SC_PAGESIZE)
        << ",\"data_cache_line_bytes\":" << data_line << ",\"data_cache_line_source\":" << quoted(line_source)
        << ",\"timer\":" << quoted(timer.name) << ",\"affinity_mask_known\":" << (mask_known ? "true" : "false")
        << ",\"allowed_cpu_ids\":[";
    bool first = true;
    if (mask_known) for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) if (CPU_ISSET(cpu, &mask)) {
        if (!first) out << ',';
        first = false; out << cpu;
    }
    out << "]}";
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

extern "C" JNIEXPORT jstring JNICALL
Java_io_benchbridge_app_ram_RamNative_runPinnedRound(JNIEnv* env, jobject, jlong handle, jint kind,
    jlongArray worksets, jintArray cpu_ids, jint warmup_ms, jint duration_ms, jlong seed, jint node_stride) {
    std::string result;
    const auto session = session_for(handle);
    try {
        if (!session) throw std::invalid_argument("SESSION_NOT_FOUND");
        if (!worksets || !cpu_ids) throw std::invalid_argument("PLAN_MISSING");
        const auto count = env->GetArrayLength(worksets);
        if (count < 1 || count > 16 || env->GetArrayLength(cpu_ids) != count) throw std::invalid_argument("PLAN_SIZE_INVALID");
        std::vector<jlong> sizes(count); std::vector<jint> cpus(count);
        env->GetLongArrayRegion(worksets, 0, count, sizes.data());
        env->GetIntArrayRegion(cpu_ids, 0, count, cpus.data());
        jlong total = 0;
        for (auto size : sizes) { if (size < 1024 || size > (2LL << 30)) throw std::invalid_argument("WORKSET_INVALID"); total += size; }
        result = run_round(*session, kind, total, count, warmup_ms, duration_ms, static_cast<std::uint64_t>(seed), sizes, cpus, node_stride);
    } catch (const Cancelled&) { result = "{\"status\":\"INTERRUPTED\",\"error\":\"RUN_CANCELLED\"}"; }
    catch (const std::bad_alloc&) { result = "{\"status\":\"FAILED\",\"error\":\"RAM_ALLOC_FAILED\"}"; }
    catch (const std::exception& error) { result = "{\"status\":\"FAILED\",\"error\":" + quoted(error.what()) + '}'; }
    catch (...) { result = "{\"status\":\"FAILED\",\"error\":\"NATIVE_UNKNOWN_ERROR\"}"; }
    if (session) session->phase.store(Idle);
    return env->NewStringUTF(result.c_str());
}
