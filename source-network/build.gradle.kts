plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    // SY --> 漫画文件夹里的 config.json 用 kotlinx-serialization 解析
    alias(libs.plugins.kotlin.serialization)
    // SY <--
}

android {
    namespace = "tachiyomi.source.network"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.core.common)

    // ChapterRecognition（章节号解析），与本地图源一致
    implementation(projects.domain)

    // SY -->
    implementation(projects.i18n)
    implementation(projects.i18nSy)
    // SY <--

    implementation(libs.bundles.kotlinx.coroutines)

    // WebDAV 走 OkHttp（PROPFIND + GET），XML 响应交给 jsoup 的 xmlParser 解析
    implementation(libs.okhttp.core)
    implementation(libs.okio)
    implementation(libs.jsoup)

    // FTP 客户端。自己拿 Socket 写一套 PASV/EPSV + LIST 解析太容易出错，
    // 而且没法在没有 FTP 服务器的环境下自测，所以用成熟实现。
    // 它只依赖 JDK 的 Socket / SSL，Android 上可直接用。
    implementation(libs.commons.net)

    // 图源设置页用 androidx.preference
    implementation(libs.androidx.preference)

    // SY -->
    // 解析漫画文件夹里的 config.json
    implementation(libs.kotlinx.serialization.json)
    // 读章节压缩包（zip / cbz / rar / 7z），与本地图源的 CBZ 读取用的是同一个库
    implementation(libs.archive)
    // SY <--
}
