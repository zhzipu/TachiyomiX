package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.GITHUB_REPO
import eu.kanade.tachiyomi.data.updater.awaitWithRetry
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import exh.syDebugVersion
import kotlinx.coroutines.launch
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object AboutScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        var logoTapCount by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        val latestVersionMsg = stringResource(SYMR.strings.about_update_latest)
        val checkFailedMsg = stringResource(SYMR.strings.about_update_check_failed)

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_category_about),
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(
                contentPadding = contentPadding,
            ) {
                item {
                    LogoHeader(
                        onClick = {
                            logoTapCount++
                            when (logoTapCount) {
                                1 -> context.toast(SYMR.strings.about_logo_tap_1)
                                2 -> context.toast(SYMR.strings.about_logo_tap_2)
                                3 -> context.toast(SYMR.strings.about_logo_tap_3)
                                else -> {
                                    logoTapCount = 0
                                    navigator.push(WebViewScreen(EASTER_EGG_URL))
                                }
                            }
                        },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.version),
                        subtitle = getVersionName(withBuildDate = true),
                        onPreferenceClick = {
                            val deviceInfo = CrashLogUtil(context).getDebugInfo()
                            context.copyToClipboard("Debug information", deviceInfo)
                        },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(SYMR.strings.about_project_url),
                        subtitle = PROJECT_URL,
                        onPreferenceClick = { context.openInBrowser(PROJECT_URL) },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(SYMR.strings.about_check_update),
                        subtitle = getVersionName(withBuildDate = false),
                        onPreferenceClick = {
                            scope.launch {
                                val result = try {
                                    // 走带退避重试的版本：刚启动时内置代理/网络可能还没就绪（见 awaitWithRetry 注释）
                                    Injekt.get<GetApplicationRelease>().awaitWithRetry(
                                        GetApplicationRelease.Arguments(
                                            isPreviewBuildType,
                                            BuildConfig.COMMIT_COUNT.toInt(),
                                            BuildConfig.VERSION_NAME,
                                            GITHUB_REPO,
                                            syDebugVersion,
                                            forceCheck = true,
                                        ),
                                    )
                                } catch (e: Exception) {
                                    context.toast(checkFailedMsg)
                                    return@launch
                                }
                                when (result) {
                                    is GetApplicationRelease.Result.NewUpdate -> {
                                        navigator.push(
                                            NewUpdateScreen(
                                                versionName = result.release.version,
                                                changelogInfo = result.release.info,
                                                releaseLink = result.release.releaseLink,
                                                downloadLink = result.release.getDownloadLink(),
                                            ),
                                        )
                                    }
                                    GetApplicationRelease.Result.NoNewUpdate -> context.toast(latestVersionMsg)
                                    GetApplicationRelease.Result.OsTooOld -> {}
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    fun getVersionName(withBuildDate: Boolean): String {
        return when {
            BuildConfig.DEBUG -> {
                "Debug ${BuildConfig.COMMIT_SHA}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
            // SY -->
            isPreviewBuildType -> {
                "Preview r$syDebugVersion".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            // SY <--
            else -> {
                "Stable ${BuildConfig.VERSION_NAME}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
        }
    }

    internal fun getFormattedBuildTime(): String {
        return try {
            LocalDateTime.ofInstant(
                Instant.parse(BuildConfig.BUILD_TIME),
                ZoneId.systemDefault(),
            )
                .toDateTimestampString(
                    UiPreferences.dateFormat(
                        Injekt.get<UiPreferences>().dateFormat.get(),
                    ),
                )
        } catch (e: Exception) {
            BuildConfig.BUILD_TIME
        }
    }

    private const val EASTER_EGG_URL = "https://arcxingye.github.io/rr/a1.html"

    private const val PROJECT_URL = "https://github.com/zhzipu/TachiyomiX"
}
