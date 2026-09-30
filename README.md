# BenchBridge

## [⬇ 下载最新版 APK / Download the latest APK](https://github.com/Hashiao/BenchBridge/releases/latest/download/BenchBridge-release.apk)

[全部版本与更新记录 / All releases and changes](https://github.com/Hashiao/BenchBridge/releases)

Android 内存与存储基准测试工具，使用 Kotlin / Jetpack Compose 构建界面，使用 C++20 执行测量。

An Android memory and storage benchmark with a Kotlin / Jetpack Compose interface and C++20 measurement kernels.

本仓库公开当前 0.5.0 实现。应用界面目前使用中文；项目说明和自有代码注释采用中英双语。

This repository contains the current 0.5.0 implementation. The app interface is currently in Chinese; project documentation and project-authored code comments are bilingual.

## 功能 / Features

- **缓存与 RAM：**L1D、L2、L3、RAM 四行，连续读、连续写、延迟、拷贝四列；自动校准线程与核心。另保留 RAM 随机读写快测。
  **Cache and RAM:** four rows for L1D, L2, L3 and RAM, with sequential read, sequential write, latency and copy columns. Thread counts and core selections are calibrated automatically. A separate RAM quick profile retains random read and write tests.
- **存储：**顺序和随机读写，可配置文件大小、块大小、队列深度、线程数与缓存模式。
  **Storage:** sequential and random I/O with configurable file size, block size, queue depth, thread count and cache mode.
- **结果：**一屏成绩表、截图分享、历史记录、成绩文本与完整 JSON 导出。
  **Results:** a single-screen result board, screenshot sharing, history, score summaries and full JSON export.
- **运行控制：**前台服务、屏幕常亮、锁屏续跑、取消及临时文件回收。
  **Run control:** a foreground service, screen-on behavior, continued measurement with the screen locked, cancellation and temporary-file cleanup.

## 当前范围 / Current scope

带宽测试比较可用核心组合及线程数，最多 16 个线程；延迟逐核校准后使用一个绑核线程。缓存工作集按实际共享域分配，容量或共享关系无法确认时显示“—”。每格显示正式测量使用的线程和总工作集，校准轮次不计入成绩。

Bandwidth calibration compares available core combinations and thread counts, up to 16 threads. Latency calibration selects one pinned core. Cache working sets respect sharing domains; unconfirmed capacities or sharing relationships produce a dash. Each cell shows the measured thread count and total working set. Calibration trials are excluded from scores.

存储的默认组合对齐 CrystalDiskMark 9.x：1 GiB 文件、每项每方向 3 次、每轮 5 秒，采用 Direct I/O。历史报告保留原有参数。

The storage profile follows CrystalDiskMark 9.x: a 1 GiB file, three measurements per case and direction, five seconds per measurement, and Direct I/O. Historical reports retain their original parameters.

内部 [SoC 资料库](docs/SOC_CATALOG.md) 收录高通、天玑和玄戒的已核实信息，优先使用设备运行时拓扑。资料库不在界面展示，也不产生理论跑分。

The internal [SoC catalog](docs/SOC_CATALOG.md) contains verified Qualcomm, Dimensity and XRING metadata. Runtime topology takes precedence. The catalog is not displayed in the interface and does not generate theoretical scores.

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
| `app/src/main/java/io/benchbridge/app/ram` | 工作进程、RAM 配置及报告 / Worker service, RAM configuration and reports |
| `app/src/main/java/io/benchbridge/app/hardware` | 拓扑探测及内部芯片匹配 / Topology discovery and internal SoC matching |
| `app/src/main/assets/soc_catalog.json` | 带来源的芯片资料 / Sourced SoC metadata |
| `app/src/main/java/io/benchbridge/app/storage` | 存储计划、能力探测及文件生命周期 / Storage plans, capability probes and file lifecycle |
| `app/src/main/java/io/benchbridge/app/ui` | 成绩表、设置与导出 / Result boards, settings and export |
| `app/src/androidTest` | 设备集成测试 / Device integration tests |

测量口径、单位和限制见 [测量说明](docs/BENCHMARKS.md)，构建与设备检查见 [测试说明](docs/TESTING.md)。

See [measurement notes](docs/BENCHMARKS.md) for counting rules, units and limitations, and [testing notes](docs/TESTING.md) for build and device checks.

## 贡献与许可 / Contributing and license

提交问题时请提供应用版本、设备型号、Android 版本、测试配置及可复现步骤。JSON 报告中的设备信息可按需删减。

When reporting an issue, include the app version, device model, Android version, test configuration and reproduction steps. Remove device information from JSON reports as needed.

本项目采用 [MIT 许可证](LICENSE)。贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)，第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

This project is licensed under the [MIT License](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution conventions and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party components.
