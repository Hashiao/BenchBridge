# 绑定受限时继续测试 / Continue testing under affinity restrictions

0.12.4 按实际绑定结果兼容系统限制，不按 HyperOS 版本号切换。可正常绑定的设备保留原候选核心顺序、校准次数、原生内核、工作集、预热、计时、校验和统计方式。没有为了兼容受限设备而放宽固定核心的验证。

0.12.4 reacts to actual affinity failures rather than firmware version strings. Healthy devices retain candidate order, calibration counts, native kernels, working sets, warmup, timing, verification and aggregation. Fixed-core validation is not relaxed for compatibility.

## 恢复顺序 / Recovery order

1. 每次仍请求单核心或计划中的逐线程绑定，并严格回读验证。设置接口返回成功不代表请求已准确落实；例如请求 CPU 6，回读 3–6，仍判定为绑定失败。
   Continue requesting and verifying exact per-worker affinity. A successful setter does not prove the requested mask was honored: requesting CPU 6 but reading back 3–6 remains a failure.
2. 仅在绑定失败后，本次运行暂时排除失败核心，优先尝试同一频率/容量组的其他核心；例如 CPU 6 → CPU 7。组别不明确时不凭编号猜测同组关系。其他原候选组仍照常参与校准。
   After an affinity failure only, exclude the failed CPU for this run and try another in its frequency/capacity group, e.g. CPU 6 → CPU 7. Do not guess groups from CPU numbers when topology is unknown. Other original candidate groups still participate in calibration.
3. 只有所有可用绑定计划均被排除，RAM 项目才使用已有的系统调度 T1 内核。若仅是采样抖动、数据校验失败或内存不足，不触发此替代路径。缓存容量表中的 L1/L2/L3 不使用系统调度来冒充固定核心成绩。
   Only when every eligible affinity plan is excluded may RAM use the existing system-scheduled T1 kernel. Noise, data verification failures and insufficient memory do not trigger this fallback. L1/L2/L3 capacity-table scores never substitute unpinned measurements for fixed-core results.
4. 正式轮次中途丢失绑定时，该项已有轮次移入 `discarded_rounds`，标记 `scored=false`，重新选计划并从第 1 轮开始。不同核心或不同调度方式不混算平均值。
   If affinity fails during scored rounds, move that score's previous rounds to `discarded_rounds` with `scored=false`, select a new plan and restart at round one. Do not average different CPUs or scheduling modes together.

新路径保留原配置的 RAM 工作集、预热、正式时长、重复次数和统计方式；系统调度明确为 T1，即使原配置选择了多线程。首页、详情、历史与文字分享都显示“系统调度”。JSON 保留请求配置与实际计划，`binding_mode=system_scheduled`、空 `cpu_ids`、`core_binding=false` 和实际观测的起止核心。

The fallback keeps the configured RAM footprint, warmup, duration, repeats and aggregation, but explicitly uses T1 even if multiple threads were requested. Dashboard, details, history and text sharing identify system scheduling. JSON retains requested settings and actual plans, including `binding_mode=system_scheduled`, empty `cpu_ids`, `core_binding=false` and observed endpoint CPUs.

系统调度复用既有非绑核实现，延迟节点间隔为 **128 B**，实际值记录在计划和每轮结果中；正常绑核路径继续使用原计划的缓存行间隔。不同内核、节点间隔和调度方式的成绩应按各自记录比较，不能把替代成绩当作指定核心的结果。

The existing unpinned fallback uses a **128 B** latency-node stride, recorded in plans and samples. Healthy pinned runs retain their planned cache-line stride. Compare results with their kernel, stride and scheduling metadata; fallback scores do not represent a specified core.

## 曲线 / Curves

曲线首个有效点之前绑定失败，可以改用同组替代核心，曲线名称与 JSON 会显示实际核心。已有有效点后再失去绑定，则保留此前验证通过的点，停止该组，继续其他组。不会把 CPU 6 和 CPU 7 的点拼成一条曲线。

Before a curve has any valid point, affinity failure may select a same-group replacement, reflected in the curve label and JSON. After valid points exist, affinity loss stops that group while preserving its verified points and continuing other groups. CPU 6 and CPU 7 samples are never joined into one curve.

无法固定绑定的组显示“受限”，不生成完整曲线结论；RAM 四项可以先完成并保留。原有数据校验失败仍保持失败，原始失败现场不删除，详情 JSON 中可检查 `affinity_recovery`、校准记录、`affinity_failures` 和 `discarded_rounds`。这些大字段保存在完整导出中，界面跨进程仅传摘要。

Restricted groups are labeled explicitly and produce no complete-curve conclusion. RAM scores can finish independently. Data errors remain failures and all original evidence is retained in `affinity_recovery`, calibration, `affinity_failures` and `discarded_rounds`. Full exports keep these records while cross-process UI messages use summaries.

## 复测 / Retesting

请正常的 4.0.6 设备与此前失败的设备分别覆盖安装新版，使用相同参数重跑并导出新 JSON。正常机应仍显示原固定核心；受限机可能使用同组替代核心，或显示系统调度 T1。模拟器回归可以验证恢复逻辑和正常路径不变，但不代表已经在这两台真机上验收。

Upgrade both the working 4.0.6 phone and the affected phone, rerun with matching settings and export new JSON. The working phone should retain verified fixed-core operation; the affected one may use a same-group replacement or explicitly show system-scheduled T1. Simulator regressions verify recovery and healthy-path behavior, not acceptance on these two physical phones.

iOS 0.12.4 为同步发版，保留现有系统 QoS 调度和测量行为；最低系统仍为 iOS/iPadOS 16.0。

iOS 0.12.4 is a synchronized release retaining existing system QoS scheduling and measurement behavior, with iOS/iPadOS 16.0 as the minimum.
