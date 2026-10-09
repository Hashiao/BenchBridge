# RAM 绑核诊断 / RAM affinity diagnostics

0.12.4 保留下述诊断，并新增绑定受限时的恢复行为，见 [恢复与真机复测](AFFINITY_RECOVERY.md)。

0.12.4 retains these diagnostics and adds recovery under affinity restrictions; see [recovery and device retesting](AFFINITY_RECOVERY.md).

0.12.3 修复绑核失败只保存错误名、丢失原生现场的问题。此次不宣称已绕过某个系统版本的调度限制；仍需受影响手机升级后重新运行并导出 **新记录的 JSON**。旧记录缺失的现场无法补回。无需连接电脑、ADB 或 root。

0.12.3 fixes loss of native evidence on affinity failure. This is not a claimed workaround for a specific firmware's scheduling policy. The affected phone must rerun and export the **new report's JSON**; missing evidence cannot be reconstructed in older reports. No computer connection, ADB or root is required.

## 怎样复测 / Retesting

1. 安装新版，保持应用在前台，用原参数开始 RAM 测试。
   Install the update, keep the app in the foreground and start RAM with the original parameters.
2. 无论完成或报错，进入该条记录的详情，导出 JSON。分享截图不足以保存以下现场。
   Whether the run completes or fails, open that record's details and export JSON. A screenshot does not contain the evidence below.
3. 对比正常与失败记录的固件、目标核心、返回码及工作线程现场。可在不同电源模式下另跑一份作为对照，但不要替换原失败记录。
   Compare firmware, target CPUs, return codes and worker context between successful and failed reports. A separate run under another power mode can serve as a control; retain the original failure.

## 安卓字段 / Android fields

| 位置 / Location | 含义 / Meaning |
| --- | --- |
| `device` | 固件指纹、安全补丁、增量版本 / Firmware fingerprint, security patch, incremental build |
| `capabilities.capture_stage` | 原能力快照采于前台服务启动前 / Original capabilities were captured before foreground startup |
| `runtime_diagnostics.at_start` | 执行线程启动后的前台状态、唤醒锁、省电、息屏/空闲、热状态、进程重要性及内核版本 / Runtime foreground/wakelock, power, screen/idle, thermal, importance and kernel evidence |
| `runtime_diagnostics.before_cleanup` | 释放前台服务、唤醒锁之前的结束状态 / Final snapshot before foreground/wakelock release |
| `failure_context` | 最近一次失败所处阶段、计划、轮次及完整采样；原记录仍保留在对应校准/轮次/曲线中 / Latest failed stage, plan, round and full sample; original records remain in calibration, rounds or curves |
| 每个原生采样的 / Per native sample `affinity_diagnostics` | 调用线程允许集合、目标核心、每个工作线程的绑核证据 / Caller allowed mask, requested CPUs and per-worker affinity evidence |
| `workers[].before / set / readback` | 设置前查询、设置、即时回读的返回值、errno、单调时间；查询失败的集合为 null / Query, set and immediate-readback return codes, errno and monotonic timestamps; failed query masks are null |
| `workers[].before_context / after_context` | 实际工作线程 PID/TID/UID、当前核心、调度策略、nice、cpuset/cgroup、在线核心及白名单状态字段 / Actual worker PID/TID/UID, current CPU, scheduler, nice, cpuset/cgroup, online CPUs and allowlisted status |
| `workers[].failure_reason` | 区分初始查询失败、核心不允许、设置失败、回读失败、非单核心集合、错误核心和测量端点迁移 / Distinguishes initial query, denied target, set, readback, non-singleton/wrong masks and endpoint migration |
| `runtime_at_failure` | 该采样失败后的运行状态 / Runtime state immediately after that sample fails |

`errno=0` 表示相应调用成功，不复用前一次调用残留的 errno；文件不可读时单独保留 errno，不能把空值解释成“没有限制”。上下文不是原子快照，时间戳有助于识别瞬时变化。端点核心一致不能证明整段期间从未发生调度变化。

`errno=0` means the associated call succeeded, without retaining stale errno. Unreadable files carry their own errno; missing data does not mean unrestricted scheduling. Context reads are not an atomic snapshot; timestamps help identify transient changes. Matching endpoint CPUs cannot prove no scheduling change occurred between them.

文件读取和 JSON 生成位于正式计时区间外。块延迟曲线现在也即时回读绑核集合，验证失败不产生核心成绩。校准验证失败在异常上抛前保存完整记录；不会把未绑核成绩当作目标核心成绩。现有 RAM 64 MiB/T1、三次平均、曲线每块一次及 ROM 默认值不变。

File reads and JSON serialization run outside scored timing. Curves now verify the affinity mask immediately as well, and failed verification produces no core score. Calibration verification failures persist the complete record before propagation. RAM 64 MiB/T1, three-round means, single-sample curves and storage defaults are unchanged.

诊断只读取本进程工作线程与 CPU 调度相关字段，不读取进程命令行、环境变量、账户或网络信息。调试版有明确标记的故障注入，用于回归保存/导出链路；正式版不启用注入。

Diagnostics read only this process's worker scheduling context, excluding command lines, environment variables, accounts and network information. Clearly marked Debug-only fault injection exercises persistence/export; injection is disabled in Release.

## 苹果字段 / Apple fields

`runtime_diagnostics` 保存报告创建、执行开始及结束时的系统版本、进程号、活动处理器数、省电与热状态。`affinity_capability=unavailable_system_scheduled_qos` 明确表示公开接口不提供安卓式绑核；已有采样保留请求的 QoS，不伪造物理核心编号或 Linux cpuset。新增字段可选，旧 JSON 继续可读。最低系统仍为 iOS/iPadOS 16.0。

`runtime_diagnostics` records report creation, worker start and finish with OS version, PID, active processor count, low-power mode and thermal state. `affinity_capability=unavailable_system_scheduled_qos` explicitly records the lack of Android-style public affinity controls. Samples retain requested QoS without fabricated physical CPU IDs or Linux cpusets. New fields are optional for legacy JSON compatibility; iOS/iPadOS 16.0 remains the minimum.

## 安卓验收 / Android acceptance

应用源码 `da942eeef0c5b9c5b56def0656a53540035773d8`：Debug、签名 Release、androidTest 构建与两种 Lint 通过。API 37 模拟器 39 项测试通过，覆盖故障注入、失败保存/导出、正常绑核、取消、参数、曲线与存储；正式包从 0.12.2 覆盖升级启动通过。已核对真实导出中成功采样的核心集合及失败采样的返回码和 errno。ARM64 Release 的注入入口反汇编确认直接返回 false。

Application source `da942eeef0c5b9c5b56def0656a53540035773d8` passed Debug, signed Release and androidTest builds plus both Lints. All 39 API 37 simulator tests passed, covering fault injection, failure persistence/export, valid affinity, cancellation, parameters, curves and storage. Signed upgrade/launch from 0.12.2 passed. Exported success masks and failure return codes/errno were checked; ARM64 Release disassembly confirms the injection entry returns false directly.

这些是功能验收，不代表已在报错的 HyperOS 手机复现或修复其调度行为。原机仍需安装新版重新测试。

These are functional checks, not a reproduction or scheduling-policy fix on the affected HyperOS phone. That device still needs a new run with this version.

## 苹果验收 / Apple acceptance

[对应源码 CI](https://github.com/Hashiao/BenchBridge/actions/runs/37942519682) 的两套工具链均通过共享原生回归；iOS 18.5/27.0 的 iPhone/iPad 共 44 项通过、无跳过，包括报告导出、恢复及新旧 JSON 兼容。已核对真机 ARM64 IPA 与主程序最低系统均为 16.0。没有可用的 iOS 16 模拟器运行时，本次不宣称已在 iOS 16 或用户真机上执行测试。

Both toolchains in [matching-source CI](https://github.com/Hashiao/BenchBridge/actions/runs/37942519682) passed shared native regressions and 44 iPhone/iPad tests on iOS 18.5/27.0 with no skips, including export/recovery and new/legacy JSON compatibility. The ARM64 device IPA and executable minimum OS were verified as 16.0. No iOS 16 simulator runtime was available; no iOS 16 runtime or physical-device test is claimed.
