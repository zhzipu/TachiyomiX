package eu.kanade.tachiyomi.network

/**
 * 内置代理（Clash / 手动 HTTP 代理）的**作用域**：每一类流量单独决定"走代理"还是"直连"。
 *
 * 之所以要分作用域：Clash 的订阅节点往往只对部分站点好用（比如在线看图要翻，但 WebDAV
 * 走直连更快、插件仓库直连就够），全局一刀切会把本来正常的东西也拖进代理。
 *
 * [defaultEnabled] 是**首次使用**时的默认值：
 * - `ONLINE_READING`、`MANGA_DOWNLOAD` 默认走代理（这两个是最需要翻的）；
 * - 其余默认直连（升级上来的老用户如果之前把 Clash 开着，这几类会从"走代理"变成"直连"）。
 *
 * 注意：这里只管"走不走代理"，内置代理本身的总开关仍然是 [ClashPreferences.enabled] /
 * [ClashPreferences.httpProxyEnabled]——总开关关掉时所有作用域都是直连。
 */
enum class ProxyScope(
    /** 偏好键后缀，改成它会丢失用户已有设置。 */
    val key: String,
    val defaultEnabled: Boolean,
) {
    /** 在线阅读：在线图源（图源列表、详情、章节、看图）与追番登录。 */
    ONLINE_READING("online_reading", true),

    /** 下载漫画：网络图源（WebDAV/FTP 书库）的读取——打开/下载库里的漫画走这条。 */
    MANGA_DOWNLOAD("manga_download", true),

    /** 上传漫画：把本地漫画/章节上传到网络图源书库。 */
    MANGA_UPLOAD("manga_upload", false),

    /** 插件仓库：拉取扩展仓库索引。 */
    EXTENSION_REPO("extension_repo", false),

    /** 插件下载更新：下载/更新扩展 APK。 */
    EXTENSION_DOWNLOAD("extension_download", false),

    /** 同步服务：SyncYomi、阿里云盘、Google Drive 等。 */
    SYNC("sync", false),

    /** WebDAV 同步（备份同步里的 WebDAV 服务）。 */
    WEBDAV("webdav", false),

    /** 版本检测：查询最新版本号。 */
    VERSION_CHECK("version_check", false),

    /** 更新下载：下载新版本安装包。 */
    UPDATE_DOWNLOAD("update_download", false),
    ;

    companion object {
        /** 默认勾选的作用域（用于设置页展示默认值/迁移判断）。 */
        val defaultEnabledScopes: List<ProxyScope> get() = entries.filter { it.defaultEnabled }
    }
}
