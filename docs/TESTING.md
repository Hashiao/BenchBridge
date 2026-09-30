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

0.4.0 的本机验收在 API 37、x86_64、4 KiB 页环境中完成，31 个不同回归用例最终通过。Release 默认 RAM 完成 14/14 轮，默认 ROM 完成 40/40 轮。ARM64 已完成构建及 ELF 对齐检查，目标手机的缓存拓扑和性能仍需实机验证。

Local validation of 0.4.0 used API 37, x86_64 and 4 KiB pages, with 31 distinct regression cases passing their final runs. Release defaults completed 14/14 RAM rounds and 40/40 storage rounds. ARM64 builds and ELF alignment were checked; target-phone cache topology and performance still require device validation.

私有验收日志、设备报告与截图不包含在公开仓库中。上述记录描述一次验收范围，不代表所有平台均已通过测试。

Private validation logs, device reports and screenshots are not included in the public repository. These records describe one validation scope, not test coverage of every platform.
