import java.util.Properties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.benchbridge.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.benchbridge.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 17
        versionName = "0.12.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        shaders { glslcArgs += listOf("-O", "--target-env=vulkan1.0") }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    // 命令行构建工具将调试密钥保存在项目的私有目录中。
    // The command-line build helper keeps the debug key in the project's private directory.
    val workspaceDebugKey = rootProject.file(".local/debug.keystore")
    if (workspaceDebugKey.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = workspaceDebugKey
        }
    }

    // Release 签名材料仅保存在本机；Debug 使用独立包名和密钥。
    // Release signing material stays local; Debug uses a separate application ID and key.
    val releaseSigningFile = rootProject.file(".local/release-signing.properties")
    if (releaseSigningFile.isFile) {
        val releaseProperties = Properties().apply {
            releaseSigningFile.inputStream().use { load(it) }
        }
        val localRelease = signingConfigs.create("localRelease") {
            storeFile = rootProject.file(requireNotNull(releaseProperties.getProperty("storeFile")))
            storePassword = requireNotNull(releaseProperties.getProperty("storePassword"))
            keyAlias = requireNotNull(releaseProperties.getProperty("keyAlias"))
            keyPassword = requireNotNull(releaseProperties.getProperty("keyPassword"))
            storeType = "PKCS12"
        }
        buildTypes.getByName("release").signingConfig = localRelease
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
        shaders = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// 交付目录为可选本机配置。其他贡献者构建时无需访问维护者的共享目录。
// The delivery directory is optional local configuration, not a contributor build requirement.
val localBuildProperties = Properties().apply {
    rootProject.file(".local/build.properties").takeIf { it.isFile }?.inputStream()?.use { load(it.reader(Charsets.UTF_8)) }
}
val releaseDropDirectory = providers.gradleProperty("releaseDropDir")
    .orElse(localBuildProperties.getProperty("releaseDropDir", ""))
val assembledReleaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
val releaseHasSigning = android.buildTypes.getByName("release").signingConfig != null
tasks.matching { it.name == "assembleRelease" }.configureEach {
    // 同次构建包含 Lint 时，检查通过后再执行交付。
    // When Lint is requested in the same build, finish it before delivery.
    mustRunAfter("lintDebug", "lintRelease")
    doLast {
        // 仅在组装成功后复制；构建失败时保留上一份交付文件。
        // Copy only after successful assembly; keep the previous delivery if the build fails.
        if (releaseDropDirectory.get().isBlank()) return@doLast
        check(releaseHasSigning) { "Release signing is required before copying to the delivery folder." }
        val source = assembledReleaseApk.get().asFile
        check(source.isFile) { "Signed Release APK not found: $source" }
        val directory = File(releaseDropDirectory.get())
        check(directory.isAbsolute) { "releaseDropDir must be an absolute path." }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot access Release delivery folder: $directory" }
        val destination = directory.resolve("BenchBridge-release.apk").toPath()
        val staging = Files.createTempFile(directory.toPath(), ".benchbridge-", ".part")
        fun sha256(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }
        try {
            Files.copy(source.toPath(), staging, StandardCopyOption.REPLACE_EXISTING)
            val expected = sha256(source)
            check(MessageDigest.isEqual(expected, sha256(staging.toFile()))) { "Release copy SHA-256 mismatch." }
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            check(MessageDigest.isEqual(expected, sha256(destination.toFile()))) { "Delivered Release SHA-256 mismatch." }
            logger.lifecycle("Release delivered: $destination (SHA-256 verified)")
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
