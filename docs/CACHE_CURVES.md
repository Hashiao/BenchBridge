# 缓存延迟曲线 / Cache latency curves

0.10.0 使用 `dense-index-curve-v3`。目标是比较不同核心组的完整访问曲线，而不是从少数采样点猜测三个缓存容量。旧版 JSON 和历史曲线保留原协议，不重新计算成新版成绩。

Version 0.10.0 uses `dense-index-curve-v3` to compare full latency curves across core groups. It does not guess three cache capacities from a few points. Historical JSON and curves retain their original protocol and results.

## 扫描与计时 / Sweep and timing

- 每个可辨认的频率 / 容量 / 核心类型组固定一个代表核心。识别不充分时逐核心测试，不凭 CPU 编号猜类型。标准范围 4 KiB–128 MiB，每倍容量 8 个间隔（基础网格 121 点）；快测到 64 MiB、每倍 4 个间隔。全部核心组使用同一基础网格；参考容量附近和实测转换附近另加采样点。
  Pin a representative of each identifiable frequency/capacity/core-type group. Test cores separately when grouping information is insufficient. The standard grid covers 4 KiB–128 MiB with eight intervals per octave (121 base points); quick mode uses four intervals per octave through 64 MiB. Groups share the base grid, with extra points near reference capacities and measured transitions.
- 每个点分配一个高熵缓冲区，每个节点存下一节点的 32 位索引。链覆盖整个工作集，每次读取依赖前次结果。计时包含索引地址计算；分配、填充、洗牌、链校验和频率文件读取不计入访问时间。节点间隔来自可用的运行时行粒度，否则保留 64 B 默认值；不声称它证明每级缓存的物理行宽。
  Allocate one high-entropy buffer per point. Each node holds the next node's 32-bit index, forming a full dependent cycle. Timing includes address generation but excludes allocation, initialization, shuffling, cycle validation and frequency-file reads. Use available line-granule information, otherwise a recorded 64 B default; this is not proof of every cache level's physical line size.
- 先预热至少两遍完整链，再在同一分配和同一绑核线程上做 5–9 个 30 ms 计时块。预热观察最近三块的收敛情况，常规上限 500 ms，未走完两圈时最多 2.5 秒。操作数是真实完成的访问数，末批取整导致的超时保存在原始数据中。
  Warm at least two complete traversals, then measure 5–9 blocks of 30 ms on the same allocation and pinned thread. Warmup tracks convergence over three blocks, normally up to 500 ms and up to 2.5 seconds when two traversals are incomplete. Count actual accesses and retain final-block overshoot in raw timing.
- 排除线程 CPU 时间不足墙钟时间 90%、计时超限或前后可读频率变化超过 10% 的计时块。每遍至少五个合格块，MAD 不超过中位数 6%，至少 80% 的块在中位数 ±12% 内；正反两遍中位数须相差不超过合并中位数 12%。不选最快值，所有原始块包括未通过的块均保留。
  Reject blocks with CPU time below 90% of wall time, excessive timing overrun, or a readable endpoint frequency change above 10%. Each pass needs five accepted blocks, MAD at most 6% of median, and at least 80% within ±12% of median. Forward/reverse medians must agree within 12% of their combined median. Never select the fastest value; retain rejected blocks too.
- 正向扫描后反转核心组顺序和工作集顺序，用独立排列复测。不合格点自动复核，每点每遍最多三批；复核采用最新批次而非最快批次。转换区间额外补三个点并进行同样验证。时间或内存不足时明确保留不完整状态，不把缺口插值成实测点。
  Reverse both group and working-set order for the second pass and use independent permutations. Automatically recheck inconsistent points, at most three batches per point per pass, keeping the latest batch rather than the fastest. Add three interior points near detected transitions and apply the same verification. Preserve incomplete status under resource limits; do not interpolate missing measurements.

## 分析与显示 / Analysis and display

对连续通过双向验证的采样片段，在对数工作集 / 对数延迟上进行带惩罚项的分段线性拟合，每段至少四点、每个可分析片段至少八点。波动或缺测处切断分析，绝不跨缺口推断阶跃；其他连续片段仍给出结论，整体保留部分验证状态。拟合仅用来解释区间；图上始终显示实测中位数，不用拟合值替换。连续增长可以形成趋势段，不强制拆成 L1/L2/L3；相邻区间差异达到 25% 才列出转换。结论给出多个范围、区间中位数和转换前后延迟；小幅变化或样本不足时不能认定缓存容量。

Apply penalized piecewise linear segmentation to contiguous bidirectionally validated spans in log-size/log-latency space, with at least four points per segment and eight points per analyzable span. Break analysis at inconsistent or missing points; never infer a transition across a gap. Other contiguous spans still receive conclusions while the overall result retains partial validation status. Fits explain regions only; plotted values remain measured medians. Continuous growth may form a trend region, without forcing L1/L2/L3 labels. List transitions only when neighboring region medians differ by at least 25%. Conclusions include multiple ranges, medians and before/after latency, without assigning physical capacities from insufficient evidence.

默认叠加显示全部核心组，共用坐标；可切换单核心组、线性 / 对数 ns。单组误差线是合格计时块的 10–90% 分位范围，不是统计置信区间。空心点表示未通过验证。0.10.1 恢复默认先测 RAM 四项再扫描曲线，首页和详情保留读取 / 写入 / 延迟 / 拷贝摘要。设置中仍可关闭 RAM，0.10.0 纯曲线历史保留未测状态，不从曲线推算 RAM 成绩。执行顺序保存在 `stage_order` 中。

The default overlay compares all groups on shared axes, with per-group selection and linear/logarithmic ns. Error bars for a selected group show the 10th–90th percentiles of accepted blocks, not confidence intervals. Hollow points failed validation. Version 0.10.1 restores four RAM tests before the curve sweep and a read/write/latency/copy summary on both result views. RAM can still be disabled; 0.10.0 curve-only records remain unmeasured without deriving RAM scores from curves. Record execution order in `stage_order`.

每完成一个点次就原子保存全量检查点。Binder 只传界面摘要，导出使用磁盘全量记录。取消或进程中断后可以显式继续当前协议的纯曲线测试，保留旧记录并创建关联的新记录；验证拓扑、参数和内存预算，复核次数不会因续测无限重置。Android 11+ 尽可能保存系统进程退出原因；查不到时只报告未知中断，不推断为 OOM。

Atomically save a full checkpoint after each point/pass. Binder carries only UI summaries; exports retain full disk records. Explicit resume of a cancelled/interrupted current-protocol curve-only run creates a linked new report, preserving the old one. Validate topology, parameters and budget without resetting retry limits. On Android 11+, record system exit information when available; an unknown interruption is not attributed to OOM.

## 可比性与限制 / Comparability and limitations

使用普通系统页，完整工作集内随机访问，包含 TLB、预取、地址计算与实际频率的影响，不能直接与大页、禁预取、纯地址指针链或锁频环境的数字等同。读不到频率时保留 0（未知）；前后读数也不能证明块内没有频率变化。曲线末端达到 128 MiB 不保证已覆盖所有系统级缓存。

Ordinary system pages and full-working-set randomness include TLB, prefetch, address-generation and actual-frequency effects. Results are not numerically interchangeable with huge-page, disabled-prefetch, pure-pointer or frequency-locked tests. Unreadable frequencies remain zero/unknown; endpoint readings cannot prove constant frequency throughout a block. Reaching 128 MiB does not prove that all system-level caches were exceeded.

方法参考：[Chips and Cheese MemoryLatency](https://github.com/ChipsandCheese/Microbenchmarks/tree/master/MemoryLatency) 对索引链、直接指针链和 TLB 模式的区分；实现为本项目原创。退出诊断使用 [Android ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)。

Method reference: [Chips and Cheese MemoryLatency](https://github.com/ChipsandCheese/Microbenchmarks/tree/master/MemoryLatency) distinguishes indexed, direct-pointer and TLB modes; this implementation is original. Exit diagnostics use [Android ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo).
