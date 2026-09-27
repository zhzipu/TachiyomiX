package eu.kanade.tachiyomi.clash

import android.content.Context
import android.os.Build
import android.util.Log
import eu.kanade.tachiyomi.network.ClashPreferences
import io.github.oviron.libmihomo.Clash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.resume

/**
 * Wraps the embedded mihomo (Clash) core via libmihomo-android. The core listens
 * on a local `127.0.0.1` mixed HTTP/SOCKS port only — no system VPN service is
 * used. Application traffic is routed through that port by [ClashProxySelector].
 */
class ClashManager(
    private val context: Context,
    private val preferences: ClashPreferences,
) {

    data class ProxyGroup(
        val nodes: List<String>,
        val selected: String?,
    )

    companion object {
        const val PROXY_GROUP = "PROXY"

        private const val TAG = "ClashManager"
    }

    private val homeDir = File(context.filesDir, "clash")
    private val configFile = File(homeDir, "config.yaml")

    @Volatile
    private var loaded = false

    private suspend fun ensureEngine() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            synchronized(this@ClashManager) {
                if (loaded) return@synchronized
                val libDir = extractNativeLibs()
                Clash.load(libDir)
                check(Clash.bridgeABI() == Clash.EXPECTED_BRIDGE_ABI) {
                    "libclash.so ABI mismatch (${Clash.bridgeABI()} != ${Clash.EXPECTED_BRIDGE_ABI})"
                }
                loaded = true
            }
        }
    }

    /**
     * Some ROMs (notably OPPO/OnePlus) report an empty or mis-named ABI directory
     * via [android.content.pm.ApplicationInfo.nativeLibraryDir] (e.g. `lib/arm64`
     * instead of `lib/arm64-v8a`), so the core `.so` files are not found there.
     * Extract them from the APK into our own directory and load from there instead.
     */
    private fun extractNativeLibs(): String {
        homeDir.mkdirs()
        val names = listOf("libclash.so", "libmihomo-jni.so")
        val candidates = buildList {
            Build.SUPPORTED_ABIS.forEach { abi ->
                add(abi)
                if (abi == "arm64") add("arm64-v8a")
            }
            addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }.distinct()

        val apkPath = context.applicationInfo.sourceDir
        ZipFile(apkPath).use { zip ->
            val abi = candidates.firstOrNull { candidate ->
                names.all { zip.getEntry("lib/$candidate/$it") != null }
            } ?: error("No mihomo native libs found in APK")

            names.forEach { name ->
                val entry = zip.getEntry("lib/$abi/$name")!!
                val dest = File(homeDir, name)
                if (!dest.exists() || dest.length() != entry.size) {
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        return homeDir.absolutePath
    }

    /** Writes the mihomo config to disk, then (re)initializes the core with it. */
    suspend fun restart() {
        ensureEngine()
        // Recover from a previous stop(), which suspends the tunnel.
        runCatching { Clash.suspended(false) }
        writeConfig()
        val init = JSONObject()
            .put("home-dir", homeDir.absolutePath)
            .put("version", Build.VERSION.SDK_INT)
            .toString()
        quickSetup(init, "{}")
    }

    /** Stops the local listener and pauses forwarding so the proxy port is released. */
    suspend fun stop() {
        if (!loaded) return
        runCatching { Clash.suspended(true) }
        runCatching { invokeAction("stopListener", null) }
    }

    suspend fun changeProxy(group: String, proxy: String) {
        ensureEngine()
        val data = JSONObject()
            .put("group-name", group)
            .put("proxy-name", proxy)
            .toString()
        invokeAction("changeProxy", data)
        preferences.selectedProxy.set(proxy)
    }

    suspend fun getProxyGroup(): ProxyGroup? {
        ensureEngine()
        return parseProxyGroup(invokeAction("getProxies", null))
    }

    /**
     * Forces the active proxy provider (subscription or manual config) to
     * re-download/reload its nodes from source. Relies on the mihomo
     * `updateExternalProvider` action, which takes `{"type":"Proxy","name":...}`.
     */
    suspend fun refreshProviders() {
        ensureEngine()
        val name = when {
            preferences.subscriptionUrl.get().trim().isNotEmpty() -> "sy-sub"
            preferences.manualConfig.get().trim().isNotEmpty() -> "sy-proxy"
            else -> null
        } ?: return
        val data = JSONObject()
            .put("type", "Proxy")
            .put("name", name)
            .toString()
        Log.d(TAG, "refreshProviders => $name")
        runCatching { invokeAction("updateExternalProvider", data) }
    }

    /** Returns the delay in ms, or null if the node is unreachable. */
    suspend fun testDelay(proxyName: String): Int? {
        ensureEngine()
        val data = JSONObject()
            .put("proxy-name", proxyName)
            .put("test-url", testUrl())
            .put("timeout", 5000L)
            .toString()
        val raw = invokeAction("testDelay", data)
        Log.d(TAG, "testDelay($proxyName) => $raw")
        return parseDelay(raw)
    }

    private fun testUrl(): String =
        preferences.testUrl.get().ifBlank { "https://www.gstatic.com/generate_204" }

    private suspend fun invokeAction(method: String, dataString: String?): String =
        suspendCancellableCoroutine { cont ->
            val action = JSONObject()
                .put("id", System.currentTimeMillis().toString())
                .put("method", method)
            if (dataString != null) {
                action.put("data", dataString)
            }
            Clash.invokeAction(action.toString()) { result ->
                Log.d(TAG, "invokeAction($method) => $result")
                if (cont.isActive) cont.resume(result ?: "")
            }
        }

    private suspend fun quickSetup(initJson: String, setupJson: String) =
        suspendCancellableCoroutine { cont ->
            Clash.quickSetup(initJson, setupJson) { result ->
                if (!result.isNullOrEmpty()) {
                    Log.e(TAG, "quickSetup failed: $result")
                }
                if (cont.isActive) cont.resume(Unit)
            }
        }

    private fun parseProxyGroup(raw: String): ProxyGroup? {
        return runCatching {
            val json = JSONObject(raw)
            if (json.optInt("code", -1) != 0) {
                Log.w(TAG, "getProxies code != 0: $raw")
                return null
            }
            // getProxies returns `data` as a JSON string (handleGetProxies returns
            // string, which ActionResult then re-marshals), so parse it twice.
            val dataText = json.optString("data")
            if (dataText.isBlank()) {
                Log.w(TAG, "getProxies data is blank: $raw")
                return null
            }
            val data = JSONObject(dataText)
            val proxies = data.optJSONObject("proxies")
            if (proxies == null) {
                Log.w(TAG, "getProxies has no 'proxies' object: $dataText")
                return null
            }
            val group = proxies.optJSONObject(PROXY_GROUP)
            if (group == null) {
                Log.w(TAG, "getProxies has no '$PROXY_GROUP' group. keys=${proxies.keys()}")
                return null
            }
            val all = group.optJSONArray("all")
            val nodes = if (all != null) {
                (0 until all.length()).mapNotNull { i ->
                    all.optString(i).takeIf { it.isNotEmpty() && it != PROXY_GROUP }
                }
            } else {
                emptyList()
            }
            Log.d(TAG, "parseProxyGroup nodes=${nodes.size} selected=${group.optString("now")}")
            ProxyGroup(
                nodes = nodes,
                selected = group.optString("now").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    private fun parseDelay(raw: String): Int? {
        return runCatching {
            val json = JSONObject(raw)
            if (json.optInt("code", -1) != 0) return null
            val delay = json.optInt("data", -1)
            delay.takeIf { it >= 0 }
        }.getOrNull()
    }

    private fun writeConfig() {
        homeDir.mkdirs()
        val port = preferences.proxyPort()
        val url = testUrl()

        val subscriptionUrl = preferences.subscriptionUrl.get().trim()
        val manualConfig = preferences.manualConfig.get().trim()

        val providerName: String?
        val providerBlock: String
        when {
            subscriptionUrl.isNotEmpty() -> {
                providerName = "sy-sub"
                providerBlock = """
                    proxy-providers:
                      $providerName:
                        type: http
                        url: "$subscriptionUrl"
                        interval: 3600
                        path: ./sy-sub.yaml
                        health-check:
                          enable: true
                          url: $url
                          interval: 300
                """.trimIndent()
            }

            manualConfig.isNotEmpty() -> {
                providerName = "sy-proxy"
                val content = if (manualConfig.startsWith("proxies:")) {
                    manualConfig
                } else {
                    "proxies:\n$manualConfig"
                }
                File(homeDir, "proxies.yaml").writeText(content)
                providerBlock = """
                    proxy-providers:
                      $providerName:
                        type: file
                        path: ./proxies.yaml
                        health-check:
                          enable: true
                          url: $url
                          interval: 300
                """.trimIndent()
            }

            else -> {
                providerName = null
                providerBlock = ""
            }
        }

        val groupBlock = if (providerName != null) {
            """
                proxy-groups:
                  - name: $PROXY_GROUP
                    type: select
                    use:
                      - $providerName
                    proxies:
                      - DIRECT
            """.trimIndent()
        } else {
            """
                proxy-groups:
                  - name: $PROXY_GROUP
                    type: select
                    proxies:
                      - DIRECT
            """.trimIndent()
        }

        val yaml = buildString {
            appendLine("mixed-port: $port")
            appendLine("bind-address: 127.0.0.1")
            appendLine("allow-lan: false")
            appendLine("mode: rule")
            appendLine("log-level: silent")
            appendLine("ipv6: false")
            if (providerBlock.isNotEmpty()) {
                appendLine(providerBlock)
            }
            appendLine(groupBlock)
            appendLine("rules:")
            appendLine("  - MATCH,$PROXY_GROUP")
        }

        configFile.writeText(yaml)
    }
}
