# 项目约定 / Project conventions

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
