# 测量说明 / Measurement notes

本文描述当前实现中的计数、计时与结果解释。所有数值来自实际测量；未完成或校验失败的轮次不作为有效成绩。

This document describes the current counting rules, timing boundaries and interpretation of results. Scores come from measured work; incomplete or unverified rounds are excluded.

## RAM

| 项目 / Operation | 测量方式 / Method | 主值 / Score |
|---|---|---|
| 顺序读 / Sequential read | NEON 或 SSE2，多累加器 / NEON or SSE2 with independent accumulators | 有效读取字节 ÷ 时间 / Useful bytes read divided by time |
| 顺序写 / Sequential write | 普通缓存 SIMD 写入 / Cached SIMD stores | 有效写入字节 ÷ 时间 / Useful bytes written divided by time |
| 复制 / Copy | 不重叠源与目标 / Non-overlapping source and destination | 2 × 复制字节 ÷ 时间 / Twice the copied bytes divided by time |
| 随机读写 / Random read and write | 预生成索引，8 B 访问 / Precomputed indices and 8-byte accesses | 有效访问字节 ÷ 时间 / Useful bytes accessed divided by time |
| 访问延迟 / Access latency | 单线程随机依赖指针链 / A single-threaded dependent random pointer chain | 总纳秒 ÷ 访问次数 / Total nanoseconds divided by dependent loads |

复制同时统计读取和写入，JSON 中 `payload_bytes` 为复制量，`logical_bytes` 为其两倍。MB/s 与 GB/s 使用十进制单位；工作集容量使用 MiB。

Copy counts both the read and write sides. In JSON, `payload_bytes` is the copied amount and `logical_bytes` is twice that amount. MB/s and GB/s are decimal units; working-set sizes use MiB.

总工作集分成不重叠的线程区域；增加线程不会增加总工作集。默认带宽工作集为 512 MiB，延迟工作集为 256 MiB。带宽线程按进程可用 CPU 数选择，最多 16 个；延迟始终使用一个线程。当前使用系统默认调度，没有显式核心绑定或线程数性能校准。

The total working set is divided into disjoint thread regions; adding threads does not increase the total allocation. Default working sets are 512 MiB for bandwidth and 256 MiB for latency. Bandwidth uses up to 16 CPUs available to the process; latency uses one thread. Scheduling remains OS-managed, without explicit core affinity or performance-based thread calibration.

延迟指针链的节点间隔为 128 B，每个节点属于同一个随机闭环。该间隔是算法参数，不代表被测 CPU 的缓存行大小。当前实现不划分 L1、L2、L3 成绩。

Latency nodes are spaced 128 bytes apart and form one randomized cycle. This spacing is an algorithm parameter, not a claim about the CPU's cache-line size. The current implementation does not produce separate L1, L2 or L3 scores.

默认预热 1 秒、正式测量 3 秒；带宽每项重复 3 次，延迟重复 5 次，轮间隔 2 秒。主值为有效轮次的中位数，另保留各轮、最小值、最大值与变异系数。

Defaults are a one-second warmup, three seconds of measurement, three repetitions per bandwidth operation, five latency repetitions, and two seconds between rounds. The displayed score is the median of valid rounds; individual rounds, extrema and the coefficient of variation are retained.

分配、触页、建链、线程创建和校验位于正式计时之外。工作线程使用共同起点，计时截至最后一个线程完成当前批次。写入屏障确保缓存写入顺序，不等同于将数据全部刷新到 DRAM。

Allocation, page touching, chain construction, thread creation and validation are outside the measured interval. Workers share a start time, and timing ends when the last worker finishes its current batch. Store barriers order cached writes; they do not flush all data to DRAM.

## 存储 / Storage

默认配置为 1 GiB 文件、每项每方向 5 轮、每轮 5 秒；每个方向和项目首轮预热 5 秒，项目之间间隔 5 秒。该组合保留了 CrystalDiskMark 8.x 的常用设置；9.x 的默认次数变化尚未应用到本版本。

The default plan uses a 1 GiB file, five rounds per case and direction, and five seconds per round. Each case and direction has a five-second initial warmup, with five seconds between cases. This profile retains the common CrystalDiskMark 8.x settings; the 9.x default-count change has not been applied in this version.

测试始终复用同一个完整初始化的文件。初始化、预热和正式写入均计入累计写入量，但不增加文件长度。数据源为按文件偏移重复的 64 MiB SplitMix64 数据池。

Each run reuses one fully initialized file. Initialization, warmup and measured writes all count toward cumulative writes without increasing file length. The data pattern is a 64 MiB SplitMix64 pool repeated by file offset.

Q1 使用 `pread` / `pwrite`，Q>1 使用 Linux 原生 AIO。每个线程拥有自己的 AIO 上下文和不重叠文件区域，每个未完成请求拥有独立缓冲区。能力探测在独立进程运行，避免系统拒绝相关调用时终止界面。

Q1 uses `pread` / `pwrite`; Q>1 uses Linux native AIO. Each thread has its own AIO context and disjoint file region, and every outstanding request has a separate buffer. Capability probes run in a separate process so a rejected system call does not terminate the interface.

Direct 使用 `O_DIRECT`；Buffered 使用正常缓存 I/O。探测或执行失败时不会静默替换缓存模式或降低 Q / T。

Direct uses `O_DIRECT`; Buffered uses normal cached I/O. Probe or execution failures do not silently change the cache mode or reduce Q / T.

停止或到达截止时间后，不再提交新请求，先排空已有请求，再回收缓冲区和文件。每个写入轮次后执行 `fdatasync`，其耗时单独记录，不计入主吞吐分母。

On cancellation or deadline, submission stops and outstanding requests are drained before buffers and files are released. Each write round ends with `fdatasync`; its duration is recorded separately from the main throughput interval.

MB/s = 完成字节 × 1000 ÷ 纳秒；IOPS = 完成请求数 × 10⁹ ÷ 纳秒。主值选择吞吐最高的完整轮次；切换 IOPS 或平均延迟时仍使用同一轮次。单请求延迟从提交前到应用观察到完成，包含系统调用、调度和完成回收开销。

MB/s equals completed bytes multiplied by 1000 and divided by nanoseconds. IOPS equals completed requests multiplied by 10⁹ and divided by nanoseconds. The displayed score uses the complete round with the highest throughput; IOPS and mean latency use that same round. Per-request latency spans submission through application-observed completion, including system-call, scheduling and completion-handling overhead.

延迟分位数由对数直方图估算。每轮结束后抽样检查文件 16 个位置，每个位置读取 4 KiB；该检查不替代完整介质校验。

Latency percentiles are estimated from logarithmic histograms. After each round, sixteen file locations are checked using 4 KiB reads; this sampling is not a full-media verification.

## 预算与文件生命周期 / Budgets and file lifecycle

存储空间只按一个测试文件检查，另保留可用空间的 5%，下限 256 MiB、上限 2 GiB。I/O 缓冲区最多 256 MiB，Q × T 最多 512；所需内存还包含数据池和线程开销，准入预算不超过可用内存的三分之一，并保留系统低内存阈值或 256 MiB。

The space check accounts for one test file and reserves 5% of available space, bounded between 256 MiB and 2 GiB. I/O buffers are limited to 256 MiB and Q × T to 512. Admission also includes the pattern pool and thread overhead, uses at most one third of available memory, and retains the system low-memory threshold or 256 MiB.

默认累计写入不限，测试由所选次数和时长控制。设置上限后，初始化、预热和正式写入都在提交前预留预算。达到上限时，不完整轮次不出分，之前完成的轮次保留。

Cumulative writes are unlimited by default, with run length controlled by the selected count and duration. When a limit is set, initialization, warmup and measured writes reserve budget before submission. An incomplete round produces no score when the limit is reached; earlier complete rounds remain available.

测试文件位于应用内部的专属目录。控制记录、文件锁及路径检查防止清理活动任务或无关文件。结果使用原子文件单独保存，工作进程重启后恢复已提交的轮次并尝试回收遗留测试文件。

Test files live in dedicated app-internal directories. Control records, file locks and path checks protect active runs and unrelated files. Results are committed separately through atomic files. After a worker restart, committed rounds are recovered and orphaned test files are considered for cleanup.

## 解释结果 / Interpreting results

这些数值描述指定工作集、线程、缓存策略和访问模式下的 CPU 或存储访问性能。RAM 指针链包含地址转换等开销；Direct I/O 也不排除设备内部缓存。跨软件、系统或设备比较时需核对测量口径和实际配置。

Scores describe CPU or storage access under the selected working set, thread count, cache policy and access pattern. RAM pointer chasing includes address-translation overhead, and Direct I/O does not exclude device-internal caching. Compare counting rules and effective configurations when comparing tools, operating systems or devices.

参考 / References: [AIDA64 benchmarks](https://www.aida64.com/user-manual/benchmarks), [AIDA64 thread calibration](https://forums.aida64.com/topic/3930-laughing-at-reviewer-ignorance/), [CrystalDiskMark settings](https://crystalmark.info/en/software/crystaldiskmark/crystaldiskmark-main-menu/), [CrystalDiskMark history](https://crystalmark.info/en/software/crystaldiskmark/crystaldiskmark-history/).
