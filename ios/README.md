# BenchBridge for iPhone and iPad / iPhone 与 iPad 版本

原生 SwiftUI + C++20 + Metal 工程，最低 iOS / iPadOS 16.0，支持 iPhone 与 iPad。与 Android 共用内存和 CPU 算术内核；界面、存储调用、GPU 后端与生命周期按苹果平台实现。

Native SwiftUI + C++20 + Metal application for iPhone and iPad, targeting iOS/iPadOS 16.0+. Memory and CPU arithmetic kernels are shared with Android; UI, storage calls, GPU backend and lifecycle are implemented for Apple platforms.

默认值、苹果 SoC 型号/核心组识别与跨平台差异见 [协议对齐说明](../docs/CROSS_PLATFORM_DEFAULTS.md)。

See [aligned defaults and platform differences](../docs/CROSS_PLATFORM_DEFAULTS.md), including Apple model/core-group discovery.

0.13.0 与安卓同步增加简体中文、经术语审校的繁体中文、英文；按手机首选语言选择，其他语言统一回退英文。设置、曲线、结果、历史和导出说明同步适配。详见 [长期语言约定](../docs/LOCALIZATION.md)。苹果端保留现有 QoS 调度、运行诊断与测量行为，不实现 Linux 式物理核心绑定；详见 [兼容策略](../docs/AFFINITY_RECOVERY.md)。

0.13.0 adds Simplified Chinese, terminology-reviewed Traditional Chinese and English alongside Android. The primary device language selects Chinese when applicable; every other language uses English. Settings, curves, results, history and export summaries are covered; see the [persistent language policy](../docs/LOCALIZATION.md). Apple retains existing QoS scheduling, diagnostics and measurements without Linux-style physical CPU binding; see [recovery and limits](../docs/AFFINITY_RECOVERY.md).

## 功能与范围 / Features and scope

| 项目 / Area | 0.13.0 实现 / Implementation |
|---|---|
| RAM | 标准默认四项均为 64 MiB、T1，关闭自动线程，四项各测 3 次取算术平均值；先测读取、写入、延迟、拷贝，首页保留四项摘要；可配置工作集、线程、时长、重复次数。 / Standard defaults are 64 MiB/T1 for all four scores, with automatic threads off and the arithmetic mean of three rounds per score. Read, write, latency and copy first, with a four-score dashboard row and configurable working set, threads, duration and repeats. |
| 缓存曲线 / Cache curve | 默认 4 KiB–64 MiB、每倍容量 8 个间隔，单遍、每块仅采样 1 次，无复测和补点；显示实测点和多个持续转换区间。 / Default 4 KiB–64 MiB, eight intervals per octave, one sweep/sample per block without rechecks or refinement; measured points and multiple sustained transitions. |
| ROM | 1 GiB 文件，SEQ 1 MiB Q8T1/Q1T1、RND 4 KiB Q32T1/Q1T1，支持块/Q/T 设置。 / 1 GiB file, SEQ 1 MiB Q8T1/Q1T1 and RND 4 KiB Q32T1/Q1T1 with configurable block/Q/T. |
| CPU | 12 项，包括内存、浮点、整数、AES-256、SHA-1、Julia 与 Mandelbrot。 / Twelve tests covering memory, floating point, integers, AES-256, SHA-1, Julia and Mandelbrot. |
| GPU | Metal 内存读写拷贝、FP32、INT24/32/64、Julia；FP64 与 Mandelbrot FP64 不支持，AES/SHA 首版未实现，界面和导出明确记录原因。 / Metal memory read/write/copy, FP32, INT24/32/64 and Julia; FP64/Mandelbrot FP64 unavailable and GPU AES/SHA not yet implemented, with explicit reasons in UI/export. |
| 结果 / Reports | 每步原子保存、历史、JSON 分享、取消、上次中断恢复标记；切到后台或严重热状态时停止。 / Atomic checkpoints, history, JSON sharing, cancellation and interruption recovery; stop on backgrounding or serious thermal state. |

本版不提供固定物理核心或锁定频率。默认高优先级和后台优先级对照；QoS 只是调度提示，不能把两条曲线命名为性能核和能效核。系统没有提供的 L1/L2/L3 容量保持未知，转换区间不自动当作缓存容量。

This version does not pin physical cores or lock frequency. High and background priority comparisons are enabled by default. QoS is a scheduling hint, not proof of performance/efficiency core placement. Unreported L1/L2/L3 capacities remain unknown; measured transitions are not automatically labeled as cache capacities.

## 测量协议 / Measurement protocol

- 缓存使用高熵缓冲区与随机 32 位依赖索引链；优先采用系统报告的缓存行粒度（32/64/128/256 B），缺失时显式采用 64 B，原始记录保存 `node_stride_bytes`。同一点保持内存与工作线程，预热至少两遍链和 40 ms，然后默认采集 1 次约 30 ms 正式采样（可选双向模式每批 7 次）；单独保留墙钟和线程 CPU 时间。
  Cache measurements use high-entropy buffers and a shuffled dependent 32-bit index chain. Use the reported cache line size when it is 32/64/128/256 B, otherwise a 64 B fallback, exported as `node_stride_bytes`. Keep allocation and worker for a point, warm for at least two chain cycles and 40 ms, then collect one approximately 30 ms trial by default (seven per batch in optional bidirectional mode), retaining wall and thread CPU time separately.
- 默认单次采样不判断重复性，不复测或补点。关闭“每块仅测一次”后启用原双向复核：正反遍分别至少 5 次有效采样；MAD/中位数不超过 6%，至少 80% 采样在中位数的 ±12%，两遍中位数相差不超过合并中位数的 12%。未通过点最多重试至三批；边界加密后再次正反测量。不平滑原始中位数，不跨无效区间推断。
  Single-sample mode does not judge repeatability, recheck or refine. Disable “one sample per block” to enable the original bidirectional checks: each direction needs at least five accepted trials, MAD/median ≤6%, at least 80% within ±12%, and directional medians within 12% of the combined median. Failed points get at most three batches; refinement points are measured bidirectionally. Raw medians are not smoothed and invalid gaps are not bridged.
- RAM 带宽以 GB/s 表示，拷贝按读取与写入合计；延迟固定单线程、64 B 节点跨度，单位 ns。默认 64 MiB 工作集不保证绕过所有系统级缓存，因此不能把该值直接解释为裸 DRAM 极限。CPU 结果验证不计入正式时间。
  RAM bandwidth uses GB/s and copy counts reads plus writes. Latency uses one thread and a 64 B node stride in ns. The default 64 MiB working set is not guaranteed to bypass every system-level cache and is not a bare-DRAM peak claim. CPU validation is outside scored timing.
- GPU 使用 `MTLCommandBuffer.gpuEndTime - gpuStartTime`，预热、CPU 编码和结果校验不混入主值；完整提交等待耗时另存。设备时间戳缺失则标记不可用，不用 CPU 耗时冒充 GPU 分数。检查输出样本后才接受成绩。
  GPU scoring uses `MTLCommandBuffer.gpuEndTime - gpuStartTime`, excluding warmup, CPU encoding and validation. Full submit/wait time is recorded separately. Missing device timestamps make the result unavailable instead of substituting CPU wall time. Scores require output validation.
- 存储使用 `F_NOCACHE` 提示并记录是否成功，吞吐计时不包含末次 `fsync`，其耗时单独保存。不宣称绕过所有硬件缓存或等同安卓 Direct I/O。测试只清理自身创建的文件，累计读写量与文件大小分开记录。
  Storage requests `F_NOCACHE` and records success; final `fsync` time is stored separately from throughput timing. This does not imply bypassing all hardware caches or equivalence to Android Direct I/O. Only owned test files are removed; I/O volume and file size are recorded separately.

跨平台协议并非完全相同，不应直接据分数判断平台优劣。模拟器始终显示提示，并在 JSON 标记 `simulator`；缩短的 UI 验证另有 `functional_test` 标记。模拟器的缓存、GPU、存储数据不代表目标手机或平板。

Protocols are not fully equivalent across platforms and scores alone should not rank them. Simulator runs always show a warning and export `simulator`; shortened UI validation also marks `functional_test`. Simulator memory, GPU and storage results do not represent the target phone or tablet.

## Windows 本地验证 / Local Windows validation

Windows 可编辑全部源码，并编译运行共享 C++ 测量核心；不能运行苹果官方 iOS Simulator 或在本机完成 SwiftUI/Metal 的 iOS 构建。

Windows can edit all sources and compile/run the shared C++ measurement core. It cannot run Apple's official iOS Simulator or locally build the iOS SwiftUI/Metal application.

```powershell
powershell -ExecutionPolicy Bypass -File tools/test-apple-core.ps1
```

该脚本使用本机 Android NDK Clang、Visual Studio C++ 工具链和 Windows SDK，输出到忽略的 `.local/apple-core`。检查索引链、取消、计数、CPU 参考值和文件归属清理；苹果专用加密与 Metal 在苹果 CI 中验证。

The script uses installed Android NDK Clang, Visual Studio C++ tools and the Windows SDK, writing to ignored `.local/apple-core`. It checks index chains, cancellation, accounting, CPU references and owned-file cleanup. Apple-specific crypto and Metal are verified on Apple CI.

## macOS 与云端构建 / macOS and cloud builds

打开 `ios/BenchBridge.xcodeproj`，选择 `BenchBridge` Scheme。工程文件由无第三方依赖的脚本维护；修改文件列表后重新生成。

Open `ios/BenchBridge.xcodeproj` and select the `BenchBridge` scheme. The project is maintained by a dependency-free generator; rerun it after changing the file list.

```sh
python3 tools/generate-apple-project.py
python3 tools/test-apple.py --ci
```

[Apple CI](https://github.com/Hashiao/BenchBridge/actions/workflows/apple.yml) 在 macOS 15 / Xcode 16.4 与官方 `xcode-27` 环境执行：本机 C++ 测试、ARM64 iOS Release 编译、iPhone 和 iPad 模拟器单元及界面测试。选择与 SDK 兼容的已安装运行时；实际 Xcode、机型、系统、跳过项和结果写入 `verification.json`。日志、截图、xcresult 和未签名 IPA 保留在每次运行的附件中。

[Apple CI](https://github.com/Hashiao/BenchBridge/actions/workflows/apple.yml) uses macOS 15 / Xcode 16.4 and the official `xcode-27` environment for native C++ tests, ARM64 iOS Release builds, and iPhone/iPad simulator unit/UI tests. It selects installed runtimes compatible with the SDK and records actual Xcode, models, systems, skips and outcomes in `verification.json`. Each run retains logs, screenshots, xcresult bundles and an unsigned IPA.

测试覆盖 RAM 四项与计数、多个曲线转换、CPU 参考值及苹果加密、Metal 输出及设备计时、存储读写与清理、JSON 回读导出和中断恢复，以及 RAM/ROM/取消/历史界面流程。Metal 设备或时间戳不可用时明确跳过并记录，不算已验证性能。

Tests cover RAM accounting, multiple curve transitions, CPU references/Apple crypto, Metal outputs/device timing, storage/cleanup, JSON roundtrip/export/recovery, and RAM/ROM/cancellation/history UI flows. Unavailable Metal devices or timestamps produce recorded skips, never a device-performance validation claim.

## 安装与签名 / Installation and signing

GitHub Release 的 `BenchBridge-iOS-unsigned.ipa` 是真实 iPhoneOS ARM64 构建，不是模拟器包，但没有 Apple 签名与 provisioning profile，不能直接点开安装。安装到 iPhone/iPad 前需要使用自己的 Apple 身份完成签名与配置；本仓库不包含账号、证书或私钥。

The Release asset `BenchBridge-iOS-unsigned.ipa` is a real iPhoneOS ARM64 build, not a simulator package. It has no Apple signature or provisioning profile and cannot be installed by simply opening it. Device installation requires signing/provisioning with your own Apple identity. No account, certificate or private key is included.

没有 Mac 不影响 Windows 编辑与云端编译，但真机验收仍需要在自己的设备上运行签名后的应用。TestFlight/App Store 分发还需符合 Apple 开发者计划要求；当前没有发布到 TestFlight，0.11.0 已收到用户安装运行成功反馈；0.12.0 新内核仍待真机验收。

A Mac is not required for Windows editing and cloud compilation, but device acceptance still requires a signed app running on your hardware. TestFlight/App Store distribution also requires meeting Apple's developer-program requirements. This project has not been distributed through TestFlight ; 0.11.0 installation/launch was reported successful, while 0.12.0 kernels still need physical-device acceptance.

0.12.1 将部署目标降低到 16.0，曲线选点使用 iOS 16 图表覆盖层手势，后台监听使用旧版兼容接口。CI 同时检查 IPA 的 MinimumOSVersion 和 Mach-O 的 minos 为 16.0；实际运行过的模拟器版本单独记录，部署目标不等于该版本真机已验收。

0.12.1 lowers deployment to 16.0, uses iOS 16 chart-overlay selection and a compatible scene-phase callback. CI verifies both IPA MinimumOSVersion and Mach-O minos are 16.0. Executed simulator versions are recorded separately; a deployment target is not a claim of physical-device validation on that OS.

0.12.2 的文件初始化、预热、测量和校验句柄均使用所请求的 F_NOCACHE 策略，缓冲区统一 64 KiB 对齐。`storage_samples` 新增初始化缓存请求、缓冲对齐、实际完成字节、提交次数及独立完成计时字段；历史字段缺失仍可读取。详情见 [缓存修复与回归](../docs/APPLE_STORAGE_CACHE.md)。

In 0.12.2 preparation, warmup, measurement and validation honor the requested F_NOCACHE policy, with 64 KiB aligned buffers. New optional storage_samples fields record preparation cache policy, alignment, actual completed bytes, submissions and an independent completion clock; historical reports remain readable. See [cache fix and regression](../docs/APPLE_STORAGE_CACHE.md).
