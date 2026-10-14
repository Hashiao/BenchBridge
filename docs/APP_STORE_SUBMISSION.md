# App Store 上架准备 / App Store submission preparation

核对日期 / Checked: 2026-10-14. 目标版本 / Target: 0.13.1, build 8, iOS/iPadOS 16+.

本页区分仓库准备与 Apple 后台操作；保存文案或生成 IPA 不表示已上传、已审核或已上架。当前 GitHub Apple 工作流只构建未签名 IPA 并验证模拟器，尚未配置分发签名和 App Store Connect 上传。

This guide separates repository preparation from Apple account operations. Saving metadata or generating an IPA does not mean upload, approval or publication. The current GitHub Apple workflow builds unsigned IPAs and tests simulators; distribution signing and App Store Connect upload are not configured yet.

## 应用记录 / App record

| 字段 / Field | 内容 / Value |
|---|---|
| 平台 / Platform | iOS，含 iPhone/iPad / includes iPhone and iPad |
| 名称 / Name | BenchBridge |
| Bundle ID | `io.benchbridge.ios` |
| SKU | `benchbridge-ios` |
| 主要语言 / Primary language | English (U.S.) |
| 本地化 / Localizations | English (U.S.), Simplified Chinese, Traditional Chinese |
| 主要类别 / Primary category | 工具 / Utilities |
| 价格 / Price | 免费 / Free (0) |
| 版本 / Version | `0.13.1`，必须与构建一致 / must match the uploaded build |

App ID 的 Description 是开发者后台内部备注，`BenchBridge App Store` 足够；它不是商店介绍。此前侧载生成的带团队编号后缀的 Bundle ID 保留，不用于本次上架。新建 App 默认显示的 `1.0` 须改为当前构建版本，或在构建前统一调整双方版本，不能直接选择版本不匹配的包。

The App ID Description is an internal developer-portal label; `BenchBridge App Store` is sufficient and is not the store description. Keep older sideload identifiers with team suffixes separate. Replace the new record's default `1.0` with the actual build version, or deliberately align both before building.

## 可复制的三语文案 / Ready-to-copy metadata

- [简体中文](app-store/zh-Hans.md) / [JSON](app-store/zh-Hans.json)
- [繁體中文](app-store/zh-Hant.md) / [JSON](app-store/zh-Hant.json)
- [English](app-store/en-US.md) / [JSON](app-store/en-US.json)

在版本页右上角语言菜单添加本地化，然后分别填写推广文本、描述、关键词和支持网址；在“App 信息”填写名称、副标题和隐私网址。当前 JSON 是待填写文案，不是已实现的自动同步工具。英国、加拿大、澳大利亚英语本地化可沿用英文稿，仍需逐地区预览。

Add localizations using the version page's language menu, then enter promotional text, description, keywords and support URL per language. Set name, subtitle and privacy URL under App Information. The JSON files are prepared copy, not an implemented automatic synchronizer. Other English localizations may reuse the English copy, subject to storefront preview.

主要语言用于缺少适合本地化时的回退，不会限制上架国家。应用内语言选择与商店介绍本地化是两套独立设置。参见 [Apple 本地化说明](https://developer.apple.com/help/app-store-connect/manage-app-information/localize-app-information/)。

The primary language is a metadata fallback, not a territory restriction. In-app language selection and store metadata are separate. See [Apple localization guidance](https://developer.apple.com/help/app-store-connect/manage-app-information/localize-app-information/).

## 覆盖地区 / Distribution territories

在“价格与销售范围”将价格设为免费。优先覆盖中国大陆、中国香港、中国澳门、中国台湾，以及美国、英国、加拿大、澳大利亚、新西兰、新加坡；愿意完成其他地区申报时可选所有可分发国家和地区。可见性取决于用户 Apple 账户商店地区及实际可用状态；选择地区不保证搜索排名或自动推荐。

Set the price to Free under Pricing and Availability. Prioritize mainland China, Hong Kong, Macao, Taiwan, the United States, United Kingdom, Canada, Australia, New Zealand and Singapore. Select all eligible territories if willing to complete their additional declarations. Availability follows the customer's Apple Account storefront and actual distribution status; selecting territories does not guarantee search ranking or recommendations. [Apple availability guidance](https://developer.apple.com/help/app-store-connect/manage-your-apps-availability/manage-availability-for-your-app-on-the-app-store/).

中国大陆需核对“App 信息”中的适用备案和资料要求。Apple 说明部分 App 需要有效 ICP 备案，相关信息须与申报及简中元数据一致。不能仅因免费、开源或主要功能离线就自行认定豁免；如后台要求或适用性不明确，向 App Review 描述本机测试、无账号/服务器、主动外链的实际功能并确认。需要备案时完成后再开放该地区，其他地区可独立推进。

For mainland China, check the applicable filing fields. Apple states that some apps need a valid ICP filing and matching registered/Simplified Chinese metadata. Free/open-source status or primarily offline operation does not establish exemption. If requirements are unclear, describe the local benchmarks, absence of an account/server and user-initiated external links to App Review. Complete required filing before enabling that territory; other territories can proceed independently. [Apple requirements](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/).

若选择欧盟，须完成交易商状态申报。个人开发者、免费应用本身不能自动决定“交易商/非交易商”身份；由账户持有人按真实活动确认，不能为减少填写而代选。参见 [Apple DSA 说明](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements/)。

EU distribution requires a trader-status declaration. Individual enrollment or a free app alone does not decide trader status; the account holder must confirm the actual circumstances. See [Apple DSA guidance](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements/).

## 隐私与审核资料 / Privacy and review information

公开隐私网址 / Public privacy URL:
https://github.com/Hashiao/BenchBridge/blob/main/docs/PRIVACY.md

支持网址 / Support URL:
https://github.com/Hashiao/BenchBridge/blob/main/docs/SUPPORT.md

当前代码没有账号、广告、分析 SDK 或向维护者服务器自动上传报告的功能。本机保存与用户主动分享已在政策中说明。由所有者核对这一事实后填写 App 隐私；若以后引入联网分析或账号，必须同步更新声明。所有 iOS App 都需提供隐私政策网址，应用设备页也有入口。[Apple 隐私信息说明](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/)。

Current code has no account, ads, analytics SDK or automatic report upload to maintainer servers. The policy explains local storage and user-initiated sharing. The owner should confirm these facts when completing App Privacy, and update declarations if analytics/accounts are introduced. A privacy-policy URL is required for iOS apps and is also accessible from Device. [Apple privacy guidance](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/).

年龄分级按真实内容填写，不预设评级；加密问卷须说明存在自有 AES/SHA 基准内核、用途仅为本机计算测试，不自动填写“没有加密”或假定豁免。审核联系人的姓名、邮箱和电话由所有者提供；应用无需登录，不编造测试账号。

Complete age-rating questions from the actual content. Encryption questions must account for the app's own AES/SHA benchmark kernels used for local computation; do not automatically claim no encryption or exemption. The owner supplies a real review contact. No app login or fabricated demo account is needed.

审核备注建议 / Suggested review notes:

> BenchBridge is a free, open-source on-device benchmark. No login, subscription or purchase is required. Open RAM, ROM or GPGPU, choose Quick in Settings, then Start test. Completed reports are under History; JSON export uses the system share sheet. The Device tab links to source code and the privacy policy. Unsupported GPU workloads are labeled rather than assigned fabricated scores. Benchmarks should remain in the foreground; Stop cancels a run. Source: https://github.com/Hashiao/BenchBridge . AES/SHA implementations are used as local computation benchmarks; export-compliance responses must be completed for the actual build.

截图要来自当前版本真实界面，提供 iPhone 和 iPad 所需尺寸，避免使用旧版本成绩或其他手机的截图。商店截图不展示用户私人报告。截图语言应与对应商店文案一致。

Use actual current-version iPhone and iPad UI at the required screenshot sizes, without private user reports, obsolete screens or unrelated phone screenshots. Match screenshot language to its localization.

## Windows 开发与云端上传 / Windows development and cloud upload

无需购买 Mac：现有 GitHub macOS CI 可继续做编译和模拟器测试，随后接入分发签名。上架包必须用 Apple 当前接受的 Xcode/SDK 构建，并使用属于自己的 Apple Distribution 证书和对应 App Store Connect profile；不能拿旧工具链或侧载签名包代替。

A personally owned Mac is unnecessary: retain GitHub macOS compilation/simulator testing and add distribution signing. Build with a currently accepted Xcode/SDK and your own Apple Distribution identity plus matching App Store Connect provisioning profile. Older compatibility builds or sideload signatures are not substitutes.

分发接入需要 Team ID、证书与私钥、profile，以及上传授权。可用 App Store Connect API 密钥在后台处理元数据及上传，不占用电脑鼠标键盘。建议先以 App Manager 权限处理 App 元数据/上传；证书和 profile 的管理按所需的更高权限另外配置，不笼统要求所有密钥为管理员。密钥只放在本机私有配置或 GitHub Secrets；不用聊天传密码或私钥，也不将它们加入版本库或 Release。

Distribution needs the Team ID, signing certificate/private key, provisioning profile and upload authorization. App Store Connect API credentials allow background metadata/upload work without controlling the desktop. Prefer App Manager access for app metadata/uploads; configure certificate/profile administration separately with the required permissions rather than making every key an administrator. Store secrets only in private local configuration or GitHub Secrets, never chat, source or Release assets. [Apple API guidance](https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-api/).

签名 Archive → 导出 App Store Connect 包 → 上传 → 等待 Apple 处理 → TestFlight 真机验证 → 选择对应构建 → 添加以供审核。GitHub Release 发布与 App Store 审核是不同流程；新的 GitHub 标签不会自动更新用户的 App Store 安装。后续可配置“标签触发签名/上传”，但仍需审核和发布流程。[Apple 上传说明](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/)。

Archive with signing → export for App Store Connect → upload → await processing → validate on physical devices with TestFlight → select the matching build → add for review. GitHub Releases and App Store review are separate; a GitHub tag does not update users' App Store installations. Tag-triggered signed uploads can be configured later, followed by review/release. [Apple upload guidance](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/).

## 免费承诺与许可证 / Free commitment and license

官方版本永久免费。MIT 许可证允许第三方再分发和商业使用，本声明不修改许可证。设备页及商店介绍会引导用户辨认官方免费项目，提醒第三方下载/解锁收费不是本项目要求；不能保证阻止篡改后再分发。

The official app is free forever. MIT permits redistribution and commercial use; the notice does not alter the license. Device and store copy identify the official free project and clarify that third-party download/unlock fees are not required. This cannot guarantee prevention of modified redistribution.
