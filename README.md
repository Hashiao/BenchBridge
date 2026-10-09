# BenchBridge

## [⬇ 下载最新版 APK / Download the latest APK](https://github.com/Hashiao/BenchBridge/releases/latest/download/BenchBridge-release.apk)

[全部版本与更新记录 / All releases and changes](https://github.com/Hashiao/BenchBridge/releases)

Android、iPhone 与 iPad 的 CPU、GPU、内存与存储基准测试工具。安卓采用 Kotlin / Jetpack Compose 与 Vulkan，苹果采用 SwiftUI 与 Metal，底层测量使用 C++20。

A CPU, GPU, memory and storage benchmark for Android, iPhone and iPad. Android uses Kotlin / Jetpack Compose and Vulkan; Apple uses SwiftUI and Metal, with C++20 measurement kernels.

本仓库公开当前 0.12.3 实现。应用界面目前使用中文；项目说明和自有代码注释采用中英双语。

This repository contains the current 0.12.3 implementation. The app interface is currently in Chinese; project documentation and project-authored code comments are bilingual.

0.12.3 补齐 RAM 绑核故障现场：安卓 JSON 保留调用返回码、errno、前后核心集合、实际工作线程及 cpuset/cgroup；校准验证失败先落盘再结束。曲线增加即时绑核回读，失败点不产生分数。苹果 JSON 新增系统、省电、热状态等运行信息，最低系统仍为 iOS/iPadOS 16。遇到旧版错误请用新版复跑并导出新 JSON；本次未宣称已解决特定固件的调度限制。详见 [诊断字段与复测方法](docs/AFFINITY_DIAGNOSTICS.md)。

0.12.3 preserves RAM affinity failure evidence in Android JSON: return codes, errno, before/after CPU masks, actual worker identity and cpuset/cgroup context. Calibration verification failures persist before termination; curves verify immediate affinity readback and reject invalid scores. Apple JSON adds OS, power and thermal runtime context while retaining iOS/iPadOS 16 support. Rerun and export a new JSON for older failures; this release does not claim a firmware scheduling workaround. See [diagnostics and retesting](docs/AFFINITY_DIAGNOSTICS.md).

0.12.2 修复苹果 ROM 初始化仍使用文件缓存的问题：首次写入前即启用 F_NOCACHE，避免第一项顺序读取被准备阶段的缓存污染；I/O 缓冲统一对齐，并记录实际完成字节、提交次数与独立完成计时。旧成绩保留原值，需用新版重新测试，不对分数作倍率修正。详见 [苹果 ROM 缓存修复](docs/APPLE_STORAGE_CACHE.md)。

0.12.2 applies F_NOCACHE before Apple storage initialization writes, preventing preparation from seeding the first sequential read with file-cache data. Aligned I/O buffers and completed-byte, submission-count and independent completion-time diagnostics are included. Historical scores are preserved and require a new run, without multiplying or dividing results. See the [Apple storage cache fix](docs/APPLE_STORAGE_CACHE.md).

0.12.1 将安卓与苹果 RAM 的读取、写入、延迟、拷贝标准默认值统一为 **64 MiB、T1**，四项各测 **3 次取算术平均值**；曲线默认**单遍、每块 1 次**，关闭复测与加密补点。默认关闭带宽线程自动校准；手动多线程、自动校准及双向复核仍可选。

0.12.1 sets the standard RAM read/write/latency/copy defaults to **64 MiB, T1** on Android and Apple. Each score uses **three rounds and their arithmetic mean**; curves default to **one sweep and one sample per block**, without rechecks or refinement. Thread-count calibration is off; manual multithreading, calibration and bidirectional verification remain optional.

0.12.1 同时将 ROM 预热和间隔各缩短至 1 秒，正式测量仍为每轮 5 秒。

0.12.1 also shortens ROM warmup and interval to one second each, retaining five-second measured rounds.

0.12.0 对齐安卓与苹果的默认 RAM 64 MiB、单线程 4 KiB–64 MiB 曲线和 DiskMark ROM 配置，并统一主要操作布局。详见 [跨平台默认参数与能力边界](docs/CROSS_PLATFORM_DEFAULTS.md)。

0.12.0 aligns Android/Apple RAM defaults at 64 MiB, single-thread 4 KiB–64 MiB curves, DiskMark storage settings and main UI layouts. See [defaults and platform capabilities](docs/CROSS_PLATFORM_DEFAULTS.md).

0.12.3 验证：安卓 Debug/签名 Release/androidTest 构建、两种 Lint、39 项 API 37 回归及从 0.12.2 覆盖升级启动通过。苹果 [对应 CI](https://github.com/Hashiao/BenchBridge/actions/runs/37942519682) 两套工具链的原生回归与 iPhone/iPad 共 44 项测试通过、无跳过；IPA 和主程序最低系统均为 16.0。故障手机仍需用新版导出 JSON 复验；SMB 按用户要求暂缓。

0.12.3 verification: Android Debug/signed Release/androidTest builds, both Lints, 39 API 37 regressions and upgrade/launch from 0.12.2 passed. [Matching Apple CI](https://github.com/Hashiao/BenchBridge/actions/runs/37942519682) passed native regressions on both toolchains and 44 iPhone/iPad tests with no skips; IPA/executable minimum OS is 16.0. The affected phone still needs a fresh diagnostic JSON; SMB remains deferred by user instruction.

## iPhone / iPad

**[下载 iPhone/iPad IPA（未签名） / Download unsigned IPA](https://github.com/Hashiao/BenchBridge/releases/latest/download/BenchBridge-iOS-unsigned.ipa)** · **[安装、真机测试与 App Store 上架指引 / Installation, device testing and App Store guide](docs/APPLE_DISTRIBUTION.md)**

首次测试：将设备用 USB 接到身边的 Windows 电脑 → 按指引用自己的 Apple 账号签名安装 → 开启需要的开发者模式 → 在设备上分别跑 RAM、ROM、GPGPU → 导出 JSON。**下载 IPA 不等于已经能安装；当前没有 TestFlight 邀请。**免费个人签名有有效期；用户已反馈 0.11.0 在 iOS 27 真机安装运行成功；新测量内核仍需真机验收。

First device test: connect the device to a nearby Windows PC over USB → sign/install using your own Apple account as described in the guide → enable Developer Mode when required → run RAM, ROM and GPGPU on-device → export JSON. **Downloading the IPA does not make it installable; there is no TestFlight invitation yet.** Free personal signing expires; the user reported successful 0.11.0 installation and launch on iOS 27; the new measurement kernels still require device acceptance.

[原生苹果端工程与构建说明](ios/README.md)：0.12.1 起最低支持 iOS / iPadOS 16。包含 RAM 四项与缓存曲线、存储、CPU 和 Metal GPU 测试、历史与 JSON 导出。Windows 验证共享 C++ 核心，GitHub 的 macOS 环境编译并实跑 iPhone / iPad 模拟器；模拟器数值不代表真机性能。苹果端采用系统调度，不提供安卓式物理核心绑定。

The [native Apple project and build guide](ios/README.md) targets iOS/iPadOS 16+ starting with 0.12.1, with RAM scores/cache curves, storage, CPU/Metal GPU tests, history and JSON export. Windows validates the shared C++ core; GitHub macOS runners build and run iPhone/iPad simulators. Simulator scores are not device-performance measurements. Apple uses system scheduling without Android-style physical-core pinning.

[苹果端构建与测试 / Apple builds and tests](https://github.com/Hashiao/BenchBridge/actions/workflows/apple.yml)。Release 中的 `BenchBridge-iOS-unsigned.ipa` **需要另行签名才能安装**；GPU AES/SHA 首版未实现，FP64 GPU 项目不支持。下文的原有功能与参数描述适用于安卓端，苹果端差异见上方说明。

[Apple builds and tests](https://github.com/Hashiao/BenchBridge/actions/workflows/apple.yml). The Release asset `BenchBridge-iOS-unsigned.ipa` **requires signing before installation**. GPU AES/SHA are not yet implemented; FP64 GPU tests are unavailable. The existing feature/parameter descriptions below concern Android; Apple differences are documented above.

## 功能 / Features

- **缓存与 RAM：**不同核心组在同一坐标叠加工作集大小—延迟曲线，默认 4 KiB–64 MiB、每倍容量 8 个间隔、单遍扫描且每块采样 1 次。先测 RAM 读取、写入、延迟、拷贝四项，首页和详情均保留一行摘要；再扫描曲线并给出多个转换结论。可手动关闭 RAM；旧版纯曲线记录明确显示 RAM 未测，历史保持原协议。
  **Cache and RAM:** overlay core-group size/latency curves on shared axes, normally covering 4 KiB–64 MiB with eight intervals per octave and one sweep/sample per block. Measure RAM read/write/latency/copy first and retain a four-score row on the dashboard and in details, then scan and analyze the curves. RAM can be disabled explicitly; historical curve-only records show RAM as unmeasured without rewriting history.
- **存储：**顺序和随机读写，可配置文件大小、块大小、队列深度、线程数与缓存模式。
  **Storage:** sequential and random I/O with configurable file size, block size, queue depth, thread count and cache mode.
- **GPGPU：**独立分页，12 项 CPU / GPU 测试，包含各自内存读写、FP32 / FP64、整数运算、大块 AES-256 / SHA-1 与分形图像处理。成绩、单位和操作按钮同屏展示。
  **GPGPU:** a separate page with twelve CPU/GPU tests for memory access, FP32/FP64, integer arithmetic, bulk AES-256/SHA-1 and fractal image processing. Scores, units and controls fit on one screen.
- **结果：**一屏成绩表、截图分享、历史记录、成绩文本与完整 JSON 导出。
  **Results:** a single-screen result board, screenshot sharing, history, score summaries and full JSON export.
- **运行控制：**前台服务、屏幕常亮、锁屏续跑、取消及临时文件回收。
  **Run control:** a foreground service, screen-on behavior, continued measurement with the screen locked, cancellation and temporary-file cleanup.

## 当前范围 / Current scope

0.10.1 恢复默认 RAM 四项，修复 0.10.0 默认仅测曲线导致摘要消失的问题。组合测试的 RAM 成绩先保存，后续扫描中断时仍保留已完成成绩。读取、写入、拷贝以 GB/s 显示，延迟以 ns 显示；拷贝按读写合计。曲线的局部波动不再被详情页误写成“项目不支持”。

Version 0.10.1 restores the default four RAM measurements after 0.10.0's curve-only default removed the summary. Combined runs persist RAM scores before scanning so completed scores survive a later interruption. Read/write/copy use GB/s, latency uses ns, and copy counts both reads and writes. Curve repeatability issues are no longer labeled as unsupported features in details.

0.12.1 默认 `single-pass-index-curve-v1` 逐块单次采样，不平滑数据，不做重复性筛选。可选的 0.10.0 `dense-index-curve-v3` 使用高熵缓冲区和 32 位依赖索引链。同一点复用内存与绑核线程，记录墙钟、线程 CPU 时间及频率，按正反两遍一致性自动复核。曲线显示实测中位数，分段分析区分平台和连续变化，不把未知转换直接命名为 L1/L2/L3。详细计时范围、判据和跨工具可比性见 [缓存曲线协议](docs/CACHE_CURVES.md)。

The 0.12.1 default `single-pass-index-curve-v1` takes one sample per block without smoothing or repeatability filtering. The optional 0.10.0 `dense-index-curve-v3` uses high-entropy buffers and dependent 32-bit index chains. Reuse each point's allocation and pinned thread, record wall/CPU time and frequency, and automatically recheck bidirectional consistency. Plot measured medians while segmentation distinguishes plateaus from continuous trends, without assigning unknown transitions to L1/L2/L3. See the [cache curve protocol](docs/CACHE_CURVES.md) for timing, criteria and comparability.

逐点保存完整记录，界面只传摘要；取消或进程中断后可继续纯曲线测试，原记录保留。支持系统进程退出原因记录。模拟器验证仅证明功能和数据流程，不代替目标手机的真实缓存曲线验收。

Persist complete per-point checkpoints and send compact UI summaries. Cancelled/interrupted curve-only runs can resume while retaining the original report, with system exit information when available. Emulator checks validate functionality and data flow, not the target phone's cache behavior.

0.8.0 修复 GPU 计时范围：主成绩使用 Vulkan 设备执行时间，整段耗时另行保留；无设备时间戳时明确标记降级。8EE6 按 2+3+3 频率组映射 L1D，八核共享一份 16 MiB L2。拓扑缺失时自动执行绑核分块扫描，详情提供延迟 / 带宽曲线和拐点候选区间，不凭曲线伪造 L3 规格。

Version 0.8.0 scores GPU work using Vulkan device execution time and retains the complete wall time separately, explicitly identifying fallback timers. 8EE6 maps L1D by its 2+3+3 frequency groups and shares one 16 MiB L2 across eight cores. Missing topology triggers a pinned working-set sweep with latency/bandwidth curves and candidate transition ranges in details; curves do not fabricate L3 specifications.

RAM 带宽测试比较可用核心组合及线程数，最多 16 个线程；RAM 延迟逐核校准后使用一个绑核线程。缓存曲线直接扫描各组代表核心，不要求先确认 L1/L2/L3 容量。旧版缓存表仍按原共享域和工作集显示；每格保留当轮参数，校准轮次不计入正式成绩。

RAM bandwidth calibration compares available core combinations and thread counts, up to 16 threads; RAM latency selects one pinned core. Cache curves directly sweep each group's representative without requiring confirmed L1/L2/L3 capacities. Legacy cache tables retain their original domains, working sets and per-cell parameters. Calibration trials are excluded from scores.

存储的默认组合对齐 CrystalDiskMark 9.x：1 GiB 文件、每项每方向 3 次、每轮 5 秒，采用 Direct I/O。历史报告保留原有参数。

The storage profile follows CrystalDiskMark 9.x: a 1 GiB file, three measurements per case and direction, five seconds per measurement, and Direct I/O. Historical reports retain their original parameters.

内部 [SoC 资料库](docs/SOC_CATALOG.md) 包含 142 个条目，覆盖 2020 年以来常见安卓平台，包括骁龙 4 / 6 / 7 系、天玑、Helio、Exynos、展锐、麒麟、Tensor 及玄戒；另收录玄戒 O3、骁龙 8EE6 和 8E6。设备运行时拓扑优先，未确认字段保留空值。资料库不在界面展示，也不产生理论跑分。

The internal [SoC catalog](docs/SOC_CATALOG.md) has 142 entries for common Android platforms used since 2020, including Snapdragon 4/6/7 series, Dimensity, Helio, Exynos, UNISOC, Kirin, Tensor and XRING. XRING O3, Snapdragon 8EE6 and 8E6 are also included. Runtime topology takes precedence, and unconfirmed fields remain null. The catalog is not displayed in the interface and does not generate theoretical scores.

AIDA64 和 CrystalDiskMark 是测量项目与参数的参考。本项目独立实现 Android 测量内核，与两者没有隶属关系，不保证跨软件分数等价。

AIDA64 and CrystalDiskMark are references for test categories and parameters. BenchBridge implements its own Android measurement kernels, is not affiliated with either project, and does not promise score equivalence across tools.

## 构建 / Build

最低运行版本为 Android 10（API 29）。提供 `arm64-v8a` 和 `x86_64` 两种 ABI。

The minimum runtime is Android 10 (API 29). Supported ABIs are `arm64-v8a` and `x86_64`.

| 工具 / Tool | 版本 / Version |
|---|---|
| JDK | 25 |
| Gradle Wrapper | 9.3.1 |
| Android Gradle Plugin | 9.1.1 |
| Kotlin / Compose compiler | 2.4.10 |
| Compose BOM | 2026.09.00 |
| Compile / target SDK | 37 |
| Android SDK Build Tools | 37.0.0 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |

在 Android Studio 中打开仓库根目录，安装表中 SDK 组件，完成 Gradle 同步后运行 `app`。SDK 路径由 Android Studio 写入本机的 `local.properties`。

Open the repository root in Android Studio, install the SDK components listed above, sync Gradle, and run `app`. Android Studio writes the local SDK path to `local.properties`.

GPU 着色器还需在 `local.properties` 中设置 `glslc.dir`，指向 NDK 中含 `glslc` 的目录，配置示例见 [BUILDING.md](docs/BUILDING.md)。Windows 构建脚本会补充缺失项，保留已有设置。

GPU shaders also require `glslc.dir` in `local.properties`, pointing to the NDK directory containing `glslc`. See [BUILDING.md](docs/BUILDING.md) for examples. The Windows build script fills in a missing setting and preserves existing overrides.

命令行构建 / Command-line build:

```sh
./gradlew :app:assembleDebug
```

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名为 `io.benchbridge.app.dev`。Release 包名为 `io.benchbridge.app`，两者独立保存数据。

The Debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and uses the application ID `io.benchbridge.app.dev`. Release uses `io.benchbridge.app`; the two installations keep separate data.

Release 签名、可选的构建后复制及本机配置见 [构建说明](docs/BUILDING.md)。

See the [build guide](docs/BUILDING.md) for Release signing, optional post-build delivery and local configuration.

维护者发版流程见 [RELEASING.md](docs/RELEASING.md)。 / Maintainers: see [RELEASING.md](docs/RELEASING.md) for the release process.

## 默认存储项目 / Default storage cases

| 项目 / Case | 块大小 / Block size | 每线程队列 / Queue per thread | 线程 / Threads |
|---|---:|---:|---:|
| SEQ | 1 MiB | 8 | 1 |
| SEQ | 1 MiB | 1 | 1 |
| RND | 4 KiB | 32 | 1 |
| RND | 4 KiB | 1 | 1 |

测试复用一个内部文件，累计写入量与文件占用分别统计。默认按次数和时长完成；用户可设置累计写入上限。Direct 绕过操作系统文件数据缓存，不等于禁用设备缓存或保证每次写入立即持久化。

Tests reuse one internal file and track cumulative writes separately from file size. Runs normally finish according to the selected count and duration; an optional cumulative-write limit is available. Direct I/O bypasses the operating system's file-data cache. It does not disable device caches or guarantee immediate persistence of every write.

## 代码结构 / Source layout

| 路径 / Path | 内容 / Contents |
|---|---|
| `app/src/main/cpp` | C++ 测量内核 / C++ measurement kernels |
| `app/src/main/shaders` | GPU 计算着色器 / GPU compute shaders |
| `app/src/main/java/io/benchbridge/app/compute` | GPGPU 配置、运行与报告 / GPGPU configuration, execution and reports |
| `app/src/main/java/io/benchbridge/app/ram` | 工作进程、RAM 配置及报告 / Worker service, RAM configuration and reports |
| `app/src/main/java/io/benchbridge/app/hardware` | 拓扑探测及内部芯片匹配 / Topology discovery and internal SoC matching |
| `app/src/main/assets/soc_catalog.json` | 带来源的芯片资料 / Sourced SoC metadata |
| `app/src/main/java/io/benchbridge/app/storage` | 存储计划、能力探测及文件生命周期 / Storage plans, capability probes and file lifecycle |
| `app/src/main/java/io/benchbridge/app/ui` | 成绩表、设置与导出 / Result boards, settings and export |
| `app/src/androidTest` | 设备集成测试 / Device integration tests |

测量口径、单位和限制见 [测量说明](docs/BENCHMARKS.md)，构建与设备检查见 [测试说明](docs/TESTING.md)。

See [measurement notes](docs/BENCHMARKS.md) for counting rules, units and limitations, and [testing notes](docs/TESTING.md) for build and device checks.

GPGPU 的计算口径与运行方式见 [GPGPU.md](docs/GPGPU.md)。 / See [GPGPU.md](docs/GPGPU.md) for compute counting rules and execution.

## 贡献与许可 / Contributing and license

提交问题时请提供应用版本、设备型号、Android 版本、测试配置及可复现步骤。JSON 报告中的设备信息可按需删减。

When reporting an issue, include the app version, device model, Android version, test configuration and reproduction steps. Remove device information from JSON reports as needed.

本项目采用 [MIT 许可证](LICENSE)。贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)，第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

This project is licensed under the [MIT License](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution conventions and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party components.
