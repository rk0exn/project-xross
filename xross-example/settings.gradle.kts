rootProject.name = "xross-example"
include("app")

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

// ローカルのプラグインプロジェクトを包含する
includeBuild("../xross-plugin")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

plugins {
    // JDKの自動ダウンロード設定
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
