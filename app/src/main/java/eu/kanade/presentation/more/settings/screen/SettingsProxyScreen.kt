package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.clash.ClashManager
import eu.kanade.tachiyomi.network.ClashPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProxyScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 并发测速上限。原来是一个节点接一个节点地测，节点多时（每个最多等 5s 超时）要等几分钟；
 * 现在全部并发发起，只用这个上限兜住极端情况（订阅里有几百个节点时别一次开几百条连接）。
 */
private const val TEST_DELAY_CONCURRENCY = 64

/** 延迟由低到高的配色：绿 → 黄 → 红。 */
private val DELAY_FAST_COLOR = Color(0xFF4CAF50)
private val DELAY_OK_COLOR = Color(0xFFF9A825)
private val DELAY_SLOW_COLOR = Color(0xFFE53935)

/** 绿/黄分界与黄/红分界（毫秒）。 */
private const val DELAY_FAST_MS = 200
private const val DELAY_OK_MS = 600

object SettingsProxyScreen : SearchableSettings {

    private suspend fun evictConnections(networkHelper: NetworkHelper) {
        withContext(Dispatchers.IO) {
            networkHelper.client.connectionPool.evictAll()
        }
    }

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = SYMR.strings.pref_category_proxy

    @Composable
    override fun getPreferences(): List<Preference> {
        val clashPreferences = remember { Injekt.get<ClashPreferences>() }
        val clashManager = remember { Injekt.get<ClashManager>() }
        val networkHelper = remember { Injekt.get<NetworkHelper>() }
        return listOf(
            getHttpProxyGroup(clashPreferences, clashManager, networkHelper),
            getClashProxyGroup(clashPreferences, clashManager, networkHelper),
            getProxyScopeGroup(clashPreferences),
        )
    }

    @Composable
    private fun getHttpProxyGroup(
        clashPreferences: ClashPreferences,
        clashManager: ClashManager,
        networkHelper: NetworkHelper,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(SYMR.strings.proxy_http_group),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.httpProxyEnabled,
                    title = stringResource(SYMR.strings.proxy_http_enable),
                    subtitle = stringResource(SYMR.strings.proxy_http_enable_summary),
                    onValueChanged = { newValue ->
                        if (newValue) {
                            // HTTP proxy takes precedence: disable Clash proxy.
                            clashPreferences.enabled.set(false)
                            clashManager.stop()
                        }
                        evictConnections(networkHelper)
                        true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = clashPreferences.httpProxyHost,
                    title = stringResource(SYMR.strings.proxy_http_host),
                    subtitle = "%s",
                    onValueChanged = {
                        evictConnections(networkHelper)
                        true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = clashPreferences.httpProxyPort,
                    title = stringResource(SYMR.strings.proxy_http_port),
                    subtitle = "%s",
                    onValueChanged = {
                        evictConnections(networkHelper)
                        true
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getClashProxyGroup(
        clashPreferences: ClashPreferences,
        clashManager: ClashManager,
        networkHelper: NetworkHelper,
    ): Preference.PreferenceGroup {
        val enabled by clashPreferences.enabled.collectAsState()
        val selectedNode by clashPreferences.selectedProxy.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(SYMR.strings.proxy_clash_group),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.enabled,
                    title = stringResource(SYMR.strings.proxy_enable),
                    subtitle = stringResource(SYMR.strings.proxy_enable_summary),
                    onValueChanged = { newValue ->
                        if (newValue) {
                            // Clash proxy takes precedence: disable HTTP proxy.
                            clashPreferences.httpProxyEnabled.set(false)
                            clashManager.restart()
                        } else {
                            clashManager.stop()
                        }
                        evictConnections(networkHelper)
                        true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = clashPreferences.subscriptionUrl,
                    title = stringResource(SYMR.strings.proxy_subscription_url),
                    subtitle = "%s",
                    onValueChanged = { true },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = clashPreferences.manualConfig,
                    title = stringResource(SYMR.strings.proxy_manual_config),
                    subtitle = stringResource(SYMR.strings.proxy_manual_config_summary),
                    onValueChanged = { true },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = clashPreferences.httpPort,
                    title = stringResource(SYMR.strings.proxy_port),
                    subtitle = "%s",
                    onValueChanged = { true },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = clashPreferences.testUrl,
                    title = stringResource(SYMR.strings.proxy_test_url),
                    subtitle = "%s",
                    onValueChanged = { true },
                ),
                kotlin.run {
                    var open by remember { mutableStateOf(false) }
                    if (open) {
                        ProxyNodesDialog(
                            clashManager = clashManager,
                            onDismissRequest = { open = false },
                        )
                    }
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(SYMR.strings.proxy_node_selection),
                        subtitle = selectedNode.ifBlank { stringResource(SYMR.strings.proxy_node_not_selected) },
                        enabled = enabled,
                        onClick = { open = true },
                    )
                },
            ),
        )
    }

    @Composable
    private fun getProxyScopeGroup(
        clashPreferences: ClashPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(SYMR.strings.pref_proxy_scope),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.ONLINE_READING),
                    title = stringResource(SYMR.strings.pref_proxy_scope_online_reading),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.MANGA_DOWNLOAD),
                    title = stringResource(SYMR.strings.pref_proxy_scope_manga_download),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.MANGA_UPLOAD),
                    title = stringResource(SYMR.strings.pref_proxy_scope_manga_upload),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.EXTENSION_REPO),
                    title = stringResource(SYMR.strings.pref_proxy_scope_extension_repo),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.EXTENSION_DOWNLOAD),
                    title = stringResource(SYMR.strings.pref_proxy_scope_extension_download),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.SYNC),
                    title = stringResource(SYMR.strings.pref_proxy_scope_sync),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.WEBDAV),
                    title = stringResource(SYMR.strings.pref_proxy_scope_webdav),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.VERSION_CHECK),
                    title = stringResource(SYMR.strings.pref_proxy_scope_version_check),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = clashPreferences.proxyScope(ProxyScope.UPDATE_DOWNLOAD),
                    title = stringResource(SYMR.strings.pref_proxy_scope_update_download),
                ),
            ),
        )
    }
}

@Composable
private fun ProxyNodesDialog(
    clashManager: ClashManager,
    onDismissRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var delays by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var testing by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val group = clashManager.getProxyGroup()
        if (group == null) {
            loadError = true
        } else {
            nodes = group.nodes
            selected = group.selected
        }
        loading = false
    }

    fun refresh() {
        refreshing = true
        loadError = false
        scope.launch {
            clashManager.refreshProviders()
            val group = clashManager.getProxyGroup()
            if (group == null) {
                nodes = emptyList()
                loadError = true
            } else {
                nodes = group.nodes
                selected = group.selected
            }
            delays = emptyMap()
            refreshing = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(SYMR.strings.proxy_node_selection)) },
        text = {
            Column {
                TextButton(
                    enabled = !refreshing,
                    onClick = { refresh() },
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text(text = stringResource(SYMR.strings.proxy_refresh_nodes))
                }
                when {
                    loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    loadError -> {
                        Text(text = stringResource(SYMR.strings.proxy_load_failed))
                    }

                    nodes.isEmpty() -> {
                        Text(text = stringResource(SYMR.strings.proxy_no_nodes))
                    }

                    else -> {
                        Column {
                            TextButton(
                                enabled = !testing,
                                onClick = {
                                    testing = true
                                    scope.launch {
                                        // 并发测速：原先 nodes.forEach 串行，一个节点最多要等 5s 超时，
                                        // 订阅里几十个节点就是好几分钟。现在一起发起，谁先回来谁先显示结果。
                                        val gate = Semaphore(TEST_DELAY_CONCURRENCY)
                                        try {
                                            coroutineScope {
                                                nodes.forEach { node ->
                                                    launch {
                                                        val result = runCatching {
                                                            gate.withPermit { clashManager.testDelay(node) }
                                                        }.getOrNull()
                                                        delays = delays + (node to result)
                                                    }
                                                }
                                            }
                                        } finally {
                                            testing = false
                                        }
                                    }
                                },
                            ) {
                                if (testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.size(8.dp))
                                }
                                Text(text = stringResource(SYMR.strings.proxy_test_delay))
                            }
                            LazyColumn {
                                items(nodes) { node ->
                                    val isSelected = node == selected
                                    val delay = delays[node]
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch {
                                                    clashManager.changeProxy(ClashManager.PROXY_GROUP, node)
                                                    selected = node
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = node,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            if (delays.containsKey(node)) {
                                                Text(
                                                    text = delay
                                                        ?.let { stringResource(SYMR.strings.proxy_delay_ms, it) }
                                                        ?: stringResource(SYMR.strings.proxy_delay_failed),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    // 延迟由低到高：绿 → 黄 → 红（null 为失败，用主题 error 色）
                                                    color = when {
                                                        delay == null -> MaterialTheme.colorScheme.error
                                                        delay < DELAY_FAST_MS -> DELAY_FAST_COLOR
                                                        delay < DELAY_OK_MS -> DELAY_OK_COLOR
                                                        else -> DELAY_SLOW_COLOR
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = true),
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_close))
            }
        },
    )
}
