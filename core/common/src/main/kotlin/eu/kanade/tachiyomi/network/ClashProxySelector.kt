package eu.kanade.tachiyomi.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Routes requests through the app's local Clash (mihomo) proxy when enabled,
 * otherwise falls back to a direct connection. Checked on every request so the
 * toggle takes effect without rebuilding the [okhttp3.OkHttpClient].
 *
 * SY --> 带 [scope]：该作用域没勾"走代理"时一律直连，勾了才按总开关（Clash / 手动代理）走。
 */
class ClashProxySelector(
    private val preferences: ClashPreferences,
    private val scope: ProxyScope = ProxyScope.ONLINE_READING,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        // SY --> 这条链路自己没勾走代理 → 直连
        if (!preferences.isScopeEnabled(scope)) {
            return listOf(Proxy.NO_PROXY)
        }
        // SY <--
        if (preferences.httpProxyEnabled.get()) {
            val address = preferences.httpProxyAddress()
            if (address != null) {
                return listOf(Proxy(Proxy.Type.HTTP, address))
            }
            return listOf(Proxy.NO_PROXY)
        }
        return if (preferences.enabled.get()) {
            listOf(
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress("127.0.0.1", preferences.proxyPort()),
                ),
            )
        } else {
            listOf(Proxy.NO_PROXY)
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
}
