package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

/* SY --> */
open /* SY <-- */ class NetworkHelper(
    private val context: Context,
    private val preferences: NetworkPreferences,
    // SY -->
    val isDebugBuild: Boolean,
    private val clashPreferences: ClashPreferences? = null,
    // SY <--
) {

    /* SY --> */
    open /* SY <-- */val cookieJar = AndroidCookieJar()

    private val clientBuilder: OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

        if (isDebugBuild) {
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        // SY -->
        clashPreferences?.let { builder.proxySelector(ClashProxySelector(it)) }
        // SY <--

        when (preferences.dohProvider.get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            else -> builder
        }
    }

    /* SY --> */
    open /* SY <-- */ val client = clientBuilder
        .addInterceptor(
            CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider),
        )
        .build()

    /**
     * Client that always connects directly, bypassing the built-in Clash proxy
     * and the manual HTTP proxy. Used for the plugin marketplace, update checks
     * and APK downloads, which must not be routed through the proxy. Setting a
     * fixed [Proxy.NO_PROXY] makes OkHttp ignore the [ClashProxySelector].
     */
    val directClient: OkHttpClient = client.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .build()

    /* SY --> */
    /**
     * 按**作用域**取客户端：该作用域勾选"走内置代理"才用代理，否则直连。
     *
     * [ClashProxySelector] 每次请求都重新读偏好，所以切换作用域开关立即生效、不用重建客户端。
     * 基类 [client] 本身就带"在线阅读"作用域，所以图源 / 追番登录等默认跟着在线阅读走。
     */
    private val scopedClients = java.util.concurrent.ConcurrentHashMap<ProxyScope, OkHttpClient>()

    fun clientFor(scope: ProxyScope): OkHttpClient {
        val clash = clashPreferences ?: return client
        return scopedClients.getOrPut(scope) {
            // 用 client.newBuilder()（而不是共享的 clientBuilder）：新 builder 是独立的，
            // 不会把拦截器重复加到同一个 builder 上，换掉的只有代理选择器。
            client.newBuilder()
                .proxySelector(ClashProxySelector(clash, scope))
                .build()
        }
    }
    /* SY <-- */

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    /* SY --> */
    open /* SY <-- */val cloudflareClient: OkHttpClient = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent.get().trim()
}
