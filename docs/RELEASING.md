# 发布流程 / Release process

每次完成开发并通过适用测试后，提交源码，发布带签名 APK 的 GitHub Release，同时更新本机配置的交付目录。只推送源码不算完成发版。使用同一签名密钥，以便覆盖安装并保留历史。

After development and applicable checks pass, commit the source, publish a GitHub Release containing the signed APK, and update the configured local delivery directory. A source push alone is not a release. Reuse the signing key so updates preserve installed data.

1. 更新 `versionCode` / `versionName`，完成构建、Lint、设备用例及界面检查，并编写中英双语更新说明。
   Update `versionCode` / `versionName`, complete builds, Lint, device and interface checks, and write bilingual release notes.
2. 执行 `assembleRelease`；已配置的交付目录会收到 `BenchBridge-release.apk`，构建步骤核对 SHA-256。用 SDK 的 `apksigner verify` 检查签名，保存已验收 APK 的 SHA-256。
   Run `assembleRelease`. The configured delivery directory receives `BenchBridge-release.apk` with SHA-256 verification. Check the signature using SDK `apksigner verify` and retain the validated APK hash.
3. 将最终提交推送到主分支，创建并推送对应 `vX.Y.Z` 标签。正式版本不移动标签、不替换原附件。
   Push the final commit to the default branch, then create and push its `vX.Y.Z` tag. Do not move published tags or replace their assets.
4. 从干净的对应提交运行发布工具，上传 APK 及校验文件。需要 Python 3.11+ 和已授权的 Git 凭据；也可通过环境变量提供 `GITHUB_TOKEN`。
   Run the publishing tool from the clean tagged commit. It uploads the APK and checksum file. Python 3.11+ and authorized Git credentials are required; `GITHUB_TOKEN` may be supplied through the environment instead.

```sh
python tools/publish-release.py --tag vX.Y.Z --notes-file release-notes.md --sha256 VERIFIED_APK_SHA256
```

更新说明可放在仓库外或不提交的 `.local` 中。代理可通过 `--proxy` 指定，不写入脚本。工具先创建草稿，验证 GitHub 返回的附件大小和摘要，再正式发布，并从匿名公开下载链接重新下载校验。已有同名附件必须与本机文件相同，否则停止。

Notes may live outside the repository or in ignored `.local`. Configure an optional proxy with `--proxy`, not in the script. The tool creates a draft, verifies the uploaded size and digest, publishes, then downloads from the anonymous public link and verifies again. An existing asset must match the local file or publication stops.

苹果端发版先检查对应提交的 Apple CI 中 iPhone/iPad 运行结果、跳过项和截图，再从该提交的成功构建取 `BenchBridge-iOS-unsigned.ipa`。通过可重复的 `--asset PATH` 一并上传 IPA、验证摘要等附件；工具为每份文件生成 SHA-256，全部核对后发布草稿。更新说明必须写明未签名、真机验证状态和未实现项目。不得把模拟器包当作真机 IPA，也不得把一次通过的旧提交产物冒充当前构建。

For Apple releases, inspect iPhone/iPad outcomes, skips and screenshots for the matching commit before taking `BenchBridge-iOS-unsigned.ipa` from a successful build. Repeat `--asset PATH` to include the IPA and verification summaries. Each file receives a SHA-256 companion and all assets are verified before the draft is published. Notes must state signing, device-validation and unimplemented-feature status. Never present simulator packages as device IPAs or older commit artifacts as current builds.

首页的固定下载链接指向 `releases/latest/download/BenchBridge-release.apk`。发布后核对该链接可直接下载，且下载文件与交付目录的 APK 摘要相同。失败时保留本地产物，报告未完成的步骤，不宣布交付成功。

The homepage links to `releases/latest/download/BenchBridge-release.apk`. After publishing, confirm that the link downloads directly and matches the APK in the delivery directory. On failure, retain local artifacts, report the unfinished step, and do not claim successful delivery.
