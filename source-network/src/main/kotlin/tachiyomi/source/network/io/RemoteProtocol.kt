package tachiyomi.source.network.io

/**
 * 网络图源支持的远端协议。
 *
 * 每个协议对应一个 [RemoteFileSystem] 实现：WebDAV 见
 * [tachiyomi.source.network.io.webdav.WebDavFileSystem]，FTP 见
 * [tachiyomi.source.network.io.ftp.FtpFileSystem]。上层（目录树映射、上传）
 * 完全不知道用的是哪个 —— 换协议只是换一个实现。
 *
 * **协议里不含端口**：地址栏填的是完整 URL，端口由 URL 自己带（不写就用 scheme 的默认端口），
 * 与「数据与存储」里的 WebDAV 同步设置一致。
 *
 * ## 协议与地址里的 scheme 谁说了算
 *
 * **显式写了 scheme 就听 scheme 的**；只在用户没写 scheme（例如只填 `192.168.1.10:2121`）时，
 * 才用设置里选中的协议去补默认 scheme。理由：地址是要真的去连的东西，
 * 用户把 `ftp://…` 填进去又选着 WebDAV 时，按他填的地址连才是他想要的；
 * 反过来「静默把 ftp 改成 http」只会得到一堆莫名其妙的错误。
 */
enum class RemoteProtocol(
    /** 不写 scheme 时补哪一个。 */
    val defaultScheme: String,
) {
    WEBDAV("http"),
    FTP("ftp"),
    ;

    /**
     * [scheme] 对应的默认端口。**不写端口**时用它。
     *
     * 注意不能拿 `HttpUrl` 的默认端口来顶替：`HttpUrl` 不认 `ftp://`，
     * 而把 ftp 当 http 解析时它会给一个 80，那是错的。
     */
    fun defaultPort(scheme: String): Int = when (this) {
        WEBDAV -> if (scheme.equals("https", ignoreCase = true)) 443 else 80
        FTP -> 21
    }

    companion object {
        /**
         * 按 URL 里的 scheme 认出协议；认不出（既不是 http/https 也不是 ftp）返回 null。
         *
         * `https` 也算 WebDAV —— 它就是走 TLS 的同一套方法。
         */
        fun ofScheme(scheme: String): RemoteProtocol? = when (scheme.lowercase()) {
            "http", "https" -> WEBDAV
            "ftp" -> FTP
            else -> null
        }
    }
}
