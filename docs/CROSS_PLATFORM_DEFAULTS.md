# 跨平台默认参数 / Cross-platform defaults

0.12.0 起统一下列默认值；历史记录保持原参数与计分方式。MB/s、GB/s 为十进制，MiB、GiB 为二进制容量。

From 0.12.0, the defaults below are shared. Historical records retain their original parameters and aggregation. MB/s and GB/s are decimal; MiB and GiB are binary capacities.

| 项目 / Test | Android、iPhone、iPad 默认 / Shared default |
|---|---|
| RAM 读、写、拷贝 / Read, write, copy | 64 MiB 总工作集；拷贝为源与目标合计 / Total footprint, source plus destination for copy |
| RAM 延迟 / Latency | 64 MiB，单线程 / One worker |
| RAM 重复与计时 / Repetition and timing | 带宽 3 次、延迟 5 次，取中位数；预热 1 s、测量 3 s、间隔 2 s / 3 bandwidth and 5 latency rounds, median; 1 s warmup, 3 s measurement, 2 s interval |
| RAM 带宽线程 / Bandwidth threads | 自动校准最多 16 线程；校准不计分 / Calibrate up to 16 workers; calibration is not scored |
| 块大小—延迟 / Working-set latency | 4 KiB–64 MiB，单线程，每倍容量 8 个间隔，正反遍及有限补测 / One worker, 8 intervals per octave, forward/reverse and bounded rechecks |
| ROM 文件 / Storage file | 1 GiB，预先完整初始化一次 / Fully initialized once per run |
| ROM 四行 / Four storage rows | SEQ 1 MiB Q8T1、SEQ 1 MiB Q1T1、RND 4 KiB Q32T1、RND 4 KiB Q1T1 |
| ROM 重复与计时 / Repetition and timing | 读写各 3 次，取最佳完整轮次；预热、测量、间隔各 5 s / 3 rounds per direction, maximum valid complete round; 5 s each for warmup, measurement and interval |

RAM 总工作集不会按末级缓存自动扩大；多线程按 256 B 分配余数，64 MiB 保持为总量。64 MiB 不保证避开每台设备的全部系统缓存。旧安卓记录中 `expand_ram_working_set` 缺失表示旧扩大策略；新配置明确为 false。

RAM does not silently expand to exceed the last-level cache. Remainders are distributed in 256 B units so a 64 MiB request stays 64 MiB across threads. This does not guarantee bypassing every system cache. Missing `expand_ram_working_set` in old Android records retains the legacy expansion policy; new settings explicitly set it to false.

## 核心与曲线 / Cores and curves

安卓根据可用 CPU 的运行时频率域、容量、MIDR 分组，每组选择代表核心并绑定；不按 CPU 编号猜测性能高低。苹果读取 `hw.nperflevels`、`hw.perflevelN.*` 的核心数、L1D、L2 和每个 L2 的共享核数，成功读取才报告。

Android groups allowed CPUs using runtime frequency domains, capacities and MIDR, then pins a representative of each group, without assuming CPU order indicates performance. Apple queries `hw.nperflevels` and `hw.perflevelN.*` for core counts, L1D/L2 and cores per L2, reporting only successful reads.

苹果目录精确匹配 102 个标识、64 个型号，涵盖 iPhone XS–18 Pro 系列及 A/M 系 iPad。标识来源为 [DeviceKit](https://github.com/devicekit/DeviceKit/blob/master/Source/Device.swift.gyb)，每条记录链接 Apple 官方规格；参考核心数与运行时拓扑分开。M4/M5 iPad Pro 的 9/10 核配置不会合并成一个确定值；iPad A16 官方仅公布 5 核时，不猜分组。未知标识不会匹配近似型号，仍可执行测量。模拟器不套用模拟机型的芯片规格。

The Apple catalog exactly matches 102 identifiers across 64 models, including iPhone XS–18 Pro and A/M-series iPads. Identifiers come from [DeviceKit](https://github.com/devicekit/DeviceKit/blob/master/Source/Device.swift.gyb); each entry links to Apple specifications. Reference counts are separate from runtime topology. M4/M5 iPad Pro 9/10-core variants remain distinct possibilities; the iPad A16's undocumented split is not guessed. Unknown identifiers still support measurements without approximate matching. Simulators never inherit target-device chip specifications.

苹果默认分别请求高优先级和后台优先级，执行相同单线程扫描。**QoS 不是物理绑核，也无法证明两条曲线分别在 P/E 核执行。** 不使用私有绑核接口，不用资料库曲线代替测量。各点原始样本、单线程 CPU 时间、墙钟时间、行跨度和请求优先级随 JSON 导出。系统报告的缓存容量附近增加采样，不据规格填充延迟。

Apple requests high and background priority for the same single-worker sweep. **QoS is not physical affinity and does not establish P/E placement.** No private affinity API or catalog-generated curve substitutes for measurement. JSON retains raw samples, thread CPU/wall times, stride and requested QoS. Runtime cache capacities add sampling anchors, not synthetic latencies.

曲线每个方向至少 5 次有效样本，MAD/中位数 ≤6%，至少 80% 样本在 ±12% 内，两遍差异 ≤12%。不平滑数据、不跨验证缺口连线或推断；保留多个持续转换。不同操作系统、页大小、缓存行与调度机制仍会影响成绩。

Each direction requires at least 5 accepted samples, MAD/median ≤6%, at least 80% within ±12%, and directional agreement within 12%. Data is not smoothed and invalid gaps are not connected or inferred across. Multiple sustained transitions are retained. OS, page size, line size and scheduling still influence results.

## ROM 后端 / Storage backends

默认参数核对 [CrystalDiskMark 官方源码](https://github.com/hiyohiyo/CrystalDiskMark/blob/master/DiskMarkDlg.cpp)：默认计数索引 2 表示 3 次，文件索引 6 表示 1 GiB；[默认项目文档](https://crystalmark.info/en/software/crystaldiskmark/crystaldiskmark-main-menu/)。本应用是独立实现，不宣称与 PC DiskMark 分数等价。

Defaults are checked against [CrystalDiskMark's official source](https://github.com/hiyohiyo/CrystalDiskMark/blob/master/DiskMarkDlg.cpp): count index 2 means three runs and size index 6 means 1 GiB; see also the [default profile documentation](https://crystalmark.info/en/software/crystaldiskmark/crystaldiskmark-main-menu/). BenchBridge is an independent implementation, not a score-equivalent PC DiskMark port.

Android 使用 native AIO/O_DIRECT；Apple 使用 POSIX AIO/F_NOCACHE。Q1 使用同步定位 I/O；Q>1 每个工作线程维护独立异步请求，记录实际最大/平均在途深度。系统资源可能使实际深度低于请求值，详情和 JSON 保留差异。此值不是闪存控制器队列深度。T 表示提交工作线程，操作系统可用其内部线程执行 I/O。

Android uses native AIO/O_DIRECT; Apple uses POSIX AIO/F_NOCACHE. Q1 uses synchronous positioned I/O, while Q>1 maintains asynchronous requests per submitting worker and records actual maximum/mean outstanding depth. Resource limits may reduce achieved depth; details/JSON retain this difference. This is not controller queue depth. T counts submitting workers, not OS-internal I/O workers.

两端使用相同 64 MiB SplitMix64 数据池与种子；文件不是稀疏零文件。主计时从工作线程放行到最后一个请求完成，初始化、预热、校验和最终同步另计。取消/错误必须排空已提交请求后才能释放缓冲和删除本次文件。iOS 0.11.0 历史仍显示旧 Q1、中位数、写入包含同步的语义。

Both use the same 64 MiB SplitMix64 pattern pool/seed with fully initialized files. Throughput timing spans worker release through the last completion; initialization, warmup, validation and final sync are separate. Cancellation/errors drain submitted requests before freeing buffers or deleting the owned file. Historical iOS 0.11.0 records retain Q1, median scoring and sync-inclusive writes.

## 界面与验证 / UI and verification

两端保留 RAM／ROM／GPGPU／历史／设备入口；RAM 四项顺序为读取、写入、延迟、拷贝；ROM 为四行 × 读写两列。参数分测试类型，默认/快测仅重置当前类型。iOS 保留原生导航、表单、分享面板及 iPad 布局，安卓保留 Material 控件。

Both retain RAM/ROM/GPGPU/history/device navigation, the read/write/latency/copy RAM row and four storage rows with read/write columns. Family-specific presets/settings avoid changing unrelated workloads. iOS retains native navigation, forms, sharing and iPad layout; Android retains Material controls.

iOS 新报告使用 schema 2：`config.ram` 为 RAM 摘要参数，`config.storage` 为 ROM 参数，`config.cache_max_mi_b` 等为曲线设置；原顶层内存/时长/次数字段保留给 GPGPU 与旧记录兼容。`measurements.working_set_bytes`、`threads` 和 `storage_samples` 记录实际执行值。文件大小不等于累计读写量，拷贝成绩中的字节数按读写合计。旧 schema 1 报告仍按原字段与中位数显示。

New iOS reports use schema 2: `config.ram` holds RAM score parameters, `config.storage` storage parameters, and fields such as `config.cache_max_mi_b` the curve settings. Original top-level memory/duration/repeat fields remain for GPGPU and legacy decoding. Measurement `working_set_bytes`, `threads` and `storage_samples` retain actual execution values. File size is distinct from cumulative I/O; copy counts reads plus writes. Schema 1 reports retain their original fields and median aggregation.

`storage_samples` 同时记录实际在途深度、`start_delay_ns`（唤醒后开始执行前的等待）及 `minimum_worker_operations`（各工作线程完成数的最小值）。极短测试仍提交首批请求，超出目标窗口的等待按真实耗时计入，不能用目标时长冒充实测时间。`error_phase` 为 1 准备、2 预热、3 测量、4 同步、5 校验，成功为 0；配合系统错误码排查，不把零次操作误报为磁盘故障。

`storage_samples` also retains actual outstanding depth, `start_delay_ns` (worker wakeup delay) and `minimum_worker_operations` (the minimum completed count across workers). Very short tests submit a first batch while retaining actual elapsed time, including deadline overshoot; the requested duration never substitutes for measured time. `error_phase` uses 1 preparation, 2 warmup, 3 measurement, 4 sync, 5 validation, and 0 success. Together with the OS error code, this distinguishes missing work from a file failure.

新版本必须重新运行本地原生测试、安卓构建/Lint/设备用例和苹果两套工具链的 iPhone/iPad 模拟器用例；最终结果见对应 GitHub Release。已有 0.11.0 真机安装运行反馈不代替 0.12.0 新测量内核的真机验收。

The release requires fresh native, Android build/Lint/device and both Apple toolchain iPhone/iPad simulator checks; final evidence belongs to the matching GitHub Release. Reported successful physical installation of 0.11.0 does not validate the new 0.12.0 measurement kernels on-device.

Apple 核心信息接口依据：[官方调度技术讲解](https://developer.apple.com/videos/play/tech-talks/110147/)。
Apple topology API reference: [official scheduling technical talk](https://developer.apple.com/videos/play/tech-talks/110147/).
