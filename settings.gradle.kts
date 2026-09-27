pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mihonx") {
            from(files("gradle/mihon.versions.toml"))
        }
        create("sylibs") {
            from(files("gradle/sy.versions.toml"))
        }
    }

    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "TachiyomiSY"
include(":app")
include(":baseline-profile")
include(":core-metadata")
include(":core:common")
include(":data")
include(":domain")
include(":i18n")
// SY -->
include(":i18n-sy")
// SY <--
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
// SY -->
include(":source-network")
// SY <--
// 模型包为独立分发的插件，源码在各 TachiyomiX-ModelPack-* 仓库（见 README「外置模型包」）；
// 未克隆到本地时跳过该模块，保证主工程可单独构建
if (file("model-packs").exists()) {
    include(":model-packs")
}
