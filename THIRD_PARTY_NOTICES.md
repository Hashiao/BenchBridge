# 第三方组件 / Third-party components

BenchBridge 的自有代码采用 MIT 许可证。下列工具和依赖保留各自的许可证。

BenchBridge's project-authored code is licensed under MIT. The following tools and dependencies retain their own licenses.

| 组件 / Component | 用途 / Use | 许可 / License |
|---|---|---|
| [Gradle Wrapper](https://github.com/gradle/gradle) | 构建启动器 / Build launcher | Apache-2.0 |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support/) | Compose、Activity、Lifecycle 与测试组件 / Compose, Activity, Lifecycle and test components | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) | 语言工具链与运行库 / Language tooling and runtime | Apache-2.0 |

仓库保留 Gradle Wrapper 的上游版权与许可证声明。Android SDK / NDK 由开发者单独安装，依赖通过构建工具解析，不将 SDK 或 NDK 分发到源码仓库。分发自行构建的二进制文件时，还应保留所包含依赖和 C++ 运行库要求的声明。

The repository retains the Gradle Wrapper's upstream copyright and license notices. Developers install the Android SDK / NDK separately, and the build resolves dependencies; SDK and NDK distributions are not included in the source repository. When distributing compiled binaries, retain the notices required by bundled dependencies and the C++ runtime.

[AIDA64](https://www.aida64.com/) 和 [CrystalDiskMark](https://crystalmark.info/en/software/crystaldiskmark/) 的名称仅用于说明测量项目和参数参考。BenchBridge 不包含这两个产品的可执行文件。

[AIDA64](https://www.aida64.com/) and [CrystalDiskMark](https://crystalmark.info/en/software/crystaldiskmark/) are named as references for test categories and parameters. BenchBridge does not include either product's executables.
