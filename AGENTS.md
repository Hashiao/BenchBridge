# 项目约定 / Project conventions

## 长期语言要求 / Persistent language requirements

- 此规则贯穿整个项目及后续所有功能，不是一次性翻译任务。Android 和 iPhone/iPad 的用户界面必须同时维护简体中文、经术语审校的繁体中文、英文。
  Apply this rule throughout the project and every future feature, not only this translation task. Maintain Simplified Chinese, terminology-reviewed Traditional Chinese and English for Android and iPhone/iPad interfaces.
- 读取用户首选语言：简体中文显示简中，繁体中文显示繁中，其他语言一律显示英文。不能因为第二首选语言是中文而让其他语言用户看到中文。显式 Hans/Hant 优先于地区；无脚本时 TW/HK/MO 使用繁中，其他 zh 使用简中。
  Use the primary preferred language: Simplified Chinese gets Simplified Chinese, Traditional Chinese gets Traditional Chinese, and every other language gets English. A secondary Chinese preference must not override this rule. Explicit Hans/Hant takes precedence over region; without a script, TW/HK/MO use Traditional Chinese and other zh locales use Simplified Chinese.
- 繁中不是逐字转换：采用台湾常见技术用语，并按上下文审校，例如「記憶體、快取、執行緒、頻寬、循序讀取、儲存空間、設定、匯出」。香港/澳门繁中使用同一份已审校文案。品牌、单位和机器字段保留标准写法。
  Traditional Chinese requires contextual terminology review, using conventional Taiwan technical terms such as 記憶體, 快取, 執行緒, 頻寬, 循序讀取, 儲存空間, 設定 and 匯出. Hong Kong/Macao Traditional Chinese use the same reviewed copy. Preserve standard brands, units and machine-readable fields.
- 新增或修改任何用户可见文字时，同步更新三种语言及占位符校验；覆盖设置、提示、错误、通知、无障碍标签、分享/导出和历史记录显示。协议标识、JSON 键、错误码及实测数字不得因语言切换改变。
  Update all three languages and placeholder checks whenever user-facing copy changes, including settings, messages, errors, notifications, accessibility, sharing/export and history rendering. Locale must not change protocol identifiers, JSON keys, error codes or measured numbers.
- 关键代码注释、GitHub README、项目文档和发布说明采用简体中文与英文双语；新增功能遵循同样要求。保留第三方许可证、上游代码注释和原始日志/数据原文。
  Keep key code comments, GitHub READMEs, project documentation and release notes bilingual in Simplified Chinese and English for every new feature. Preserve third-party licenses, upstream comments and original logs/data verbatim.
- 发版前检查语言资源完整性、英文回退和繁中术语；至少验证简中、繁中、英文及一种不支持语言，确认长文本不会遮挡按钮或成绩，并保持已有测量回归通过。
  Before release, check resource completeness, English fallback and Traditional Chinese terminology. Verify Simplified Chinese, Traditional Chinese, English and at least one unsupported language, ensuring longer text does not obscure controls/scores and existing measurement regressions still pass.

- 项目说明和自有代码注释采用中英双语；保留上游许可证及生成文件原文。
  Keep project documentation and authored comments bilingual in Chinese and English. Preserve upstream licenses and generated text.
- 测量参数、执行计划、成绩表和导出必须一致；未知规格不填猜测值，校准轮次不计入正式成绩。
  Keep configuration, execution plans, displayed scores and exports consistent. Do not guess unknown specifications or count calibration as scored work.
- 修改测量或生命周期逻辑后运行相关设备用例。发版需通过适用构建、Lint 和设备检查，保留已有签名密钥与用户历史。
  Run relevant device cases after measurement or lifecycle changes. Releases require applicable build, Lint and device checks; preserve signing keys and user history.
- 每次完成开发、测试及发版，按 [发布流程](docs/RELEASING.md) 发布带签名 APK 的 GitHub Release，并更新本机配置的交付目录。两处文件必须具有相同 SHA-256。
  After development and testing, follow the [release process](docs/RELEASING.md): publish a GitHub Release with the signed APK and update the locally configured delivery directory. Both copies must have the same SHA-256.
- README 首页保留醒目的最新版 APK 直达链接；正式标签及附件不覆盖，失败步骤不得省略。
  Retain the prominent direct APK link in the README. Do not overwrite published tags or assets, or omit failed delivery steps.
- 凭据和私有路径配置保留在本机，不提交源码；`assembleRelease` 通过 `.local/build.properties` 中的 `releaseDropDir` 执行可选交付。
  Keep credentials and private path settings local. `assembleRelease` uses `releaseDropDir` in ignored `.local/build.properties` for optional delivery.
