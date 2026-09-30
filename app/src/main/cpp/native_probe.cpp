#include <jni.h>
#include <android/ndk-version.h>
#include <unistd.h>
#include <time.h>

#include <cstdint>
#include <exception>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr std::size_t kCheckedBytes = 1024U * 1024U;
static_assert(__cplusplus >= 202002L, "BenchBridge requires C++20");

std::string json_string(const std::string& text) {
    std::ostringstream out;
    out << '"';
    for (const unsigned char character : text) {
        switch (character) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (character < 0x20U) {
                    out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                        << static_cast<unsigned int>(character) << std::dec;
                } else {
                    out << character;
                }
        }
    }
    out << '"';
    return out.str();
}

constexpr const char* process_abi() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__x86_64__)
    return "x86_64";
#else
#error Unsupported BenchBridge ABI
#endif
}

constexpr std::uint64_t pattern(const std::size_t index) {
    return UINT64_C(0x9E3779B97F4A7C15) ^
        (static_cast<std::uint64_t>(index) * UINT64_C(0xBF58476D1CE4E5B9));
}

std::string inspect_environment() {
    const long page_size = sysconf(_SC_PAGESIZE);
    timespec before{};
    timespec after{};
    const bool first_clock_ok = clock_gettime(CLOCK_MONOTONIC, &before) == 0;

    // 小规模正确性检查，不用于计算带宽或延迟成绩。
    // A small correctness check, excluded from bandwidth and latency scores.
    std::vector<std::uint64_t> buffer(kCheckedBytes / sizeof(std::uint64_t));
    for (std::size_t index = 0; index < buffer.size(); ++index) {
        buffer[index] = pattern(index);
    }
    std::uint64_t checksum = 0;
    bool memory_ok = true;
    for (std::size_t index = 0; index < buffer.size(); ++index) {
        memory_ok = memory_ok && buffer[index] == pattern(index);
        checksum ^= buffer[index] + static_cast<std::uint64_t>(index);
    }

    const bool second_clock_ok = clock_gettime(CLOCK_MONOTONIC, &after) == 0;
    const bool clock_ok = first_clock_ok && second_clock_ok &&
        (after.tv_sec > before.tv_sec ||
         (after.tv_sec == before.tv_sec && after.tv_nsec >= before.tv_nsec));
    const bool passed = page_size > 0 && memory_ok && clock_ok;

    std::ostringstream checksum_text;
    checksum_text << std::hex << std::setw(16) << std::setfill('0') << checksum;
    std::ostringstream ndk_version;
    ndk_version << __NDK_MAJOR__ << '.' << __NDK_MINOR__ << '.' << __NDK_BUILD__;

    std::ostringstream out;
    out << std::boolalpha
        << "{\"schemaVersion\":1,\"ok\":" << passed
        << ",\"message\":\"Hello from C++\""
        << ",\"abi\":" << json_string(process_abi())
        << ",\"pageSizeBytes\":" << page_size
        << ",\"cppStandard\":" << __cplusplus
        << ",\"ndkVersion\":" << json_string(ndk_version.str())
        << ",\"cmakeVersion\":" << json_string(BENCHBRIDGE_CMAKE_VERSION)
        << ",\"compiler\":" << json_string(__clang_version__)
        << ",\"checkedBytes\":" << kCheckedBytes
        << ",\"memoryOk\":" << memory_ok
        << ",\"clockOk\":" << clock_ok
        << ",\"checksum\":" << json_string(checksum_text.str())
        << '}';
    return out.str();
}

}  // 匿名命名空间 / Anonymous namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_benchbridge_app_diagnostics_NativeProbe_nativeInspect(JNIEnv* env, jobject) {
    try {
        const std::string report = inspect_environment();
        return env->NewStringUTF(report.c_str());
    } catch (const std::exception& error) {
        const std::string report = "{\"schemaVersion\":1,\"ok\":false,\"error\":" +
            json_string(error.what()) + '}';
        return env->NewStringUTF(report.c_str());
    } catch (...) {
        return env->NewStringUTF(
            "{\"schemaVersion\":1,\"ok\":false,\"error\":\"Unknown native exception\"}");
    }
}
