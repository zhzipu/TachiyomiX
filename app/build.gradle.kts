
import mihon.gradle.getBuildTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.tasks.ReplaceShortcutsPlaceholderTask
import java.util.Properties

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    kotlin("plugin.parcelize")

    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)

    id("com.github.ben-manes.versions")
}

// ncnn Android Vulkan SDK：优先取 Gradle 属性 / local.properties / 环境变量，其次用仓库内自带的
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val bundledNcnnSdkDir = rootProject.file("third_party/ncnn-20260113-android-vulkan")

val ncnnSdkDir = providers.gradleProperty("ncnnSdkDir").orNull
    ?: localProperties.getProperty("ncnn.sdk.dir")
    ?: System.getenv("NCNN_SDK_DIR")
    ?: bundledNcnnSdkDir.takeIf { it.exists() }?.absolutePath

// Qualcomm AI Runtime（QNN）SDK：用于空间深度模型等 NPU 通路，未配置时原生侧以 MIHON_ENABLE_QNN=0 编译
val qnnSdkDir = providers.gradleProperty("qnnSdkDir").orNull
    ?: localProperties.getProperty("qnn.sdk.dir")
    ?: System.getenv("QNN_SDK_ROOT")

val qnnSdkRoot = qnnSdkDir?.takeIf { it.isNotBlank() }?.let(rootProject::file)

// HTP 架构版本，多值用逗号分隔；CMake 取第一个作为 QNN_HTP_ARCH
val qnnHtpArchs = (
    providers.gradleProperty("qnnHtpArchs").orNull
        ?: localProperties.getProperty("qnn.htp.archs")
        ?: "73"
    ).split(',').map(String::trim).filter(String::isNotEmpty).distinct()

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "com.tachiyomi.x"

        versionCode = 102
        versionName = "1.0.2"

        buildConfigField("String", "UPSTREAM_VERSION", """"0.20.1"""")

        buildConfigField("String", "COMMIT_COUNT", "\"${getLatestCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getLatestCommitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
        buildConfigField("boolean", "INCLUDE_UPDATER", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                if (!ncnnSdkDir.isNullOrBlank()) {
                    arguments += "-DNCNN_SDK_DIR=$ncnnSdkDir"
                }
                qnnSdkRoot?.let {
                    arguments += "-DQNN_SDK_DIR=${it.absolutePath}"
                    arguments += "-DQNN_HTP_ARCH=${qnnHtpArchs.first()}"
                }
            }
        }
    }

    buildTypes {
        named("release") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            isProfileable = true
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
            buildConfigField("boolean", "INCLUDE_UPDATER", "false")
        }
        create("foss") {
            initWith(getByName("release"))

            applicationIdSuffix = ".foss"

            matchingFallbacks.add("release")

            buildConfigField("boolean", "INCLUDE_UPDATER", "false")
        }
        create("benchmark") {
            initWith(getByName("release"))

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks.add("release")
            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            buildConfigField("boolean", "INCLUDE_UPDATER", "false")
        }
    }

    sourceSets {
        getByName("release").java.directories.add("src/release/java")
        getByName("foss").java.directories.add("src/foss/java")
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // ncnn / JNI 原生库使用传统打包方式，避免解压后 mmap 失败
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.1.10909125"

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.baselineProfile)

    implementation(projects.i18n)
    // SY -->
    implementation(projects.i18nSy)
    // SY <--
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    // SY -->
    implementation(projects.sourceNetwork)
    // SY <--
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialIcons)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.sqlite.bundled)
    // SY -->
    implementation(sylibs.sqlcipher)
    // SY <--

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.sqldelight.async)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Pinyin (汉字→拼音) for index-bar first-letter sorting
    implementation("com.belerweb:pinyin4j:2.5.1")

    // Disk
    implementation(libs.diskLruCache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // Crash reports/analytics
//    "standardImplementation"(platform(libs.firebase.bom))
//    "standardImplementation"(libs.firebase.analytics)
//    "standardImplementation"(libs.firebase.crashlytics)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)

    // SY -->
    // Better logging (EH)
    implementation(sylibs.xlog)

    // RatingBar (SY)
    implementation(sylibs.ratingbar)
    implementation(sylibs.composeRatingbar)

    // Google drive
    implementation(sylibs.google.api.services.drive)
    implementation(sylibs.google.api.client.oauth)

    // Koin
    implementation(sylibs.koin.core)
    implementation(sylibs.koin.android)

    // ZXing Android Embedded
    implementation(sylibs.zxing.android.embedded)

    // secp256k1 signing for Aliyun Pan web API session signature
    implementation(sylibs.bcprov)

    // SY -->
    // Built-in Clash (mihomo) proxy core
    implementation(files("libs/libmihomo-android-v0.3.1.aar"))
    // SY <--
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType.name == "ABI" }?.identifier ?: "universal"
            output.outputFileName.set("TachiyomiX-${android.defaultConfig.versionName}-$abi.apk")
        }

        val resSource = variant.sources.res ?: return@onVariants

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val replaceShortcutsPlaceholderTask = tasks.register<ReplaceShortcutsPlaceholderTask>(
            "replace${variantName}ShortcutPlaceholder",
        ) {
            applicationId.set(variant.applicationId)
            shortcutsFile.set(projectDir.resolve("src/main/shortcuts.xml"))
        }
        resSource.addGeneratedSourceDirectory(replaceShortcutsPlaceholderTask) { it.outputDir }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
