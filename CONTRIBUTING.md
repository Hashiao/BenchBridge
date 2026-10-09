# 贡献约定 / Contributing

请将行为变更、界面调整与文档整理分开提交，说明改动的原因和验证方式。

Keep behavior changes, interface changes and documentation edits in separate commits. Describe the reason for each change and how it was verified.

## 代码与文档 / Code and documentation

- 界面文案必须同步提供简体中文、经术语审校的繁体中文和英文，统一维护 `localization/catalog.json`；其他手机语言统一使用英文，不按第二语言回退中文。按 [语言维护指南](docs/LOCALIZATION.md) 生成资源并运行校验；新增功能遵循同样要求。
  Provide Simplified Chinese, terminology-reviewed Traditional Chinese and English for all UI copy in `localization/catalog.json`. Every other primary device language uses English, without a secondary-Chinese fallback. Follow the [localization guide](docs/LOCALIZATION.md) to generate and check resources; this applies to every future feature.
- 自有代码注释使用中英双语，解释约束和设计原因，避免复述代码。
  Write project-authored comments in Chinese and English. Explain constraints and design decisions rather than restating the code.
- 保留上游生成文件和许可证的原始声明，不改写法律文本。
  Preserve upstream notices in generated files and licenses; do not rewrite legal text.
- 测量循环与准备、保存及界面操作保持分离。改变计数或计时语义时，更新报告字段与测量说明。
  Keep measurement loops separate from preparation, persistence and UI work. Update report fields and measurement notes when counting or timing semantics change.
- 缺失或不支持的能力须明确记录，不替换成不同测试后沿用原标签。
  Record missing or unsupported capabilities explicitly. Do not substitute a different test while retaining the original label.

## 验证 / Validation

先完成相关构建和 Lint，再运行受影响的设备测试。用例只清理自己创建的记录和临时文件。性能优化需保留工作量校验，并检查编译后的测量循环。

Run the relevant builds and Lint checks, then the affected device tests. Tests must clean up only their own records and temporary files. Retain workload validation when optimizing kernels and inspect the compiled measurement loops.

签名材料、SDK 路径、代理配置、设备报告和构建输出均保留在本机。提交前检查 Git 暂存区；`.gitignore` 不会移除已经跟踪的文件。

Keep signing material, SDK paths, proxy settings, device reports and build outputs local. Review the Git index before committing; `.gitignore` does not remove files that are already tracked.
