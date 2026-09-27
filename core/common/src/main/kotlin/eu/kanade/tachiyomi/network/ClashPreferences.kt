package eu.kanade.tachiyomi.network

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.net.InetSocketAddress

class ClashPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /** Master switch for the built-in proxy. */
    val enabled: Preference<Boolean> = preferenceStore.getBoolean("clash_enabled", false)

    /** Local HTTP/SOCKS (mixed) proxy port the app routes traffic through. */
    val httpPort: Preference<String> = preferenceStore.getString("clash_http_port", "7890")

    /** Subscription URL used to fetch proxy nodes. */
    val subscriptionUrl: Preference<String> = preferenceStore.getString("clash_subscription_url", "")

    /** Manually provided proxy list (Clash YAML `proxies:` entries). */
    val manualConfig: Preference<String> = preferenceStore.getString("clash_manual_config", "")

    /** Currently selected node inside the selector group. */
    val selectedProxy: Preference<String> = preferenceStore.getString("clash_selected_proxy", "")

    /** URL used for delay tests. */
    val testUrl: Preference<String> = preferenceStore.getString("clash_test_url", "https://www.gstatic.com/generate_204")

    /** Manual HTTP proxy toggle. */
    val httpProxyEnabled: Preference<Boolean> = preferenceStore.getBoolean("http_proxy_enabled", false)

    /** Manual HTTP proxy host (IP or domain). */
    val httpProxyHost: Preference<String> = preferenceStore.getString("http_proxy_host", "")

    /** Manual HTTP proxy port. */
    val httpProxyPort: Preference<String> = preferenceStore.getString("http_proxy_port", "8080")

    fun proxyPort(): Int = httpPort.get().toIntOrNull() ?: 7890

    fun baseUrl(): String = "http://127.0.0.1:${proxyPort()}"

    /** Returns a valid manual HTTP proxy address, or null if misconfigured. */
    fun httpProxyAddress(): InetSocketAddress? {
        val host = httpProxyHost.get().trim()
        val port = httpProxyPort.get().trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return InetSocketAddress(host, port)
    }
}
