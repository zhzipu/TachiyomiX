package tachiyomi.source.network

import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import tachiyomi.source.network.io.RemoteFileSystem
import tachiyomi.source.network.io.RemoteProtocol
import tachiyomi.source.network.io.ftp.FtpFileSystem
import tachiyomi.source.network.io.webdav.WebDavFileSystem
import java.net.URI
import java.net.URLEncoder

/**
 * 网络图源的连接配置。
 *
 * 数据落在 `ConfigurableSource` 约定的那份 SharedPreferences（`source_<id>`）里，
 * 也就是图源设置页写入的那一份，两边读写同一个来源，不存在同步问题。
 *
 * 所有取值都以字符串存储，这样即使不填也不会因为类型不符而抛异常。
 *
 * ## 地址是一个完整 URL（**没有单独的端口项**）
 *
 * [serverUrl] 里 scheme / host / 端口 / 路径都写在一起，形如
 * `http://192.168.1.10:5005`、`https://example.com/remote.php/dav/files/user`
 * 或 `ftp://192.168.1.10:2121`。
 * 这与「数据与存储」里的 WebDAV 同步设置是同一套做法（那边也叫「服务器地址」）——
 * 端口本来就是 URL 的组成部分，拆成独立一栏只会出现「改了主机忘了改端口」。
 *
 * **WebDAV 与 FTP 用的是同一个字段、同一套目录约定**，两者只是传输层不同：
 * 换协议不用改地址以外的任何东西，服务器上的 `TachiyomiX manga/` 结构完全一样。
 *
 * 库根目录 [ROOT_DIRECTORY] 是**追加**在这个地址后面的（见 [Location]），
 * 所以用户填到「共享目录」这一层就行，不用自己拼 `TachiyomiX manga`。
 *
 * **根路径不可配置**：它由 [ROOT_DIRECTORY] 固定，既不在设置页里出现，
 * 也不会去读 SharedPreferences（见 [basePath]）。
 */
class NetworkSourcePreferences(
    private val prefs: SharedPreferences,
) {

    /**
     * 设置页里**选中**的协议。
     *
     * 注意它只是「地址里没写 scheme 时补哪一个」（见 [ensureScheme]）：
     * 地址里一旦写了 `ftp://` 或 `http://`，就按地址里的 scheme 走 ——
     * 真正决定用哪个实现的是 [Location.protocol]。
     */
    val protocol: RemoteProtocol
        get() = prefs.getString(KEY_PROTOCOL, null)
            ?.let { stored -> RemoteProtocol.entries.firstOrNull { it.name == stored } }
            ?: RemoteProtocol.WEBDAV

    /**
     * 服务器地址（完整 URL）。
     *
     * 例：`http://192.168.1.10:5005`、`https://dav.example.com/dav`、`ftp://192.168.1.10:2121`。
     * 不写端口就用该 scheme 的默认端口（http 80 / https 443 / ftp 21）；
     * 原来的「端口」栏与「使用 HTTPS」开关都已删除，这两件事都由 URL 表达。
     */
    val serverUrl: String get() = prefs.getString(KEY_SERVER_URL, null).orEmpty().trim()

    /**
     * 解析好的**库根地址**（协议无关）。
     *
     * 地址为空、不是合法 URL、或 scheme 认不出来（既不是 http/https/ftp）时是 null ——
     * [isConfigured] 也据此判断，这样「填了个乱七八糟的东西」和「没填」对上层是同一种情况：不可用。
     */
    val location: Location? get() = runCatching { buildLocation() }.getOrNull()

    /**
     * 库根的**解码后**绝对路径，形如 `/remote.php/dav/files/user/TachiyomiX manga`。
     *
     * WebDAV 拿它跟响应里的 `href` 比对（href 是解码后再比的），FTP 拿它当起始目录。
     * 所以这里必须是解码形态；拼 URL 的那一份由 [rootUrl] 负责，它是编码过的。
     */
    val basePath: String
        get() = location?.path ?: "/"

    val username: String get() = prefs.getString(KEY_USERNAME, null).orEmpty()

    val password: String get() = prefs.getString(KEY_PASSWORD, null).orEmpty()

    /**
     * 「下载项目自动上传」。
     *
     * 打开之后，书架「下载」分类里**下载完毕**的漫画会被自动上传到本图源的库
     * （由 App 侧的 `UploadManager` 读取这个开关并执行）。
     * 上传动作本身不在这里，这里只是把开关存进图源自己的那份 SharedPreferences。
     */
    val autoUpload: Boolean get() = prefs.getBoolean(KEY_AUTO_UPLOAD, false)

    /**
     * 「不通过软件代理」，**默认开**。
     *
     * 打开时图源用自己的直连客户端（见 `NetworkSource.client`），OkHttp 里显式设置了
     * `proxy(Proxy.NO_PROXY)`，内置的 `ClashProxySelector` 和手动 HTTP 代理都会被跳过。
     *
     * 为什么默认开：本图源读的是用户自己的 NAS / 路由器 / 本机，属于局域网地址，
     * 远端代理节点根本路由不过去，被接管之后只会拿到 HTTP 502 —— 也就是说，
     * 对典型用法而言「走代理」是 100% 失败的，「直连」才是唯一可用路径。
     * 真把库放在公网服务器上、且必须经代理才能访问时，用户手动关掉这个开关即可。
     *
     * FTP（纯 TCP，不走 HTTP）本来就不经过这个客户端，这个开关对它没有影响。
     */
    val bypassProxy: Boolean get() = prefs.getBoolean(KEY_BYPASS_PROXY, true)

    val isConfigured: Boolean get() = location != null

    /**
     * 库根地址的完整字符串（**编码过**，无尾斜杠）。
     *
     * 之所以要编码：库根目录名里带空格（`TachiyomiX manga`），直接把空格塞进 URL 是不合法的，
     * OkHttp 解析时要么抛错要么按自己的规则改写，而 `WebDavFileSystem` 会把 `href` 解码回来
     * 跟 [basePath] 比对 —— 只有编码/解码成对，相对路径的推导才不会错位。
     *
     * 没配置好时是空串（调用方本来就该先看 [isConfigured]）。
     */
    val rootUrl: String
        get() = location?.url.orEmpty()

    /**
     * 按当前配置造一个远端文件系统。
     *
     * 用哪个实现由**地址里的 scheme** 决定（见 [Location.protocol]）；
     * WebDAV 和 FTP 的目录约定完全一致，上层拿到的都是同一个 [RemoteFileSystem] 接口。
     */
    fun newFileSystem(client: OkHttpClient): RemoteFileSystem {
        val configured = checkNotNull(location) { "the network source is not configured" }
        return when (configured.protocol) {
            RemoteProtocol.WEBDAV -> WebDavFileSystem(
                client = client,
                baseUrl = configured.url,
                basePath = configured.path,
                username = username,
                password = password,
            )

            RemoteProtocol.FTP -> FtpFileSystem(
                baseUrl = configured.url,
                basePath = configured.path,
                host = configured.host,
                port = configured.port,
                username = username,
                password = password,
            )
        }
    }

    /**
     * 解析好的库根地址。
     *
     * [url] 与 [path] 是同一个地址的两种形态（编码 / 解码），必须成对使用 ——
     * WebDAV 拼请求用 [url]、比对 `href` 用 [path]，混用会导致相对路径推导错位。
     */
    class Location(
        /** 真正决定用哪个 [RemoteFileSystem] 实现的协议。 */
        val protocol: RemoteProtocol,
        val scheme: String,
        /** 主机名或 IP；IPv6 是**不带方括号**的裸地址。 */
        val host: String,
        val port: Int,
        /** 编码过的完整地址，无尾斜杠。 */
        val url: String,
        /** 解码后的绝对路径，无尾斜杠。 */
        val path: String,
    )

    /**
     * 把用户填的地址补上库根目录，得到 [Location]。
     *
     * 解析交给 `HttpUrl`：host 归一化、IPv6、非法字符、路径段解码都由它兜。
     * 已有路径段要保留（Nextcloud 那种 `/remote.php/dav/files/user` 是常态），
     * 结尾斜杠则统一去掉再拼，避免出现 `//`。
     */
    private fun buildLocation(): Location? {
        val raw = ensureScheme(serverUrl) ?: return null
        val scheme = schemeOf(raw) ?: return null

        // **显式写了 scheme 就听它的**：用户把 `ftp://…` 填进来、却还选着 WebDAV 时，
        // 按他填的地址去连才是他想要的。详细理由见 `RemoteProtocol` 的类注释。
        val effectiveProtocol = RemoteProtocol.ofScheme(scheme) ?: return null

        // `HttpUrl` 不认 `ftp://`，所以解析时临时把 scheme 换成 http ——
        // 我们只要 host 和路径段，scheme 用回用户填的那个。
        val parsed = raw.replaceFirst(SCHEME_PATTERN, "http://").toHttpUrlOrNull() ?: return null

        val segments = parsed.pathSegments.filter { it.isNotEmpty() } + ROOT_DIRECTORY
        val path = segments.joinToString(separator = "/", prefix = "/") { it }
        val encodedPath = segments.joinToString(separator = "/", prefix = "/") { encodeSegment(it) }
        // IPv6 字面量拼回 URL 时方括号不能少（`host` 里是没有括号的裸地址）
        val hostForUrl = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host

        // 端口：写了就用写的，没写用协议的默认端口。
        // 注意不能拿 `parsed.port` 顶替 —— 上面是把 scheme 当 http 解析的，
        // 「没写端口」时它会给出 80，对 ftp 来说是错的。所以单独问一次 URI「到底写没写」。
        val explicitPort = runCatching { URI(raw).port }.getOrDefault(-1)
        val port = explicitPort.takeIf { it in 1..65535 } ?: effectiveProtocol.defaultPort(scheme)

        // 默认端口不写出来，跟 `HttpUrl.toString()` 的行为保持一致。
        // （`HttpUrl` 没有现成的 isDefaultPort，`RemoteProtocol.defaultPort` 自己算。）
        val portPart = if (port == effectiveProtocol.defaultPort(scheme)) "" else ":$port"

        // 注意这里必须写 `${...}`：Kotlin 的字符串模板里 `$` 后面只吃一个**简单标识符**，
        // 写成 `"$scheme://..."` 没问题，但 `"$parsed.scheme://..."` 会被当成
        // `parsed.toString() + ".scheme://..."` —— 拼出来的地址是
        // `http://host/dav/.scheme://host/dav/TachiyomiX%20manga` 这种垃圾，
        // 而且 `HttpUrl` 还真能把它解析成合法 URL，于是所有请求 404，很难一眼看出。
        return Location(
            protocol = effectiveProtocol,
            scheme = scheme,
            host = parsed.host,
            port = port,
            url = "$scheme://$hostForUrl$portPart$encodedPath",
            path = path,
        )
    }

    /**
     * 用户没写 scheme 时补一个（`192.168.1.10:2121` 这种写法很常见）。
     *
     * 补哪一个由设置里选中的协议决定 —— 这也是那个下拉框**唯一**的作用：
     * 地址里一旦写了 scheme，就以 scheme 为准（见 [buildLocation]）。
     */
    private fun ensureScheme(raw: String): String? {
        if (raw.isBlank()) return null
        if (SCHEME_PATTERN.containsMatchIn(raw)) return raw
        return "${protocol.defaultScheme}://${raw.removePrefix("//")}"
    }

    private fun schemeOf(raw: String): String? =
        raw.substringBefore("://", missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() }
            ?.lowercase()

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        /**
         * 远端上用来存放漫画的目录名，固定不可改（WebDAV / FTP 共用同一套约定）。
         *
         * 目录约定的完整说明见 `tachiyomi.source.network.config.MangaConfig`。
         */
        const val ROOT_DIRECTORY = "TachiyomiX manga"

        const val KEY_PROTOCOL = "network_source_protocol"

        /** 服务器地址（完整 URL，端口写在里面）。 */
        const val KEY_SERVER_URL = "network_source_server_url"

        const val KEY_USERNAME = "network_source_username"
        const val KEY_PASSWORD = "network_source_password"

        /** 「下载项目自动上传」开关。App 侧的 `UploadManager` 会读它。 */
        const val KEY_AUTO_UPLOAD = "network_source_auto_upload"

        /** 「不通过软件代理」开关，默认开（见 [bypassProxy]）。 */
        const val KEY_BYPASS_PROXY = "network_source_bypass_proxy"

        /** 「测试连接」那一行。不存值，只在设置页里当个可点的入口。 */
        const val KEY_TEST_CONNECTION = "network_source_test_connection"

        /** 「清除 WebDAV 内容」那一行。同样不存值，只是个可点的入口。 */
        const val KEY_CLEAR_LIBRARY = "network_source_clear_library"

        /** 地址开头的 `scheme://`，用来判断用户写没写 scheme、以及把它替换掉。 */
        private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
    }
}
