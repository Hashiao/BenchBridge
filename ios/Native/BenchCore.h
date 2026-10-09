#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

// 独立会话支持协作取消，销毁前必须等待测量返回。 / Sessions support cooperative cancellation; join measurements before destruction.
typedef struct BBSession BBSession;
typedef struct {
    uint64_t elapsed_ns, cpu_ns, operations, logical_bytes;
    int32_t accepted;
} BBTrial;
typedef struct {
    // 0 成功，1 取消，2 参数无效，3 分配失败，4 校验失败，5 I/O 失败，6 未实现，7 没有测量操作。
    // 0 completed, 1 cancelled, 2 invalid, 3 allocation, 4 verification, 5 I/O, 6 unavailable, 7 no measured operations.
    int32_t status, kind, trial_count, verified, threads, qos, no_cache, node_stride_bytes;
    uint64_t working_set_bytes, warmup_operations, wall_ns, checksum;
    BBTrial trials[9];
} BBResult;

BBSession* bb_session_create(void);
void bb_session_cancel(BBSession* session);
int32_t bb_session_cancelled(BBSession* session);
void bb_session_destroy(BBSession* session);
BBResult bb_memory(BBSession* session, int32_t kind, uint64_t bytes, int32_t threads,
                   int32_t warmup_ms, int32_t duration_ms, int32_t qos, uint64_t seed);
BBResult bb_cache_point(BBSession* session, uint64_t bytes, int32_t stride,
                        int32_t qos, uint64_t seed);
BBResult bb_cache_point_once(BBSession* session, uint64_t bytes, int32_t stride,
                             int32_t qos, uint64_t seed);
BBResult bb_storage(BBSession* session, const char* path, int32_t write_test, int32_t random_access,
                    uint64_t file_bytes, int32_t block_bytes, int32_t duration_ms);
typedef struct BBStorage BBStorage;
typedef struct {
    BBResult measurement;
    int32_t queue_depth, block_bytes, random_access, max_outstanding, error_number, resource_limited, error_phase;
    double mean_outstanding;
    uint64_t flush_ns, prepare_bytes, written_bytes_total, start_delay_ns, minimum_worker_operations;
    int32_t preparation_no_cache, buffer_alignment_bytes;
    uint64_t submitted_operations, completed_bytes, completion_wall_ns;
} BBStorageResult;
// 文件仅初始化一次；销毁前必须等 run 返回、排空所有 I/O。 / Prepare once; drain all I/O before returning or destroying.
BBStorage* bb_storage_create(BBSession* session, const char* path, uint64_t file_bytes, int32_t no_cache, BBStorageResult* result);
BBStorageResult bb_storage_run(BBStorage* storage, int32_t write_test, int32_t random_access, int32_t block_bytes,
                               int32_t queue_depth, int32_t threads, int32_t warmup_ms, int32_t duration_ms);
void bb_storage_destroy(BBStorage* storage);
BBResult bb_cpu_compute(BBSession* session, int32_t kind, int32_t duration_ms);
int32_t bb_compute_reference(int32_t kind, uint32_t item, uint32_t iterations, uint32_t* words);
int32_t bb_check_compute(int32_t kind, uint32_t item, uint32_t iterations, const uint32_t* words);
uint32_t bb_fractal_reference(int32_t kind, uint32_t pixel, uint32_t width, uint32_t height);
int32_t bb_validate_chain(uint64_t bytes, int32_t stride, uint64_t seed);
const char* bb_core_protocol(void);
uint64_t bb_available_memory(void);
#ifdef __cplusplus
}
#endif
