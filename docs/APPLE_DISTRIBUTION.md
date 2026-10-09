# 苹果端安装、真机测试与上架 / Apple installation, device testing and distribution

适用于 BenchBridge 0.12.1；资料核对日期 2026-10-08。应用最低 iOS/iPadOS 18.0，当前是供真机验收的首版，不是已经上架 App Store 的成品。

For BenchBridge 0.12.1; references checked on 2026-10-08. Minimum iOS/iPadOS 18.0. This first version is ready for device acceptance testing; it is not already an App Store release.

## 1. 下载哪一个文件 / Choose the download

| 文件 / File | 用途 / Purpose |
|---|---|
| [BenchBridge-release.apk](https://github.com/Hashiao/BenchBridge/releases/latest/download/BenchBridge-release.apk) | 安卓签名安装包。 / Signed Android installer. |
| [BenchBridge-iOS-unsigned.ipa](https://github.com/Hashiao/BenchBridge/releases/latest/download/BenchBridge-iOS-unsigned.ipa) | 真正的 ARM64 iPhoneOS 构建，适用于 iPhone/iPad，但需要个人签名后才能安装；不是 TestFlight/App Store 包。 / Real ARM64 iPhoneOS build for iPhone/iPad, requiring personal signing before installation; not a TestFlight/App Store distribution package. |
| 同名 `.sha256` / Matching `.sha256` | 下载后核对文件；重新签名后的 IPA 摘要会改变。 / Verify the downloaded file; re-signing changes its hash. |

在 Windows 可用 `Get-FileHash .\BenchBridge-iOS-unsigned.ipa -Algorithm SHA256`，与 Release 的同名校验文件比较。

On Windows, run `Get-FileHash .\BenchBridge-iOS-unsigned.ipa -Algorithm SHA256` and compare with the matching Release checksum file.

## 2. 今晚用 Windows 安装 / Install from Windows for testing

Windows 可尝试第三方 [Sideloadly](https://sideloadly.io/)：按官网装好所需的 iTunes/iCloud，USB 配对后导入 IPA，用自己的 Apple 账号签名。免费签名通常有效 7 天；后续 Wi-Fi 安装需已配对且同网。官网写明支持 iOS 26+，用户已反馈 0.11.0 在 iOS 27 安装运行成功；0.12.0 仍需重新验收。

Windows users can try third-party [Sideloadly](https://sideloadly.io/): follow its iTunes/iCloud prerequisites, pair over USB, import the IPA and sign with their Apple account. Free signatures normally expire in seven days; Wi-Fi requires pairing and the same network. It advertises iOS 26+; the user reported successful 0.11.0 installation on iOS 27; 0.12.0 requires fresh acceptance.

签名安装与应用模拟器验收是不同环节；当前远程电脑不能直接当作已配对设备。认证信息只在自己使用的签名工具中输入，不放进 JSON、聊天或仓库。

Signing/installation and simulator acceptance are different steps; the remote PC is not automatically paired. Keep authentication in your chosen signing tool, outside reports, chat and the repository.

如系统要求，按 [Apple 的开发者模式说明](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device) 在“设置 → 隐私与安全性 → 开发者模式”开启并完成重启确认；开发者信任提示按设备显示处理。安装失败时记录完整错误文字、系统版本和签名工具版本。当前没有 TestFlight 链接；付费会员配置好 TestFlight 后才可使用那条远程安装路径。

When required, follow [Apple's Developer Mode instructions](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device) under Settings → Privacy & Security → Developer Mode, including restart confirmation. Follow any device trust prompts. On failure, record the error, OS and signing-tool version. There is no TestFlight link yet; that remote-install path needs a configured developer membership and uploaded build.

## 3. 真机验收顺序 / Device acceptance sequence

1. iPhone 与 iPad 分别记录型号、系统版本和应用版本。先让设备降温，关闭低电量模式；测试时保持应用在前台，不同时运行其他重负载任务。
   Record model, OS and app version on each device. Let it cool, disable Low Power Mode, keep the app foregrounded and avoid other heavy work.
2. 设置中先选“快速测试”，分别运行 RAM、ROM、GPGPU，确认能结束、停止、查看历史和导出 JSON。RAM 应先出现读、写、延迟、拷贝四项，曲线随后扫描。
   Start with the Quick preset for RAM, ROM and GPGPU. Check completion, stop, history and JSON export. Four RAM scores appear before curve scanning.
3. 分别在 RAM、ROM 页面恢复“标准测试”：RAM 读取、写入、延迟、拷贝均为 64 MiB、T1（自动线程关闭），四项各 3 次取算术平均值；曲线为 4 KiB–64 MiB、T1、单遍且每块 1 次；ROM 为 1 GiB、3 次、预热/测量/间隔 1/5/1 秒、四行 Q8T1/Q1T1/Q32T1/Q1T1。0.12.0 的预设仅影响当前测试类型。用标准配置测完整曲线，冷却后按相同参数复测；各类结果分别保存，不拿模拟器成绩与真机比较。
   Restore Standard separately on RAM and ROM: 64 MiB/T1 RAM read/write/latency/copy with automatic threads off and the arithmetic mean of three rounds per score, plus a single-worker 4 KiB–64 MiB sweep with one sample per block, and a 1 GiB storage file with three repetitions, 1/5/1-second warmup/measurement/interval across Q8T1/Q1T1/Q32T1/Q1T1 rows. In 0.12.0 presets affect only the current family. Run the complete standard curve and repeat after cooling with identical parameters; save each report and do not compare simulator scores with device performance.
4. 每项结束后“导出 JSON”，将 iPhone/iPad 各自的文件连同异常截图提供给开发者；附上插电状态、低电量模式、是否切后台及复现步骤。后台/锁屏会停止本版苹果端测试，已完成数据仍保存。
   Export each report and provide per-device JSON plus issue screenshots, power state, Low Power Mode, backgrounding and reproduction steps. Backgrounding/locking stops this Apple version while retaining completed data.
5. 更新、换签名方式或移除应用前先导出历史；不同 Bundle ID 的安装可能使用不同数据目录。
   Export history before updates, changing signing methods or uninstalling; different bundle IDs may use separate data containers.

“部分测量未通过验证”表示某项失败或部分曲线点缺测（双向复核模式还会检查重复性），应结合详情和原始采样判断。系统不会为了让曲线好看而补造分数。当前无法固定到指定物理核心，优先级曲线不能当作大小核曲线；GPU AES/SHA 未实现、GPU FP64 不支持是已知边界，CPU 对应项目已实现。完整协议见 [苹果端工程说明](../ios/README.md)。

“Some measurements did not pass verification” can mean missing curve points or failed workloads (bidirectional mode also checks repeatability); inspect details/raw samples. Scores are not invented to beautify a curve. Physical-core pinning is unavailable; priority curves are not P/E-core curves. GPU AES/SHA are unimplemented and GPU FP64 unavailable; CPU counterparts are implemented. See the [Apple engineering guide](../ios/README.md) for the protocol.

## 4. 真机通过后提交 App Store / App Store after device acceptance

1. **开通会员。**通过 [Apple Developer Program](https://developer.apple.com/programs/enroll/) 注册个人或组织会员；当前标准价 99 美元/年，实际按地区结算。个人会员的法定姓名会作为卖家名称显示。
   **Enroll.** Join the [Apple Developer Program](https://developer.apple.com/programs/enroll/) as an individual or organization. Standard pricing is USD 99/year with regional pricing. Individual sellers display their legal name.
2. **确认应用身份。**在自己的团队注册可用 Bundle ID，在 [App Store Connect 创建 App](https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app)，使项目、签名配置和 App 记录一致。仓库当前使用 `io.benchbridge.ios`，不代表已在你的团队完成注册。
   **Set identity.** Register an available bundle ID in your team and [create the App Store Connect record](https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app). Match project, signing and store identity. The repository's `io.benchbridge.ios` is not a claim of registration in your team.
3. **生成分发构建。**在受控云端 Mac 配置分发签名、profile 和上传凭据，重新 Archive/导出并[上传 App Store Connect](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/)。每次上传递增 build 号。当前 CI 仅做无签名构建和模拟器验证，还没有接入你的 Apple 团队或商店上传流程；现有未签名 IPA 不能直接提交审核。
   **Build for distribution.** Configure signing, profiles and upload credentials on a controlled cloud Mac, then archive/export and [upload](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/). Increment the build number for each upload. Current CI performs unsigned builds/tests only; no Apple team or store-upload integration is configured. The unsigned IPA cannot be submitted directly.
4. **先用 TestFlight 复验。**构建处理完成后，通过 [TestFlight](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview) 安装分发签名版本并再次测试。内部/外部测试流程不同，外部测试可能需要 Beta 审核；开发侧载通过不等于商店构建已验收。
   **Recheck through TestFlight.** Once processing finishes, test the distribution-signed build through [TestFlight](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview). Internal/external flows differ; external testing may require Beta Review. A sideload pass is not acceptance of the store build.
5. **补齐商店资料。**准备名称、描述、分类、年龄分级、真实 iPhone/iPad 截图、支持网址、审核联系方式和测试步骤。所有 iOS App 需要[隐私政策网址与数据处理申报](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy)。本项目也有 AES 测试，需要据实际用途填写[加密合规问卷](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance)，不预先编造豁免结论。
   **Complete metadata.** Prepare name, description, category, age rating, actual device screenshots, support URL and review contact/instructions. Supply the required [privacy-policy URL/data disclosures](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy) and answer the [encryption questions](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance) for the actual AES benchmark use; no exemption is assumed.
6. **提交审核。**选择验收过的 build 和发布地区，设置手动或自动发布，按 [Apple 提交流程](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/submit-an-app) 提交。选择中国大陆地区时，还要核对[适用的备案与资料要求](https://developer.apple.com/cn/help/app-store-connect/reference/app-information/app-information/)。通过审核后才会上架，测试通过不保证审核通过。
   **Submit.** Select the accepted build, storefronts and release mode, then [submit for review](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/submit-an-app). Check [applicable filing requirements](https://developer.apple.com/cn/help/app-store-connect/reference/app-information/app-information/) for mainland China. Testing success does not guarantee review approval.

没有自有 Mac 仍可采用 Windows 编辑＋云端 Mac 签名构建上传；App Store Connect 的资料管理可通过浏览器处理。Apple 账号、会员付款、团队授权和最终发布决定由所有者完成；证书私钥和 API 密钥不能进入源码、公开 Release 或日志。

A personally owned Mac is not required: edit on Windows and sign/build/upload on a cloud Mac, while managing metadata in a browser. The owner handles account enrollment/payment, team authorization and the final publication decision. Keep signing private keys and API keys out of source, public Releases and logs.

正式上架前还需处理本版未实现的功能入口、隐私/支持页面、签名上传配置，以及真实设备上的稳定性和测量一致性。以上是待完成事项，不表示已经代为提交 App Store。

Before store submission, resolve unimplemented feature entries, privacy/support pages, signing/upload configuration and physical-device stability/repeatability. These are remaining tasks, not a claim that submission has happened.

## 5. 0.11.0 历史验证依据 / Historical 0.11.0 evidence

苹果二进制来自提交 `5a3748fa967f41837622dd1a388c1a8162966610` 的 [成功 CI](https://github.com/Hashiao/BenchBridge/actions/runs/37755229929)：iOS 18.5 与 27.0 各自的 iPhone/iPad 模拟器，每组 6 项单元测试＋2 项界面测试，共 32 次通过，无跳过；两个工具链的 ARM64 iPhoneOS Release 编译通过。后续安装指引提交仅修改文档，不改变这份已验证二进制的应用源码。真机结果待用户回传。

The Apple binary comes from commit `5a3748fa967f41837622dd1a388c1a8162966610` and its [successful CI](https://github.com/Hashiao/BenchBridge/actions/runs/37755229929): six unit plus two UI tests on each iPhone/iPad simulator for iOS 18.5 and 27.0, 32 passes with no skips, and successful ARM64 iPhoneOS Release builds on both toolchains. Subsequent installation-guide commits change documentation only, not this verified binary's application source. Physical-device results remain pending.

0.12.0 的参数与能力见 [跨平台对齐说明](CROSS_PLATFORM_DEFAULTS.md)，构建/测试结果以对应新 Release 为准，不沿用上面的旧版通过记录。

For 0.12.0, see [aligned defaults](CROSS_PLATFORM_DEFAULTS.md) and its matching Release for fresh build/test outcomes; the historical results above do not validate the new binary.

0.12.0 最终应用源码为 `297161462c683e11eb6c3e30f3f4f89f61db252c`，[对应 CI](https://github.com/Hashiao/BenchBridge/actions/runs/37873837857) 完成两套工具链真机 ARM64 构建，以及 iOS 18.5/27.0 各自 iPhone/iPad 共 44 项通过、无跳过。发布标签仅在其后补充验收文档，不改应用源码。安卓 37 项 API 37 模拟器回归、构建/Lint、签名与覆盖安装启动均通过；新版本手机性能仍需真机实测。

The final 0.12.0 application source is `297161462c683e11eb6c3e30f3f4f89f61db252c`. [Matching CI](https://github.com/Hashiao/BenchBridge/actions/runs/37873837857) completed ARM64 device builds on both toolchains and 44 iPhone/iPad tests across iOS 18.5/27.0 with no skips. The release tag adds acceptance documentation only. Android's 37 API 37 emulator regressions, builds/Lint, signing and upgrade/launch passed; new-version phone performance still requires physical testing.
