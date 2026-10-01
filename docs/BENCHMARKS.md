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

默认表格测量 L1 数据缓存、L2、L3 与 RAM。CPU 及缓存共享域优先读取 `/sys/devices/system/cpu`，缺失规格仅在 SoC 身份、核心数及核心组得到核对后从内部资料库补充。无法可靠规划的项目不分配假定容量，不生成成绩；原因保存在对应单元格的 `reason` 字段。

The default matrix measures L1 data cache, L2, L3 and RAM. CPU topology and cache sharing domains come first from `/sys/devices/system/cpu`. The internal catalog fills gaps only after checking SoC identity, core count and core groups. A cell without a reliable plan receives no assumed cache capacity or score; its `reason` field records the limitation.

每个 L1 共享域使用其容量的 50%，L2 / L3 使用 75%，再分配给参与该域的线程。L2 / L3 的每线程工作集须至少为已确认下一级容量的两倍。拷贝工作集包括源和目标。RAM 默认申请带宽 512 MiB、延迟 256 MiB；规划时至少覆盖所选末级缓存域的两倍容量，可能扩大工作集。所有候选计划独立检查内存预算，界面与 JSON 显示实际分配值。

Each L1 sharing domain uses 50% of its capacity; L2 and L3 use 75%, divided among participating workers. Each L2/L3 worker must cover at least twice the confirmed capacity of the preceding level. Copy footprints include both source and destination. RAM requests 512 MiB for bandwidth and 256 MiB for latency by default, then expands if needed to cover twice each selected last-level domain. Every candidate receives a separate memory-budget check. The interface and JSON record the actual allocation.

带宽候选包括 1、2、4、8、16 线程、全部可用核心、物理核心代表及同类核心组；只比较有效组合，最多 12 组。固定线程模式保持指定线程数，只校准核心组合。延迟候选始终为单核单线程。每个组合短测 2 次，差异超过 15% 时补第 3 次；仍波动超过 30% 的组合排除。按中位数选择带宽最高或延迟最低者，3% 内近似相同时优先较少线程。每个表格单元格独立校准，完整过程保存在 `cells[].calibration`。

Bandwidth candidates include 1, 2, 4, 8 and 16 threads, all available cores, physical-core representatives and homogeneous groups. Up to twelve valid combinations are compared. Fixed-thread mode preserves the requested count and calibrates only core selection. Latency always uses one thread on one core. Each candidate has two short trials, with a third when the first two differ by over 15%. Candidates still varying by over 30% are excluded. Selection uses median bandwidth or latency; ties within 3% favor fewer threads. Each cell calibrates independently, with full records in `cells[].calibration`.

工作线程在首次触页前绑定到单个 CPU，并核对亲和掩码及正式测量前后的实际 CPU。小工作集在内核内重复遍历，编译器屏障保留每一遍访存。表格延迟以已确认的缓存行大小建立随机闭环；RAM 无缓存行资料时使用 64 B 并记录实际间隔。原有六项 RAM 快测继续使用系统调度和 128 B 延迟节点，旧报告按原口径读取。

Workers bind to one CPU before first touch. Affinity masks and observed CPUs before and after measurement are checked. Small working sets repeat inside the kernel, with compiler barriers preserving accesses on every pass. Matrix latency uses a randomized cycle at the confirmed cache-line spacing; RAM uses a recorded 64-byte spacing if line metadata is unavailable. The legacy six-operation RAM quick profile retains OS scheduling and 128-byte latency nodes. Old reports retain their original interpretation.

默认预热 1 秒、正式测量 3 秒；带宽每项重复 3 次，延迟重复 5 次，轮间隔 2 秒。主值为有效轮次的中位数，另保留各轮、最小值、最大值与变异系数。

Defaults are a one-second warmup, three seconds of measurement, three repetitions per bandwidth operation, five latency repetitions, and two seconds between rounds. The displayed score is the median of valid rounds; individual rounds, extrema and the coefficient of variation are retained.

上述时长用于 RAM；缓存每轮预热最多 250 ms、正式测量最多 1 秒、间隔最多 200 ms，重复次数相同。默认校准每次 150 ms，预热最多 80 ms。正式轮次独立于校准，完整表格共 56 轮。表格快测缩短时长与次数，主要用于检查流程。

Those timings apply to RAM. Cache rounds cap warmup at 250 ms, measurement at one second and rest at 200 ms, retaining the same repetition counts. Default calibration trials last 150 ms with up to 80 ms warmup. Scored rounds are independent of calibration; a complete default matrix has 56 rounds. The matrix quick profile shortens timings and counts for functional checks.

分配、触页、建链、线程创建和校验位于正式计时之外。工作线程使用共同起点，计时截至最后一个线程完成当前批次。写入屏障确保缓存写入顺序，不等同于将数据全部刷新到 DRAM。

Allocation, page touching, chain construction, thread creation and validation are outside the measured interval. Workers share a start time, and timing ends when the last worker finishes its current batch. Store barriers order cached writes; they do not flush all data to DRAM.

## 存储 / Storage

默认配置为 1 GiB 文件、每项每方向 3 轮、每轮 5 秒；每个方向和项目首轮预热 5 秒，项目之间间隔 5 秒。次数对齐 CrystalDiskMark 9.x，四项双方向共 24 轮。

The default plan uses a 1 GiB file, three rounds per case and direction, and five seconds per round. Each case and direction has a five-second initial warmup, with five seconds between cases. The count follows CrystalDiskMark 9.x, for 24 rounds across four cases and two directions.

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
# 缓存分块扫描（0.8.0） / Cache working-set sweep (0.8.0)

缓存拓扑有缺失时，先按运行时核心类型、容量、频率与频率域选择代表核心；分组信息全未知时逐核测试。扫描 4 KiB 至预算允许的最大 64 MiB，以 1、1.5 倍的几何序列增长，并在已知缓存容量周围增加采样。每点分别执行两次 20 ms 预热 + 50 ms 测量的绑核随机指针追逐及连续读取，两次使用不同随机种子。

When topology is incomplete, select representative cores from runtime type/capacity/frequency domains, testing each core when grouping evidence is entirely absent. Sweep from 4 KiB to a budget-limited 64 MiB using geometric 1/1.5 steps, adding samples around known cache capacities. Each point has two independently seeded pinned pointer-chase and sequential-read trials, each with 20 ms warmup and 50 ms measurement.

两次相对波动超过 15% 的点不用于判定。候选拐点要求前两点延迟平台稳定、后点延迟至少上升 25%，且下一点持续至少 20% 上升；带宽下降超过 8% 作为额外证据。初次区间内再插入三个点缩小区间。导出保留原始样本、粗区间、细化区间、绑核与步长来源，扫描不计入正式成绩。失败、取消和预算不足分别报告。

Points with over 15% repeat variation are excluded from detection. A candidate requires a stable preceding two-point latency plateau, a rise of at least 25%, and a following point at least 20% above baseline; a bandwidth drop over 8% corroborates it. Three additional interior points refine each initial interval. Exports retain raw trials, coarse/refined ranges, affinity and stride provenance. Probe work is unscored, and failures, cancellation and insufficient budget are reported separately.

这些是访问路径的有效拐点，不是缓存规格读取：TLB、预取器、调频、系统缓存都会影响曲线。未确认的层级不自动命名为 L1/L2/L3，不把系统缓存或共享 L2 写成 L3。ARM64 的 CTR_EL0 DminLine 是系统可用的最小数据缓存行粒度；用于指针步长并单独标注来源，不宣称它证明所有层级的精确物理行大小。

These are effective access-path transitions, not direct cache specifications: TLBs, prefetching, frequency changes and system caches can affect curves. Unknown levels are not automatically labeled L1/L2/L3; system cache and shared L2 are not renamed L3. ARM64 CTR_EL0 DminLine supplies a system-safe minimum data-cache granule for pointer strides with separate provenance, not proof of each level's exact physical line size.
