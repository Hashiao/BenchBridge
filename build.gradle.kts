// AGP 9 内置 Kotlin；其运行时依赖与 Compose 编译器保持同一版本。
// AGP 9 includes Kotlin; keep its runtime dependency and the Compose compiler on the same version.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
