#pragma once
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <fcntl.h>
#include <unistd.h>
#include <time.h>
#include <cerrno>
#include <sstream>
#include <string>
#include <vector>

// 只读取当前线程的调度现场；文件不可读时保留 errno，诊断不进入计时区间。
// Read only this thread's scheduling context; retain read errors and stay outside scored timing.
namespace bbdiag {
inline std::string quote(const std::string& input) {
    std::string out = "\"";
    for (unsigned char c : input) {
        if (c == '"' || c == '\\') { out += '\\'; out += char(c); }
        else if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else if (c == '\t') out += "\\t";
        else if (c >= 32) out += char(c);
    }
    return out + '"';
}
inline long long now() {
    timespec t{};
    return clock_gettime(CLOCK_MONOTONIC, &t) == 0 ? t.tv_sec * 1000000000LL + t.tv_nsec : 0;
}
inline std::string file(const std::string& path, bool status = false) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    int error = fd < 0 ? errno : 0;
    std::string value;
    bool truncated = false;
    if (fd >= 0) {
        char buffer[4097];
        ssize_t count;
        do { count = read(fd, buffer, sizeof(buffer)); } while (count < 0 && errno == EINTR);
        if (count < 0) error = errno;
        else { truncated = count > 4096; value.assign(buffer, static_cast<size_t>(count > 4096 ? 4096 : count)); }
        close(fd);
    }
    if (status) {
        std::istringstream lines(value); std::string line; value.clear();
        while (std::getline(lines, line)) {
            if (line.starts_with("State:") || line.starts_with("Cpus_allowed:") || line.starts_with("Cpus_allowed_list:") ||
                line.starts_with("Mems_allowed_list:") || line.starts_with("Seccomp:")) value += line + '\n';
        }
    }
    return "{\"errno\":" + std::to_string(error) + ",\"truncated\":" + (truncated ? "true" : "false") +
        ",\"value\":" + (error ? "null" : quote(value)) + "}";
}
inline std::string context() {
    const auto tid = syscall(SYS_gettid);
    const auto root = "/proc/self/task/" + std::to_string(tid) + '/';
    const auto stamp = now();
    const int cpu = sched_getcpu(), cpu_error = cpu < 0 ? errno : 0;
    const int policy = sched_getscheduler(0), policy_error = policy < 0 ? errno : 0;
    errno = 0; const int priority = getpriority(PRIO_PROCESS, 0), priority_error = errno;
    std::ostringstream s;
    s << "{\"monotonic_ns\":" << stamp << ",\"pid\":" << getpid() << ",\"tid\":" << tid << ",\"uid\":" << getuid()
      << ",\"observed_cpu\":" << cpu << ",\"getcpu_errno\":" << cpu_error
      << ",\"scheduler_policy\":" << policy << ",\"scheduler_errno\":" << policy_error
      << ",\"nice\":" << priority << ",\"nice_errno\":" << priority_error
      << ",\"thread_status\":" << file(root + "status", true) << ",\"cpuset\":" << file(root + "cpuset")
      << ",\"cgroup\":" << file(root + "cgroup") << ",\"online_cpus\":" << file("/sys/devices/system/cpu/online") << '}';
    return s.str();
}
struct Mask {
    cpu_set_t bits{};
    bool attempted = false;
    int result = -1, error = 0;
    long long stamp = 0;
    void query() {
        attempted = true; stamp = now(); CPU_ZERO(&bits);
        result = sched_getaffinity(0, sizeof(bits), &bits); error = result < 0 ? errno : 0;
    }
    std::string json() const {
        if (!attempted) return "null";
        std::ostringstream s;
        s << "{\"return_code\":" << result << ",\"errno\":" << error << ",\"monotonic_ns\":" << stamp
          << ",\"mask_bytes\":" << sizeof(bits) << ",\"cpu_count\":";
        if (result) s << "null,\"cpu_ids\":null}";
        else {
            s << CPU_COUNT(&bits) << ",\"cpu_ids\":["; bool first = true;
            for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) if (CPU_ISSET(cpu, &bits)) { if (!first) s << ','; first = false; s << cpu; }
            s << "]}";
        }
        return s.str();
    }
};
struct Attempt {
    int target = -1, set_result = -1, set_error = 0, start_cpu = -1, end_cpu = -1;
    bool set_attempted = false, injected = false;
    long long set_stamp = 0;
    Mask before, after;
    std::string before_context = "null", after_context = "null", reason;
    std::string pin(int cpu, int fault = 0) {
        target = cpu; before_context = context(); before.query();
        if (before.result != 0) { reason = "initial_query_failed"; return "AFFINITY_QUERY_FAILED"; }
        if (cpu < 0 || cpu >= CPU_SETSIZE || !CPU_ISSET(cpu, &before.bits)) {
            reason = "target_not_allowed"; return "AFFINITY_CPU_NOT_ALLOWED";
        }
        cpu_set_t mask; CPU_ZERO(&mask); CPU_SET(cpu, &mask);
        set_attempted = true; set_stamp = now();
        // 设置与即时回读之间不做文件 I/O；成功调用的 errno 固定为零。
        // No file I/O between set and immediate readback; errno is zero for successful calls.
        set_result = sched_setaffinity(0, sizeof(mask), &mask); set_error = set_result < 0 ? errno : 0;
        after.query();
#if defined(BENCHBRIDGE_TEST_HOOKS)
        injected = fault != 0;
        if (fault == 1) { after.result = -1; after.error = EIO; }
        if (fault == 2) { after.result = 0; after.error = 0; CPU_ZERO(&after.bits); }
        if (fault == 3) { set_result = -1; set_error = EPERM; }
#else
        (void)fault;
#endif
        after_context = context();
        if (set_result != 0) { reason = "set_failed"; return "AFFINITY_SET_FAILED:" + std::to_string(set_error); }
        if (after.result != 0) reason = "readback_failed";
        else if (CPU_COUNT(&after.bits) != 1) reason = "readback_not_singleton";
        else if (!CPU_ISSET(cpu, &after.bits)) reason = "readback_wrong_cpu";
        if (!reason.empty()) return "AFFINITY_VERIFY_FAILED";
        return "";
    }
    std::string json() const {
        std::ostringstream s;
        s << "{\"requested_cpu\":" << target << ",\"before\":" << before.json() << ",\"set\":";
        if (!set_attempted) s << "null";
        else s << "{\"return_code\":" << set_result << ",\"errno\":" << set_error << ",\"monotonic_ns\":" << set_stamp << '}';
        s << ",\"readback\":" << after.json() << ",\"before_context\":" << before_context << ",\"after_context\":" << after_context
          << ",\"observed_start_cpu\":" << start_cpu << ",\"observed_end_cpu\":" << end_cpu
          << ",\"failure_reason\":" << (reason.empty() ? "null" : quote(reason))
          << ",\"test_injected\":" << (injected ? "true" : "false") << '}';
        return s.str();
    }
};
struct Round {
    Mask caller;
    std::string caller_context = "null";
    std::vector<Attempt> workers;
    std::vector<int> requested;
    std::string json() const {
        std::ostringstream s;
        s << "{\"schema_version\":1,\"requested_cpus\":[";
        for (size_t i = 0; i < requested.size(); ++i) { if (i) s << ','; s << requested[i]; }
        s << "],\"caller_affinity\":" << caller.json() << ",\"caller_context\":" << caller_context << ",\"workers\":[";
        for (size_t i = 0; i < workers.size(); ++i) { if (i) s << ','; s << workers[i].json(); }
        s << "]}"; return s.str();
    }
};
inline std::string attach(std::string output, const std::string& diagnostics) {
    output.pop_back(); return output + ",\"affinity_diagnostics\":" + diagnostics + '}';
}
inline std::string environment() {
    utsname value{}; const int result = uname(&value), error = result < 0 ? errno : 0;
    return "{\"schema_version\":1,\"uname_errno\":" + std::to_string(error) + ",\"kernel_release\":" + quote(value.release) +
        ",\"kernel_version\":" + quote(value.version) + ",\"machine\":" + quote(value.machine) + ",\"thread\":" + context() + '}';
}
}
