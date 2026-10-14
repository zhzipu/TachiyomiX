package eu.kanade.presentation.more.settings.screen.advanced

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.modelpack.CatalogModelPack
import eu.kanade.tachiyomi.modelpack.ModelPack
import eu.kanade.tachiyomi.modelpack.ModelPackCatalogService
import eu.kanade.tachiyomi.modelpack.ModelPackInstallException
import eu.kanade.tachiyomi.modelpack.ModelPackInstaller
import eu.kanade.tachiyomi.modelpack.ModelPackManager
import eu.kanade.tachiyomi.modelpack.appendEnhanceDebugLog
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 图像增强模型包（waifu2x / Real-CUGAN / Real-ESRGAN / SPAN 等）的管理页。
 *
 * 上半部分读**远端插件目录**（`TachiyomiX-ModelPack-Catalog` 的 `index.min.json`）：
 * 能看出哪个插件没装、哪个装了但有新版本，并可一键下载 + 交给系统安装器安装/更新；
 * 下半部分列出宿主实际扫描到的已安装模型包（含卸载）。
 *
 * 安装/更新失败时**会明确区分阶段**（下载 / 校验 / 装包）：页内显示一行原因，同时弹一条带原因的
 * toast —— 只写页内小字或只写 logcat 都不够，用户会以为「什么都没发生」。
 *
 * 包内提供了哪些模型仍由模型包自己的 `assets/modelpack.json` 声明，宿主不内置任何模型目录。
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
                itemsProvider = { getPreferences(s, model) },
            )
        }
    }

    @Composable
    private fun getPreferences(
        state: ModelPacksScreenModel.State.Ready,
        model: ModelPacksScreenModel,
    ): List<Preference> {
        val installedById = remember(state.installedPacks) { state.installedPacks.associateBy { it.id } }
        val remoteById = remember(state.remotePacks) { state.remotePacks.associateBy { it.id } }

        // 「可更新」= 仓库里的 versionCode 比装着的那个大；「可安装」= 压根没装
        val updatable = state.remotePacks.filter { remote ->
            installedById[remote.id]?.let { remote.code > it.versionCode } == true
        }
        val installable = state.remotePacks.filter { installedById[it.id] == null }

        return buildList {
            add(getRepoGroup(state, model))
            if (updatable.isNotEmpty()) {
                add(getRemoteGroup(MR.strings.pref_model_packs_updates, updatable, installedById, state, model))
            }
            if (installable.isNotEmpty()) {
                add(getRemoteGroup(MR.strings.pref_model_packs_available, installable, installedById, state, model))
            }
            add(getInstalledGroup(state, remoteById))
        }
    }

    /** 插件目录：读取状态 + 「检查更新」+ 失败/出错时的排错提示。 */
    @Composable
    private fun getRepoGroup(
        state: ModelPacksScreenModel.State.Ready,
        model: ModelPacksScreenModel,
    ): Preference.PreferenceGroup {
        val catalog = state.catalog
        val statusText = when (catalog) {
            ModelPacksScreenModel.CatalogState.Never -> stringResource(MR.strings.pref_model_packs_repo_never)
            ModelPacksScreenModel.CatalogState.Loading -> stringResource(MR.strings.pref_model_packs_repo_checking)
            is ModelPacksScreenModel.CatalogState.Loaded -> {
                val time = remember(catalog.fetchedAt) {
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(catalog.fetchedAt))
                }
                stringResource(MR.strings.pref_model_packs_repo_updated, state.remotePacks.size, time)
            }
            is ModelPacksScreenModel.CatalogState.Failed -> {
                stringResource(MR.strings.pref_model_packs_repo_failed, catalog.message)
            }
        }
        val loading = catalog is ModelPacksScreenModel.CatalogState.Loading

        val items = mutableListOf<Preference.PreferenceItem<out Any, out Any>>(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_model_packs_repo_check),
                subtitle = ModelPackCatalogService.REPO_NAME + " · " + statusText,
                enabled = !loading,
                onClick = { model.refreshCatalog() },
                widget = if (loading) {
                    { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            ),
            Preference.PreferenceItem.InfoPreference(ModelPackCatalogService.REPO_URL),
        )

        if (catalog is ModelPacksScreenModel.CatalogState.Failed) {
            items += Preference.PreferenceItem.InfoPreference(
                stringResource(MR.strings.pref_model_packs_repo_hint),
            )
        }
        if (catalog is ModelPacksScreenModel.CatalogState.Loaded && state.remotePacks.isEmpty()) {
            items += Preference.PreferenceItem.InfoPreference(
                stringResource(MR.strings.pref_model_packs_repo_empty),
            )
        }
        state.installError?.let { error ->
            items += Preference.PreferenceItem.InfoPreference(error.message)
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_model_packs_repo),
            preferenceItems = items,
        )
    }

    /** 远端目录里的插件（可更新 / 可安装共用）。 */
    @Composable
    private fun getRemoteGroup(
        titleRes: StringResource,
        packs: List<CatalogModelPack>,
        installedById: Map<String, ModelPack>,
        state: ModelPacksScreenModel.State.Ready,
        model: ModelPacksScreenModel,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val sizeUnknown = stringResource(MR.strings.pref_model_packs_size_unknown)

        return Preference.PreferenceGroup(
            title = stringResource(titleRes),
            preferenceItems = packs.map { pack ->
                val installed = installedById[pack.id]
                val size = pack.size?.let { Formatter.formatFileSize(context, it) } ?: sizeUnknown
                val busy = pack.id in state.busyIds
                val subtitle = when {
                    // 下载/安装进行中：直接把状态写在行里，别让用户只看到一个转圈
                    busy -> stringResource(MR.strings.pref_model_packs_downloading)
                    installed != null -> stringResource(
                        MR.strings.pref_model_packs_update_summary,
                        installed.versionCode.toString(),
                        pack.version,
                        size,
                    )
                    else -> stringResource(MR.strings.pref_model_packs_available_summary, pack.version, size)
                }

                Preference.PreferenceItem.TextPreference(
                    title = pack.name.ifBlank { pack.id },
                    subtitle = subtitle,
                    enabled = !busy,
                    onClick = { model.install(pack) },
                    widget = {
                        // 已装的包在「可更新 / 可安装」这一组里也直接给卸载入口，不用滚到下面那组去
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (installed != null) {
                                UninstallButton(pack = installed, enabled = !busy)
                            }
                            TextButton(onClick = { model.install(pack) }, enabled = !busy) {
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(
                                        text = stringResource(
                                            if (installed != null) MR.strings.ext_update else MR.strings.ext_install,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                )
            },
        )
    }

    /** 已安装的模型包（宿主实际扫描到的），带卸载。 */
    @Composable
    private fun getInstalledGroup(
        state: ModelPacksScreenModel.State.Ready,
        remoteById: Map<String, CatalogModelPack>,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current

        val items: List<Preference.PreferenceItem<out Any, out Any>> = if (state.installedPacks.isEmpty()) {
            listOf(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_model_packs_none_installed)))
        } else {
            state.installedPacks.map { pack ->
                val size = remember(pack) { Formatter.formatFileSize(context, pack.sizeBytes) }
                val summary = stringResource(
                    MR.strings.pref_model_packs_installed_summary,
                    pack.id,
                    pack.versionCode.toString(),
                    size,
                )
                val remote = remoteById[pack.id]
                val updateNote = if (remote != null && remote.code > pack.versionCode) {
                    stringResource(
                        MR.strings.pref_model_packs_update_available,
                        remote.version,
                        remote.code.toString(),
                    )
                } else {
                    null
                }
                Preference.PreferenceItem.TextPreference(
                    title = pack.name,
                    subtitle = if (updateNote == null) summary else "$summary\n$updateNote",
                    widget = { UninstallButton(pack = pack, enabled = true) },
                )
            }
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_model_packs_installed),
            preferenceItems = items,
        )
    }

    /**
     * 卸载按钮：先把宿主解压出来的模型文件删掉，再把模型包本体交给系统卸载器。
     * 「已安装」组与「可更新 / 可安装」组共用。
     */
    @Composable
    private fun UninstallButton(pack: ModelPack, enabled: Boolean) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        TextButton(
            enabled = enabled,
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ModelPackManager.deleteExtractedData(context, pack.id)
                    }
                    context.startUninstall(pack)
                }
            },
        ) {
            Text(text = stringResource(MR.strings.ext_uninstall))
        }
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

    private val catalog = ModelPackCatalogService()
    private val installer = ModelPackInstaller(context)

    private var installed: List<ModelPack> = emptyList()
    private var remote: List<CatalogModelPack> = emptyList()
    private var catalogState: CatalogState = CatalogState.Never
    private var busyIds: Set<String> = emptySet()
    private var installError: InstallError? = null

    init {
        screenModelScope.launchIO {
            ModelPackManager.refresh(context)
            // 安装/卸载模型包时 ExtensionInstallReceiver 会再触发 refresh，这里的订阅会跟着刷新
            ModelPackManager.packs.collectLatest { packs ->
                installed = packs
                publish()
            }
        }
        // 进页面就自动读一次目录（失败也不影响下半部分的已安装列表）
        refreshCatalog()
    }

    /** 重新读取远端插件目录。 */
    fun refreshCatalog() {
        if (catalogState is CatalogState.Loading) return
        catalogState = CatalogState.Loading
        installError = null
        publish()

        screenModelScope.launchIO {
            catalog.fetch()
                .onSuccess {
                    remote = it
                    catalogState = CatalogState.Loaded(System.currentTimeMillis())
                    appendEnhanceDebugLog(context, "catalog: 读到 ${it.size} 个插件")
                }
                .onFailure {
                    catalogState = CatalogState.Failed(it.message ?: it::class.java.simpleName)
                    appendEnhanceDebugLog(context, "catalog: 读取失败 ${it.message}")
                }
            publish()
        }
    }

    /** 下载并交给系统安装器安装/更新。 */
    fun install(pack: CatalogModelPack) {
        if (pack.id in busyIds) return
        busyIds = busyIds + pack.id
        installError = null
        publish()
        appendEnhanceDebugLog(context, "modelpack: ${pack.id} 用户点击安装 version=${pack.version}")

        screenModelScope.launch {
            val result = installer.downloadAndInstall(pack)
            busyIds = busyIds - pack.id
            result
                .onSuccess {
                    // 时长给 LONG：安装器起来后用户还要确认，提示别太快消失
                    context.toast(MR.strings.pref_model_packs_install_started, Toast.LENGTH_LONG)
                }
                .onFailure { error ->
                    val stage = (error as? ModelPackInstallException)?.stage
                        ?: ModelPackInstallException.Stage.INSTALL
                    val label = pack.name.ifBlank { pack.id }
                    val detail = error.message ?: error::class.java.simpleName
                    val message = stageMessage(stage, label, detail)
                    installError = InstallError(stage = stage, message = message)
                    // 关键：失败的**原因**要弹出来，否则用户只会看到转圈结束、什么都没发生
                    context.toast(message, Toast.LENGTH_LONG)
                }
            publish()
        }
    }

    private fun publish() {
        mutableState.value = State.Ready(
            installedPacks = installed,
            remotePacks = remote,
            catalog = catalogState,
            busyIds = busyIds,
            installError = installError,
        )
    }

    private fun stageMessage(stage: ModelPackInstallException.Stage, label: String, detail: String): String =
        context.contextStringResource(
            when (stage) {
                ModelPackInstallException.Stage.DOWNLOAD -> MR.strings.pref_model_packs_error_download
                ModelPackInstallException.Stage.VERIFY -> MR.strings.pref_model_packs_error_verify
                ModelPackInstallException.Stage.INSTALL -> MR.strings.pref_model_packs_install_failed
            },
            label,
            detail,
        )

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val installedPacks: List<ModelPack>,
            val remotePacks: List<CatalogModelPack>,
            val catalog: CatalogState,
            val busyIds: Set<String>,
            val installError: InstallError?,
        ) : State
    }

    @Immutable
    sealed interface CatalogState {
        /** 还没读过（本次进页面会自动读，理论上一闪而过）。 */
        @Immutable
        data object Never : CatalogState

        @Immutable
        data object Loading : CatalogState

        @Immutable
        data class Loaded(val fetchedAt: Long) : CatalogState

        @Immutable
        data class Failed(val message: String) : CatalogState
    }

    /** 安装失败：带阶段与**已本地化**的整句文案，页内与 toast 共用。 */
    @Immutable
    data class InstallError(
        val stage: ModelPackInstallException.Stage,
        val message: String,
    )
}
