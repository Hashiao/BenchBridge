# 测试说明 / Testing

## 构建检查 / Build checks

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

Windows 使用 `gradlew.bat`。签名与可选交付目录配置见 [BUILDING.md](BUILDING.md)。

Use `gradlew.bat` on Windows. See [BUILDING.md](BUILDING.md) for signing and optional delivery settings.

## 设备测试 / Device tests

连接一台 Android 设备或启动模拟器。首次运行需先安装 Debug APK，并在 API 33 及以上设备授予通知权限：

Connect an Android device or start an emulator. Before the first run, install the Debug APK and grant notification permission on API 33 or later:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant io.benchbridge.app.dev android.permission.POST_NOTIFICATIONS
./gradlew :app:connectedDebugAndroidTest
```

测试使用 `ActivityScenario` 和 UI Automator 操作应用，覆盖原生计数与校验、并发互斥、取消、进程恢复、文件回收、设置与结果一致性、锁屏续跑、截图及大报告导出。通知相关用例需要允许测试应用发送通知。

Tests use `ActivityScenario` and UI Automator to exercise native counts and validation, run exclusion, cancellation, process recovery, file cleanup, settings/result consistency, screen-off execution, screenshots and large-report export. Notification tests require notification permission for the test app.

```sh
adb shell pm grant io.benchbridge.app.dev android.permission.POST_NOTIFICATIONS
```

也可安装 Debug 与测试 APK 后单独运行用例：

Individual tests can also be run after installing the Debug and test APKs:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class io.benchbridge.app.DashboardLifecycleTest io.benchbridge.app.dev.test/androidx.test.runner.AndroidJUnitRunner
```

用例会停止本应用的测试、终止本应用的工作进程或关闭设备屏幕。请使用测试设备，且不要同时运行手动测试。清理范围限定为用例自行创建的记录和文件。

Some cases cancel app runs, terminate the app's worker process or turn off the device screen. Use a test device and do not run manual benchmarks concurrently. Cleanup is restricted to records and files created by the tests.

## 手动检查 / Manual checks

1. RAM 与 ROM 各完成一次快速测试，检查成绩、单位和参数概要。
   Complete a quick RAM and storage run; check scores, units and configuration summaries.
2. 修改参数，确认当前成绩清空而历史保留；历史结果使用原配置。
   Change settings and verify that current scores clear while history retains the original configuration.
3. 测试期间锁屏、返回桌面、旋转屏幕和停止，检查运行状态及资源释放。
   Lock the screen, leave the app, rotate and cancel during a run; check state transitions and resource release.
4. 导出完整 ROM 报告并重新解析 JSON，检查全部轮次与文件清理状态。
   Export a full storage report, parse its JSON, and check all rounds and file-cleanup status.
5. 在小屏幕与较大系统字体下检查完整成绩表，再检查截图分享。
   Check the complete result board on a small display with larger system fonts, then verify screenshot sharing.

## 已有验证范围 / Existing validation scope

0.6.1 调整 GPGPU 成绩字号及 CPU / GPU 列间距，并增加列分隔线。两项已有界面用例通过，覆盖完整快速测试、截图、导出及配置和历史保持。签名 Release 完成快速测试；正常尺寸和 360×640、130% 字体下，24 格成绩均可见，两列文字区域不相交。原生库和着色器与 0.6.0 逐字节一致。

Version 0.6.1 reduces GPGPU score sizes, separates the CPU/GPU columns and adds a vertical divider. Two existing interface cases passed, covering the complete quick run, screenshots, export, and preservation of settings and history. The signed Release completed a quick run. All 24 score cells remain visible at the normal display size and at 360×640 with 130% font scaling, with no overlap between column text bounds. Native libraries and shaders are byte-identical to 0.6.0.

0.6.0 增加 7 个 GPGPU 用例，覆盖 CPU 运算和密码学已知答案、GPU 输出与 CPU 参考值交叉验证、取消、互斥、锁屏、历史、截图及导出。加上原有用例，44 个不同测试最终通过。一次旧 ROM 编辑器检查读到了点击前的选中状态，改为等待选中状态后，该组 5 个参数用例复测通过。

Version 0.6.0 adds seven GPGPU cases covering CPU arithmetic and cryptographic known answers, GPU/reference comparisons, cancellation, mutual exclusion, screen-off execution, history, screenshots and export. Together with existing coverage, 44 distinct cases passed their final runs. One existing storage-editor check observed selection before the click was committed; waiting for the selected state resolved it, and all five parameter cases passed again.

签名 Release 默认 GPGPU 流程处理 72/72 个计划轮次，完成 63 个有效轮次：CPU 12 项、GPU 9 项各 3 轮。该验收设备不提供 GPU FP64 / INT64，因此 FP64、INT64、Mandel 三项 GPU 成绩按能力标记为不支持。完整 JSON 已通过系统文件选择器导出并重新核对计数。24 个结果位置及单位在 360×640、130% 字体下可完整显示，主界面没有驱动或实现说明。

The signed Release default GPGPU run processed all 72 planned rounds and produced 63 valid rounds: three each for twelve CPU and nine GPU operations. The validation device does not expose GPU FP64/INT64, so GPU FP64, INT64 and Mandel were marked unsupported. Full JSON was exported through the system file picker and its counts rechecked. All 24 result positions and units fit at 360×640 with 130% font scaling; the main page contains no driver or implementation commentary.

两种 ABI 的原有 RAM / ROM 原生库均与 0.5.0 交付文件逐字节一致，已有用户记录摘要也保持一致。新增计算库完成 ARM64 / x86_64 构建、签名与 16 KiB 对齐检查；8 个 SPIR-V 着色器均已打包。GPU 运行验证使用 API 37 的虚拟设备，ARM64 手机仍需实机验收。

The original RAM/ROM libraries remain byte-identical to the 0.5.0 delivery on both ABIs, and existing user-record hashes are unchanged. The new compute library passed ARM64/x86_64 builds, signing and 16 KiB alignment checks; all eight SPIR-V modules are packaged. GPU execution was validated on an API 37 virtual device; ARM64 phones still require device acceptance.

0.5.0 的本机验收在 API 37、x86_64、4 KiB 页环境中完成，37 个不同用例通过。新增用例覆盖缓存共享域预算、型号变体匹配、绑核小工作集计数、准备阶段取消、校准取消、16 格成绩与执行计划的一致性。签名 Release 默认缓存 / RAM 完成 56/56 轮，ROM 完成 24/24 轮，完整 JSON 导出通过。

Local validation of 0.5.0 used API 37, x86_64 and 4 KiB pages, with 37 distinct cases passing. New cases cover shared-cache budgets, variant matching, pinned small-working-set counts, cancellation during setup and calibration, and consistency between all sixteen cells and their plans. Signed Release defaults completed 56/56 cache/RAM rounds and 24/24 storage rounds, with complete JSON export verified.

ARM64 与 x86_64 均已完成构建、16 KiB ELF / APK 对齐及优化后内核反汇编检查。Lint 无错误，有一项固定 Gradle 版本的更新提示。目标手机的绑核权限、缓存拓扑与性能仍需实机验收。

Both ARM64 and x86_64 passed build, 16 KiB ELF/APK alignment and optimized-kernel disassembly checks. Lint reported no errors and one update notice for the pinned Gradle version. Target-phone affinity permissions, cache topology and performance still require device validation.

成绩页另在 360×640 逻辑尺寸、130% 系统字体下检查；RAM 的 16 个成绩及各格线程 / 工作集均可见，MB/s 与 GB/s 均通过，ROM 四行结果可完整截图。测试后恢复显示设置。

The result board was also checked at a 360×640 logical size with 130% system font scaling. All sixteen RAM scores and per-cell thread/working-set summaries remained visible in both MB/s and GB/s; all four storage rows fit in one screenshot. Display settings were restored afterward.

私有验收日志、设备报告与截图不包含在公开仓库中。上述记录描述一次验收范围，不代表所有平台均已通过测试。

Private validation logs, device reports and screenshots are not included in the public repository. These records describe one validation scope, not test coverage of every platform.
