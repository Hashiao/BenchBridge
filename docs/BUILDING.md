# 构建说明 / Build guide

## 开发环境 / Development environment

使用 README 中列出的 JDK、SDK、NDK 和 CMake 版本。在 Android Studio 中打开仓库根目录，由 SDK Manager 安装缺少的组件。命令行构建需设置 `JAVA_HOME` 和 `ANDROID_HOME`，或在 `local.properties` 中填写 `sdk.dir`。

Use the JDK, SDK, NDK and CMake versions listed in the README. Open the repository root in Android Studio and install missing components through SDK Manager. For command-line builds, set `JAVA_HOME` and `ANDROID_HOME`, or set `sdk.dir` in `local.properties`.

`local.properties`、Gradle 缓存和 IDE 配置不会提交。需要网络代理时，在 IDE 或用户级 Gradle 配置中设置，不修改仓库默认网络配置。

`local.properties`, Gradle caches and IDE settings are not committed. Configure any required proxy in the IDE or user-level Gradle settings, rather than changing the repository's default network configuration.

AGP 9 的着色器构建需要显式指定编译器。NDK 已包含 `glslc`；在本机 `local.properties` 中添加对应目录，例如：

AGP 9 requires an explicit shader compiler directory. The NDK includes `glslc`; add the matching directory to local `local.properties`, for example:

```properties
# Windows 示例 / Windows example
glslc.dir=C\:/Android/Sdk/ndk/28.2.13676358/shader-tools/windows-x86_64
```

Linux 和 macOS 使用对应的 `linux-x86_64` / `darwin-x86_64` 目录。着色器源码位于 `app/src/main/shaders`，构建时生成 SPIR-V 并打包到 APK；无需在手机上额外安装编译器。

On Linux and macOS, use the corresponding `linux-x86_64` / `darwin-x86_64` directory. Sources in `app/src/main/shaders` are compiled to SPIR-V and packaged in the APK. No additional compiler is installed on the phone.

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat`。可选的 `tools/build.ps1` 接受 SDK、JDK 路径和本地代理端口参数；默认构建 Debug 与 Release，并运行两种变体的 Lint。

Use `gradlew.bat` on Windows. The optional `tools/build.ps1` accepts SDK and JDK paths and a local proxy port. Its default tasks build Debug and Release and run Lint for both variants.

## Release 签名 / Release signing

仓库不提供签名密钥。没有本机签名配置时，`assembleRelease` 生成未签名 APK。可在 Android Studio 的签名向导中选择自己的密钥，或创建以下本机文件：

The repository does not include a signing key. Without local signing configuration, `assembleRelease` produces an unsigned APK. Use Android Studio's signing wizard with your own key, or create the following local files:

- `.local/release-signing.p12`
- `.local/release-signing.properties`

属性文件示例 / Properties file example:

```properties
storeFile=.local/release-signing.p12
storePassword=YOUR_LOCAL_PASSWORD
keyAlias=benchbridge-release
keyPassword=YOUR_LOCAL_PASSWORD
```

Windows 的 `tools/prepare-release-signing.ps1` 可生成本机签名材料；已有完整材料时保留，发现不完整文件对时停止。请自行备份密钥；使用不同密钥构建的 APK 不能覆盖安装到原签名的应用上。

On Windows, `tools/prepare-release-signing.ps1` can generate local signing material. It preserves an existing complete pair and stops if the pair is incomplete. Back up your key; an APK signed with a different key cannot update an existing installation.

```sh
./gradlew :app:assembleRelease
```

输出位于 `app/build/outputs/apk/release/`。

Output is written to `app/build/outputs/apk/release/`.

## 可选交付目录 / Optional delivery directory

`assembleRelease` 可在成功组装后复制已签名 APK。通过 `-PreleaseDropDir=<absolute-directory>` 指定目录，或将配置写入不提交的 `.local/build.properties`：

After successful assembly, `assembleRelease` can copy the signed APK to a delivery directory. Set `-PreleaseDropDir=<absolute-directory>`, or place the setting in the ignored `.local/build.properties` file:

```properties
releaseDropDir=/absolute/path/to/apk-delivery
```

未配置时不执行复制。启用后先复制到临时文件并校验 SHA-256，再更新 `BenchBridge-release.apk`；目录不可访问或校验失败会使交付步骤报错，本地构建产物仍保留。

No copy is performed without this setting. When enabled, the task copies to a temporary file and verifies SHA-256 before updating `BenchBridge-release.apk`. An inaccessible directory or failed verification causes the delivery step to fail while retaining the local build artifact.

## 生成文件 / Generated files

保留 Gradle Wrapper 的脚本和 JAR；排除 APK、签名文件、`.local`、`.gradle`、`.idea`、`.cxx` 和构建目录。仓库不依赖维护者的磁盘映射或共享登录状态。

Keep the Gradle Wrapper scripts and JAR in version control. Exclude APKs, signing files, `.local`, `.gradle`, `.idea`, `.cxx` and build directories. The repository does not depend on the maintainer's drive mappings or authenticated network shares.
