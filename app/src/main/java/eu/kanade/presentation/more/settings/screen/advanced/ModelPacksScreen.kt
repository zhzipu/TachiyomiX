package eu.kanade.presentation.more.settings.screen.advanced

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.modelpack.ModelPack
import eu.kanade.tachiyomi.modelpack.ModelPackManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * AI 图像增强模型包（waifu2x / Real-CUGAN / Real-ESRGAN 等）的安装情况与卸载入口。
 *
 * 宿主不内置任何模型目录：这里只展示宿主实际扫描到的模型包，
 * 包内提供了哪些模型由模型包自己的 `assets/modelpack.json` 声明。
 */
class ModelPacksScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { ModelPacksScreenModel(context.applicationContext) }
        val state by model.state.collectAsState()

        when (val s = state) {
            ModelPacksScreenModel.State.Loading -> LoadingScreen()
            is ModelPacksScreenModel.State.Ready -> PreferenceScaffold(
                titleRes = MR.strings.pref_model_packs,
                onBackPressed = navigator::pop,
                itemsProvider = { getPreferences(s.installedPacks) },
            )
        }
    }

    @Composable
    private fun getPreferences(installedPacks: List<ModelPack>): List<Preference> {
        val installedItems: List<Preference.PreferenceItem<out Any, out Any>> = if (installedPacks.isEmpty()) {
            listOf(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_model_packs_none_installed)))
        } else {
            installedPacks.map { getInstalledPreference(it) }
        }

        // 宿主不内置模型目录：只列出实际安装的模型包，缺失的包不做任何占位
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_model_packs_installed),
                preferenceItems = installedItems +
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(MR.strings.pref_model_packs_install_hint),
                    ),
            ),
        )
    }

    @Composable
    private fun getInstalledPreference(pack: ModelPack): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val size = remember(pack) { Formatter.formatFileSize(context, pack.sizeBytes) }

        return Preference.PreferenceItem.TextPreference(
            title = pack.name,
            subtitle = stringResource(
                MR.strings.pref_model_packs_installed_summary,
                pack.id,
                pack.versionCode.toString(),
                size,
            ),
            widget = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // 先清掉解压出来的模型文件，再交给系统卸载模型包本体
                            withContext(Dispatchers.IO) {
                                ModelPackManager.deleteExtractedData(context, pack.id)
                            }
                            context.startUninstall(pack)
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.ext_uninstall))
                }
            },
        )
    }

    private fun Context.startUninstall(pack: ModelPack) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, "package:${pack.packageName}".toUri()))
        } catch (_: ActivityNotFoundException) {
            toast(MR.strings.pref_model_packs_uninstall_failed)
        }
    }
}

private class ModelPacksScreenModel(
    private val context: Context,
) : StateScreenModel<ModelPacksScreenModel.State>(State.Loading) {

    init {
        screenModelScope.launchIO {
            ModelPackManager.refresh(context)
            ModelPackManager.packs.collectLatest { packs ->
                mutableState.value = State.Ready(packs)
            }
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(val installedPacks: List<ModelPack>) : State
    }
}
