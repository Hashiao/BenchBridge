# 全项目语言约定 / Project-wide localization

这是长期维护规则。安卓与 iPhone/iPad 应用维护简体中文、繁体中文和英文；关键代码注释、README、开发文档、历史说明及发布说明维护简体中文与英文双语。第三方许可证、原始日志、硬件标识和测量数据保留原文。

This is a persistent maintenance rule. Android and iPhone/iPad apps support Simplified Chinese, Traditional Chinese and English. Key code comments, READMEs, development guides, historical documentation and release notes are bilingual in Simplified Chinese and English. Preserve third-party licenses, raw logs, hardware identifiers and measurements verbatim.

## 语言选择 / Language selection

只看用户首选语言，不因第二首选中文而改变其他语言用户的英文回退。显式文字脚本优先于地区。

Use only the primary preferred language. A secondary Chinese preference must not override English fallback for a non-Chinese primary language. Explicit script takes precedence over region.

| 手机语言 / Device language | 应用语言 / App language |
| --- | --- |
| `zh-Hans`、`zh-CN`、`zh-SG`、无脚本/地区的 `zh` | 简体中文 / Simplified Chinese |
| `zh-Hant`、`zh-TW`、`zh-HK`、`zh-MO` | 繁體中文 / Traditional Chinese |
| `zh-Hans-TW`、`zh-Hans-HK` | 简体中文，显式 Hans 优先 / Simplified; explicit Hans wins |
| `zh-Hant-CN`、`zh-Hant-SG` | 繁體中文，显式 Hant 优先 / Traditional; explicit Hant wins |
| 英语、日语、法语、阿拉伯语及其他语言；不支持的中文文字脚本 | 英文 / English |

安卓读取系统语言列表的第一项，并为界面与资源创建对应的语言环境；三个受支持界面均使用从左到右的布局。苹果读取 `Locale.preferredLanguages` 第一项，再明确选择对应资源包；不依赖 Bundle 自动匹配第二语言。

Android reads the first system locale and creates the matching UI/resource context. All three supported interfaces use left-to-right layout. Apple reads the first `Locale.preferredLanguages` entry and explicitly selects its resource bundle, without relying on Bundle matching a secondary language.

## 繁中审校 / Traditional Chinese review

繁中采用台湾常见技术用语，香港/澳门也使用这份文案。转换工具只能产生草稿，必须按上下文审校；例如测试“项目”应为“項目”，不能把它翻成软件工程的“專案”。

Traditional Chinese uses conventional Taiwan technical terminology, shared with Hong Kong/Macao. Conversion tools may produce a draft only; contextual review is required. For example, a test item is 項目, not 專案 (a software project).

| 简体中文 / Simplified | 繁體中文 / Traditional | English / 语境 |
| --- | --- | --- |
| 内存 | 記憶體 | Memory |
| 缓存 | 快取 | Cache |
| 线程 | 執行緒 | Thread |
| 带宽 | 頻寬 | Bandwidth |
| 顺序读取 | 循序讀取 | Sequential read |
| 存储空间 | 儲存空間 | Storage space |
| 系统调度 | 系統排程 | System scheduling |
| 依赖访问 | 相依存取 | Dependent access |
| 采样 | 取樣 | Sampling |
| 分形图像 | 碎形影像 | Fractal image |
| 测试对象 | 測試對象 | Test target, not a programming object |
| 测试项目 | 測試項目 | Test item, not a project |
| 设置 / 配置参数 | 設定 / 設定參數 | Settings / configuration |
| 分配内存 | 配置記憶體 | Memory allocation; 配置 is correct in this context |
| 导出 / 文件 | 匯出 / 檔案 | Export / file |
| 数据校验 / 校准 | 資料驗證 / 校準 | Verification / calibration, distinct concepts |

## 文案维护 / Maintaining copy

统一编辑 `localization/catalog.json`。每个稳定键包含来源文字、`en`、`zh-Hans`、`zh-Hant` 和使用位置。更新表达时保持键稳定；不要直接修改生成的 XML、`.strings`、资源索引或旧记录模板。

Edit `localization/catalog.json` as the single source of truth. Each stable key includes source text, `en`, `zh-Hans`, `zh-Hant` and usage locations. Keep keys stable when revising copy. Do not directly edit generated XML, `.strings`, resource indices or legacy-description templates.

```sh
python tools/generate-localizations.py
python tools/check-localization.py
```

`{0}`、`{1}` 等为跨平台插值参数；`%.2f`、`%d` 等保留现有数值格式。三种译文必须保留相同参数集合与格式类型，可以按语法调整插值顺序。品牌、协议名、单位、JSON 键、错误码、CPU 编号与实测数字不翻译。

`{0}`, `{1}` and similar tokens are cross-platform interpolation arguments. `%.2f`, `%d` and similar tokens retain existing numeric formats. All translations must preserve the argument set and format types, while allowing grammatical reordering. Do not translate brands, protocols, units, JSON keys, error codes, CPU IDs or measured numbers.

安卓默认 `values/strings.xml` 为英文，简中与繁中使用脚本限定资源目录；苹果使用 `en.lproj`、`zh-Hans.lproj`、`zh-Hant.lproj`，开发语言为英文。界面文字、设置、错误、通知和无障碍说明均通过 `L10n` 获取。

Android's default `values/strings.xml` is English, with script-qualified Simplified/Traditional resources. Apple uses `en.lproj`, `zh-Hans.lproj` and `zh-Hant.lproj`, with English as the development language. UI copy, settings, errors, notifications and accessibility descriptions use `L10n`.

## 历史与导出 / History and export

历史记录的已知说明在显示时翻译，不修改历史原件。JSON 保留原始测量及诊断字段，另附 `export_locale` 与 `localized_summary`，方便用当前语言阅读；系统提供的原始硬件名与未知诊断保留原文。

Known historical descriptions are translated at display time without modifying stored originals. JSON preserves raw measurement/diagnostic fields and adds `export_locale` and `localized_summary` for reading in the current language. System-provided hardware names and unknown raw diagnostics remain verbatim.

## 验证 / Validation

- 校验共享词库、三语资源、占位符、英文回退和繁中禁用术语。
  Check the shared catalog, all three resources, placeholders, English fallback and disallowed Traditional Chinese terminology.
- 验证简中、繁中、英文，以及“日语/阿拉伯语第一、中文第二”的场景。
  Verify Simplified Chinese, Traditional Chinese, English, and Japanese/Arabic primary languages with Chinese secondary preferences.
- 检查导航、设置、结果、历史、导出、通知与无障碍标签，确认长文本不会遮挡按钮或成绩。
  Check navigation, settings, results, history, export, notifications and accessibility labels; longer copy must not obscure controls or scores.
- 保留 RAM/ROM/GPGPU 原有参数与测量回归；语言变化不改变配置哈希的机器字段和测量口径。
  Retain RAM/ROM/GPGPU parameter and measurement regressions. Language changes must not alter machine fields in configuration hashes or score definitions.

## 0.13.0 验收 / Acceptance

应用源码 `444f01ee57206c3c58b33cd66126ce693cc42695`；Apple CI 提交 `a24a3158741604d233b98f545d36f1ab6e30bcf0` 与其苹果源码一致；最终标签仅增加验收文档。

Application source: `444f01ee57206c3c58b33cd66126ce693cc42695`; Apple CI commit `a24a3158741604d233b98f545d36f1ab6e30bcf0` has identical Apple sources. The final tag adds acceptance documentation only.

0.13.0 验证：安卓构建、两种 Lint（无问题）和 77 个不同回归用例通过；补齐历史模板与缓存同步后，3 个语言用例以简中、繁中、英文分别复测通过。英文小屏 360×640 / 130% 字体、非中文首选语言回退、签名覆盖升级通过，22 份历史记录哈希不变，六个原生库与 0.12.4 逐字节一致。[Apple CI](https://github.com/Hashiao/BenchBridge/actions/runs/37990732712) 双工具链原生检查及 iPhone/iPad 共 56 项通过、无跳过，最低系统 16.0；本版真机仍待复测，SMB 按要求暂缓。

0.13.0 verification: Android builds, both Lints (no issues) and 77 distinct regression cases passed. After completing legacy templates and cache synchronization, all three language cases passed again under Simplified Chinese, Traditional Chinese and English. English at 360×640/130% font size, non-Chinese primary-language fallback and signed upgrade passed, preserving hashes of 22 reports. Six native libraries are byte-identical to 0.12.4. [Apple CI](https://github.com/Hashiao/BenchBridge/actions/runs/37990732712) passed native checks on both toolchains and 56 iPhone/iPad cases with no skips, retaining minimum OS 16.0. Physical-device acceptance remains pending; SMB stays deferred.

苹果 CI 实际运行时记录在本次构建附件，IPA 的 MinimumOSVersion 与主程序 minos 都检查为 16.0。没有提供 iOS 16 模拟器运行时，不将最低部署版本当作该版本真机验收。

Actual Apple simulator runtimes are recorded in the CI artifacts. Both IPA MinimumOSVersion and executable minos were checked as 16.0. No iOS 16 simulator runtime was available; the deployment target is not a claim of physical-device validation on that OS.
