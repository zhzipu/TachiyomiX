package tachiyomi.source.network

import android.content.Context
import android.net.Uri
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.source.network.config.CONFIG_FILE_NAME
import tachiyomi.source.network.config.ChapterConfig
import tachiyomi.source.network.config.ConfigJson
import tachiyomi.source.network.config.LibraryIndex
import tachiyomi.source.network.config.MangaConfig
import tachiyomi.source.network.config.PENDING_CHAPTER_URL_PREFIX
import tachiyomi.source.network.config.chapterFolderIndex
import tachiyomi.source.network.config.isPendingChapterUrl
import tachiyomi.source.network.filter.OrderBy
import tachiyomi.source.network.io.LocalAssetCache
import tachiyomi.source.network.io.RemoteAuthException
import tachiyomi.source.network.io.RemoteEntry
import tachiyomi.source.network.io.RemoteFileSystem
import tachiyomi.source.network.io.RemoteProtocol
import tachiyomi.source.network.io.RemoteUnreachableException
import tachiyomi.source.network.library.NetworkLibraryClient
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络图源：把远端（WebDAV）上的漫画库当成图源来读。
 *
 * ## 目录约定
 *
 * ```
 * <WebDAV 根>/TachiyomiX manga/          ← 根路径固定（见 NetworkSourcePreferences.ROOT_DIRECTORY）
 *   ├── config.json                      ← 库索引：漫画名 → 漫画文件夹
 *   ├── kR7pQ2/                          ← 漫画文件夹，随机字符串命名（只是个 ID）
 *   │    ├── config.json                 ← 漫画名 / 封面 / 作者 / 简介 / 标签 / 已上传的章节
 *   │    ├── cover.jpg
 *   │    └── No.0001/                    ← 章节文件夹，递增序号
 *   │         ├── 001.jpg                ← 该话的图片，一页一个文件
 *   │         └── 002.jpg
 *   └── x9Lm3T/
 * ```
 *
 * `config.json` 的字段含义见 [tachiyomi.source.network.config.MangaConfig]；
 * 根目录那份索引见 [tachiyomi.source.network.config.LibraryIndex]。
 *
 * **一话 = 一个图片目录**（不是压缩包，原因见 `MangaConfig` 的类注释）：
 * 页表来自一次 PROPFIND，取图逐页 GET，第一页不用等整话下完。
 * 早期上传的压缩包仍然读得动（回退到「整包下载 + libarchive 解包」），但新数据不再产生。
 *
 * **索引是唯一真相**：根目录里不在索引中的目录一律不认 —— 这样根目录混进别的文件夹
 * （备份、临时目录…）不会被当成漫画，也不用再靠「有没有 config.json」去猜。
 *
 * ## 必须绕过去的一个坑
 *
 * **封面不能直接给远端 URL。** 阅读器/书架加载 `thumbnail_url` 走 Coil，**不会带图源的
 * Basic Auth**，所以封面要先落本地（[LocalAssetCache]），再把 `file://` 地址交给上层。
 *
 * （页面**没有**这个问题：一页就是一个普通图片文件，[getImage] 把远端字节直接当响应体返回，
 * 既不用落本地也不用 libarchive —— 这也是不把一话打成压缩包的原因之一：
 * `ArchiveReader` 必须 mmap 整个包，那样第一页就得等整话下完。）
 *
 * 至于「一个章节 = 一堆图片」这个上层模型：本图源继承 [HttpSource]，
 * `ChapterLoader` 就一定会用 `HttpPageLoader`，它只认
 * [getPageList] + [getImage] 两个口子 —— 所以远端形态的差异全部收敛在这两个函数里，
 * 阅读器、下载器、书库更新、迁移这些链路一行都不用改。
 */
class NetworkSource(
    private val context: Context,
) : HttpSource(), ConfigurableSource {

    private val preferences: NetworkSourcePreferences by lazy {
        NetworkSourcePreferences(sourcePreferences())
    }

    private val assetCache by lazy { LocalAssetCache(context) }

    /** 远端文件的进程内记忆：路径 + 大小/时间 做键，远端一变键就变，不会读到旧内容。 */
    private val configCache = ConcurrentHashMap<String, MangaConfig>()
    private val indexCache = ConcurrentHashMap<String, LibraryIndex>()

    override val name: String = context.stringResource(SYMR.strings.network_source)

    override val id: Long = ID

    /**
     * 必须是 "all"，不能是 "other"。
     *
     * [eu.kanade.domain.source.interactor.GetEnabledSources] 里有一句
     * `.filter { it.lang in enabledLanguages || it.isLocal() }`，而
     * `enabledLanguages` 的默认值是 `{"all", "en", 系统语言}`（见 `LocaleHelper.getDefaultEnabledLanguages`）。
     * 用 "other" 的话本图源不在默认集合里，会**直接从图源列表消失**，用户得先去
     * 设置里手动勾选「其他」语言才看得到。"all" 在默认集合内，且
     * `LocaleHelper.getSourceDisplayName` 会把它显示为「多语言」并排在最前。
     *
     * 自建 WebDAV 库里的内容是用户自己的收藏，本来就没有单一语言，用 "all" 语义也正确
     * （`MergedSource`、`EHentai` 同样用 "all"）。
     */
    override val lang: String = "all"

    /**
     * 只保留「浏览」一个列表。
     *
     * `BrowseSourceScreen` 里「最近更新」那个 chip 就挂在这个属性上，置 false 之后
     * 浏览页只剩「浏览」（见 [getPopularManga]），而它的默认排序是
     * [OrderBy.DateDescending]，也就是「从新到旧」。
     */
    override val supportsLatest: Boolean = false

    /**
     * 本图源的网络客户端，「不通过软件代理」开启时（默认）强制直连。
     *
     * 局域网 WebDAV 走不了外网代理节点，被接管之后只会得到 502（见
     * [NetworkSourcePreferences.bypassProxy]）。OkHttp 的规则是：`proxy` 一旦显式设置，
     * 就完全不看 `proxySelector`，所以这一行同时绕过了 App 内置的
     * `ClashProxySelector` 和手动 HTTP 代理，且**不需要 App 侧做任何开关联动**。
     *
     * 客户端按需懒建、只建一次，避免每个请求都 `newBuilder().build()`。
     */
    private val directClient: OkHttpClient by lazy {
        super.client.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    override val client: OkHttpClient
        get() = if (preferences.bypassProxy) directClient else super.client

    /** 与本地图源一致：只显示名字，不要 `名字 (MULTI)` 这种语言后缀。 */
    override fun toString(): String = name

    /**
     * 地址随设置变化，所以这里每次读取。
     * 正常流程里 [getMangaUrl] / [getChapterUrl] 都被覆写了，这个属性只作为兜底。
     */
    override val baseUrl: String get() = preferences.rootUrl

    /** 默认「日期、从新到旧」，与 [getFilterList] 同源，避免两处各写一份。 */
    private fun defaultFilters(): FilterList = FilterList(OrderBy.DateDescending(context))

    override fun getFilterList(): FilterList = defaultFilters()

    // 浏览

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchManga(page, "", defaultFilters())

    /**
     * 本图源 [supportsLatest] 为 false，上层不会调用它；
     * 但 [HttpSource] 是抽象方法，必须给个实现 —— 直接复用「浏览」的语义。
     */
    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchManga(page, "", defaultFilters())

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val remote = fileSystem()

        // 一次列根目录（拿漫画文件夹的 lastModified，排序要用）+ 一次读根索引（决定有哪些漫画）
        val rootEntries = listFolder(remote, "")
        val dirsByName = rootEntries
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .distinctBy { it.name }
            .associateBy { it.name }
        val index = readIndex(remote, rootEntries)

        var mangas = scanIndexedFolders(remote, index, dirsByName)

        if (query.isNotBlank()) {
            // 标题来自漫画自己的 config.json；它缺失时退回根索引里记的名字（见 MangaFolder.title）。
            // 不再拿文件夹名去匹配 —— 那是个随机串，撞上了只会让人莫名其妙。
            mangas = mangas.filter { folder -> folder.title.contains(query, ignoreCase = true) }
        }

        filters.forEach { filter ->
            when (filter) {
                // 排序项只有 [OrderBy] 一个，但下拉里「标题 / 日期」两列都要生效，
                // 所以按 `state.index` 分派而不是按具体子类 —— 否则用户切到「标题」
                // 之后实例还是默认的那个子类，排序不会变（本地图源就踩了这个坑）。
                is OrderBy -> {
                    val selection = filter.state
                    if (selection != null) {
                        mangas = when (selection.index) {
                            0 -> if (selection.ascending) {
                                mangas.sortedBy { it.title.lowercase() }
                            } else {
                                mangas.sortedByDescending { it.title.lowercase() }
                            }

                            else -> if (selection.ascending) {
                                mangas.sortedBy { it.dir.lastModified }
                            } else {
                                mangas.sortedByDescending { it.dir.lastModified }
                            }
                        }
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        MangasPage(mangas.map { it.toSManga() }, false)
    }

    // 详情与章节

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
        val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    /**
     * 简介 / 作者 / 标签 / 封面都来自 `config.json`，其余元数据远端没有。
     * 必须把 `initialized` 置位，否则每次打开漫画都会再走一遍「更新详情」。
     *
     * **不动标题**：调用方传进来的 [SManga] 已经带着 config.json 里的漫画名了
     * （列表页 / 索引兜底都填过），这里再写一遍只会把用户在书架里手改的名字顶掉。
     *
     * 也只填远端**确实提供了**的字段：配置里没写作者/简介/标签时留空，
     * 不要用空值去覆盖上游已有的内容（本地手动填过的元数据应该保住）。
     */
    private suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        val remote = fileSystem()
        val folderPath = manga.url

        val entries = listFolder(remote, folderPath)
        val config = readConfig(remote, entries)
        val cover = coverUrlOf(remote, folderPath, config, entries)

        manga.apply {
            config?.authorName?.let { author = it }
            config?.synopsis?.let { description = it }
            // SManga.genre 是「逗号+空格」连接的字符串（见 SManga.getGenres），不是数组
            config?.allTags?.takeIf { it.isNotEmpty() }?.let { genre = it.joinToString(", ") }
            if (cover != null) thumbnail_url = cover
            initialized = true
        }
    }

    /**
     * 章节 = 漫画文件夹下 `No.NNNN` 章节文件夹里的压缩包。
     *
     * 优先信任 `config.json` 里的 `chapters`（章节名、章节号、章节文件夹都由它说了算）；
     * 它缺失或为空时退化成「扫描 `No.NNNN` 文件夹，每个里面的第一个压缩包算一话」，
     * 用**章节文件夹名**当章节名（一话一个目录，目录名是这里唯一能拿到的信息）——
     * 配置写坏了也不至于整个漫画打不开。
     *
     * 注意 `chapters` 里**只有已经上传的章节**：没上传的章节不在这份列表里，图源也就看不到它。
     */
    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val remote = fileSystem()
        val folderPath = manga.url

        val entries = listFolder(remote, folderPath)
        val config = readConfig(remote, entries)

        // 章节文件夹一次列出来（顺便拿到 lastModified 当章节的「上传日期」），
        // 不然后面每一话都要单独 PROPFIND 一次。
        val chapterDirs = entries
            .filter { it.isDirectory && chapterFolderIndex(it.name) != null }
            .distinctBy { it.name }
            .associateBy { it.name }

        val chapters = config?.chapters?.takeIf { it.isNotEmpty() }
            ?.mapNotNull { chapterConfig ->
                buildChapterFromConfig(manga, chapterConfig, folderPath, chapterDirs)
            }
            ?: fallbackChapters(manga, chapterDirs)

        // 上层约定：章节列表按「新的在前」排列
        chapters.sortedWith { c1, c2 ->
            val byNumber = c2.chapter_number.compareTo(c1.chapter_number)
            if (byNumber != 0) byNumber else c2.name.compareToCaseInsensitiveNaturalOrder(c1.name)
        }
    }

    /**
     * 配置里的一话 → [SChapter]。
     *
     * **有内容**的一话（配置里有 `folder`，且那个文件夹真的在服务器上）地址就是它的
     * 章节文件夹（`<漫画文件夹>/<No.NNNN>`），图片都在里面。所以这里**不需要任何请求** ——
     * 章节文件夹存在与否，从漫画文件夹那一次 PROPFIND 就已经知道了。
     *
     * **没内容**的一话（配置里没写 `folder`，＝ 本机没下载、还没上传）也照样返回一条，
     * 地址是 [pendingChapterUrl] 那个虚拟地址。列表因此能完整反映「这本一共多少话」，
     * 上层再把它置灰、点开提示「无数据」。
     */
    private fun buildChapterFromConfig(
        manga: SManga,
        config: ChapterConfig,
        folderPath: String,
        chapterDirs: Map<String, RemoteEntry>,
    ): SChapter? {
        val chapterFolder = config.chapterFolder
        val dirEntry = chapterFolder?.let { chapterDirs[it] }

        // 没有 `folder`（＝还没上传），或 `folder` 在服务器上不存在（内容被手动删过？）
        // → 这一话**没有数据**。仍然把它列出来（用户要求：未上传的章节按顺序一起显示），
        // 但地址换成虚拟地址 —— 上层据此置灰、点开提示「无数据」。
        if (chapterFolder == null || dirEntry == null) {
            val title = config.displayTitle ?: chapterFolder ?: return null
            return buildChapter(
                manga = manga,
                title = title,
                chapterUrl = pendingChapterUrl(config),
                number = config.number?.toFloat(),
                // 没有内容可看，也就没有「上传日期」可给；上层会填成当前时间
                lastModified = 0L,
            )
        }

        return buildChapter(
            manga = manga,
            title = config.displayTitle ?: chapterFolder,
            chapterUrl = "$folderPath/$chapterFolder",
            number = config.number?.toFloat(),
            lastModified = dirEntry.lastModified,
        )
    }

    /**
     * 「未上传」章节的虚拟地址。
     *
     * 必须**每话唯一** —— 章节在同步时按 `url` 匹配，全空或重复会被合并成一话
     * （理由见 [PENDING_CHAPTER_URL_PREFIX]）。
     *
     * 上传之后这一话的地址会换成真实的章节文件夹，这时同步逻辑会按**章节号**把
     * 已读状态 / 书签带过去（`SyncChaptersWithSource` 里的 `deletedChapterNumbers` 那套），
     * 所以地址变了不会丢阅读进度。
     */
    private fun pendingChapterUrl(config: ChapterConfig): String =
        PENDING_CHAPTER_URL_PREFIX + (config.number?.toString().orEmpty()) + "#" + config.displayTitle.orEmpty()

    /**
     * 配置缺失时的退化路径：把每个 `No.NNNN` 文件夹当成一话。
     *
     * 标题取**章节文件夹名**（`No.0001`）—— 一话一个目录，目录名是这里唯一能拿到的信息。
     * 章节号用文件夹序号兜底，保证列表顺序跟服务器上的目录顺序一致。
     */
    private fun fallbackChapters(
        manga: SManga,
        chapterDirs: Map<String, RemoteEntry>,
    ): List<SChapter> = chapterDirs.values
        .sortedBy { chapterFolderIndex(it.name) ?: Int.MAX_VALUE }
        .map { dir ->
            buildChapter(
                manga = manga,
                title = dir.name,
                chapterUrl = dir.path,
                number = chapterFolderIndex(dir.name)?.toFloat(),
                lastModified = dir.lastModified,
            )
        }

    private fun buildChapter(
        manga: SManga,
        title: String,
        chapterUrl: String,
        number: Float?,
        lastModified: Long,
    ): SChapter = SChapter.create().apply {
        url = chapterUrl
        name = title
        date_upload = lastModified
        chapter_number = number
            ?: ChapterRecognition.parseChapterNumber(manga.title, title, chapter_number.toDouble()).toFloat()
    }

    /**
     * 一话的页 = 章节文件夹里的图片，按文件名自然序。
     *
     * **不需要先把整话下到本地**：页表来自一次 PROPFIND，取图时逐页 GET（见 [getImage]）。
     * 这正是把「一话一个压缩包」改成「一话一个图片目录」的全部理由 ——
     * 压缩包形态下 libarchive 必须 mmap 整个包，第一页要等整包传完才出现。
     *
     * 章节文件夹是空的（被人手动删过图片之类）就返回空页表，不在意料之外。
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> = withIOContext {
        // 「未上传」的章节在服务器上没有内容。界面上点它会被拦住并提示「无数据」，
        // 这里是兜底（比如从通知栏、迁移、下载队列走到这里），给一条说得清的错，
        // 不要让它变成一次莫名其妙的 PROPFIND 404。
        if (chapter.url.isPendingChapterUrl()) {
            throw IOException(context.stringResource(SYMR.strings.chapter_no_data))
        }

        val remote = fileSystem()
        val entries = listFolder(remote, chapter.url)

        entries
            .filter { !it.isDirectory && !it.name.startsWith('.') && ImageUtil.isImage(it.name) }
            .sortedWith { e1, e2 -> e1.name.compareToCaseInsensitiveNaturalOrder(e2.name) }
            .mapIndexed { index, entry ->
                Page(index = index, url = entry.path, imageUrl = entry.path)
            }
    }

    // 取图：整条链路的唯一替换点

    /**
     * 阅读器（`HttpPageLoader`）和下载器（`Downloader`）都只通过这个函数取图。
     *
     * [Page.url] 就是图片在库里的相对路径，所以这里把远端那份字节**直接**包成响应体：
     * 不落本地缓存、不做整话预下载。一页一请求，失败也只重试这一页，
     * 缓存交给阅读器 / Coil / 下载器各自的机制。
     *
     * 响应固定是 200，因此下载器不会走 HTTP 206 的断点续传分支（我们不做分片下载）。
     */
    override suspend fun getImage(page: Page, existingSize: Long): Response {
        val remote = fileSystem()

        val file = try {
            remote.open(page.url)
        } catch (e: RemoteAuthException) {
            throw IOException(context.stringResource(SYMR.strings.network_source_auth_failed), e)
        } catch (e: RemoteUnreachableException) {
            throw IOException(
                context.stringResource(
                    SYMR.strings.network_source_connection_failed,
                    e.cause?.message ?: e.message.orEmpty(),
                ),
                e,
            )
        }

        return Response.Builder()
            .request(syntheticRequest())
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_OK)
            .message("OK")
            .body(
                RemoteResponseBody(
                    // 关响应体时把流关掉，okio 会一路把连接释放掉
                    input = file.stream,
                    // WebDAV 的 GET 一般带 Content-Length，有真实长度时进度显示更准
                    length = file.contentLength,
                    mediaType = guessMediaType(page.url) ?: file.contentType?.toMediaTypeOrNull(),
                ),
            )
            .build()
    }

    /**
     * 合成响应必须带一个 request，但上层不会读它，给个合法 URL 占位即可。
     */
    private fun syntheticRequest(): Request =
        runCatching { Request.Builder().url(preferences.rootUrl).build() }
            .getOrElse { Request.Builder().url(FALLBACK_URL).build() }

    private fun guessMediaType(path: String): MediaType? = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "jxl" -> "image/jxl"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        else -> null
    }?.toMediaTypeOrNull()

    // 扫描

    /**
     * 根目录下的一个「漫画文件夹」的扫描结果。
     *
     * [fallbackTitle] 是根索引里记的漫画名 —— 漫画自己的 `config.json` 坏了或还没写时，
     * 至少还能用索引里那个名字显示，而不是甩一个随机文件夹名给用户。
     */
    private class MangaFolder(
        val dir: RemoteEntry,
        val config: MangaConfig?,
        val entries: List<RemoteEntry>,
        val coverUrl: String?,
        val fallbackTitle: String? = null,
    ) {
        val title: String get() = config?.displayName ?: fallbackTitle ?: dir.name

        fun toSManga(): SManga = SManga.create().apply {
            title = this@MangaFolder.title
            url = dir.path
            thumbnail_url = coverUrl
        }
    }

    /**
     * 按根索引扫描各漫画文件夹。
     *
     * 每个文件夹至少一次 PROPFIND + 一次读 config.json，串行扫几十本会很难看，
     * 所以这里限流并发。单个文件夹出错只跳过它，不让整个列表挂掉。
     *
     * **索引里有、目录里没有**的条目（用户手动删了目录、或索引写坏了）直接跳过，
     * 而不是造一个读不出内容的假条目。
     */
    private suspend fun scanIndexedFolders(
        remote: RemoteFileSystem,
        index: LibraryIndex,
        dirsByName: Map<String, RemoteEntry>,
    ): List<MangaFolder> = coroutineScope {
        val gate = Semaphore(SCAN_CONCURRENCY)
        index.mangas
            .mapNotNull { it.folderName to it.displayName }
            .distinctBy { it.first }
            .map { (folderName, indexName) ->
                async {
                    gate.withPermit {
                        ignoringFailure("scan $folderName") {
                            dirsByName[folderName]?.let { dir -> scanFolder(remote, dir, indexName) }
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun scanFolder(
        remote: RemoteFileSystem,
        dir: RemoteEntry,
        fallbackTitle: String?,
    ): MangaFolder {
        val entries = listFolder(remote, dir.path)
        val config = readConfig(remote, entries)

        return MangaFolder(
            dir = dir,
            config = config,
            entries = entries,
            coverUrl = coverUrlOf(remote, dir.path, config, entries),
            fallbackTitle = fallbackTitle,
        )
    }

    /** 列出目录下的非隐藏子项；列不动就返回空表（单点失败不该炸掉整个列表）。 */
    private suspend fun listFolder(remote: RemoteFileSystem, path: String): List<RemoteEntry> =
        ignoringFailure("list $path") {
            remote.list(path).filter { !it.name.startsWith('.') }
        }.orEmpty()

    /** 读 `config.json`：先按「路径 + 大小 + 修改时间」查进程内记忆，没有再拉。 */
    private suspend fun readConfig(remote: RemoteFileSystem, entries: List<RemoteEntry>): MangaConfig? {
        val entry = entries.firstOrNull { !it.isDirectory && it.name.equals(CONFIG_FILE_NAME, ignoreCase = true) }
            ?: return null

        val cacheKey = "${entry.path}|${entry.size}|${entry.lastModified}"
        configCache[cacheKey]?.let { return it }

        val config = ignoringFailure("read ${entry.path}") {
            val text = remote.open(entry.path).let { file ->
                file.stream.use { it.readBytes() }.decodeToString()
            }
            ConfigJson.decodeFromString(MangaConfig.serializer(), text)
        }

        if (config != null) {
            if (configCache.size >= MAX_MEMO_ENTRIES) configCache.clear()
            configCache[cacheKey] = config
        }
        return config
    }

    /**
     * 读根目录的库索引 `config.json`（[LibraryIndex]）。
     *
     * 和 [readConfig] 一样按「路径 + 大小 + 修改时间」做进程内记忆 —— 书架的每次刷新、
     * 每次搜索都要用它，不缓存的话每次都要多一次 GET。
     * 读不到（根目录还没建、文件被删）就返回空索引：这时列表是空的，而不是把整个图源判死。
     */
    private suspend fun readIndex(remote: RemoteFileSystem, rootEntries: List<RemoteEntry>): LibraryIndex {
        val entry = rootEntries
            .firstOrNull { !it.isDirectory && it.name.equals(CONFIG_FILE_NAME, ignoreCase = true) }
            ?: return LibraryIndex()

        val cacheKey = "${entry.path}|${entry.size}|${entry.lastModified}"
        indexCache[cacheKey]?.let { return it }

        val index = ignoringFailure("read ${entry.path}") {
            val text = remote.open(entry.path).let { file ->
                file.stream.use { it.readBytes() }.decodeToString()
            }
            ConfigJson.decodeFromString(LibraryIndex.serializer(), text)
        } ?: return LibraryIndex()

        if (indexCache.size >= MAX_MEMO_ENTRIES) indexCache.clear()
        indexCache[cacheKey] = index
        return index
    }

    /**
     * 封面地址：`config.cover` 指的文件；没写就退回漫画文件夹里的第一张图片。
     *
     * 必须落本地再交给 Coil —— 加载 `thumbnail_url` 时不带图源的 Basic Auth，
     * 直接把远端地址交出去只会得到 401。
     */
    private suspend fun coverUrlOf(
        remote: RemoteFileSystem,
        folderPath: String,
        config: MangaConfig?,
        entries: List<RemoteEntry>,
    ): String? {
        val entry = config?.coverPath
            ?.let { resolveEntry(remote, folderPath, entries, it) }
            ?: entries.filter { !it.isDirectory && ImageUtil.isImage(it.name) }.minByOrNull { it.name }
            ?: return null

        return ignoringFailure("cache cover ${entry.path}") {
            Uri.fromFile(assetCache.obtain(remote, entry, LocalAssetCache.BUCKET_COVERS)).toString()
        }
    }

    /**
     * 把配置里写的相对路径解析成实际的远端条目。
     *
     * 路径不带 `/` 是常态（封面就在漫画文件夹里），这时先在已列出的子项里找，省一次请求；
     * 带 `/` 才去列它的父目录。
     */
    private suspend fun resolveEntry(
        remote: RemoteFileSystem,
        folderPath: String,
        knownEntries: List<RemoteEntry>,
        relativePath: String,
    ): RemoteEntry? {
        val clean = relativePath.trim().trimStart('/').replace('\\', '/')
        if (clean.isEmpty()) return null

        val parent = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')

        if (parent.isEmpty()) {
            knownEntries
                .firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }
                ?.let { return it }
        }

        val dir = if (parent.isEmpty()) folderPath else "$folderPath/$parent"
        return ignoringFailure("list $dir") {
            remote.list(dir).firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }
        }
    }

    // 地址

    override fun getMangaUrl(manga: SManga): String = fileSystem().urlOf(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = fileSystem().urlOf(chapter.url)

    // 设置

    /**
     * 图源自己的设置页，由项目已有的 `SourcePreferencesScreen` 渲染（浏览图源页右上角「设置」）。
     *
     * 这里**只有连接相关的项**：协议 / 服务器地址（完整 URL，端口写在里面）/ 用户名 / 密码 /
     * 测试连接 / 下载项目自动上传 / 不通过软件代理。
     * 地址的写法与「数据与存储」里的 WebDAV 同步一致（那个也叫「服务器地址」）。
     * 根路径不在这里，也不可配置 —— 它固定为 `TachiyomiX manga`（见
     * [NetworkSourcePreferences.ROOT_DIRECTORY]），因为目录结构是图源自己约定的。
     *
     * ## 为什么这里用 `screen.context`，而不是本图源的 [context]
     *
     * 本图源拿到的 `context` 是 **Application 级**的（`AppModule` 里是
     * `AndroidSourceManager(app, ...)`）。Application 的 `getTheme()` 给的是系统默认主题，
     * **不包含** `SourcePreferencesScreen` 套上去的 `preferenceTheme`。
     *
     * 而 `DialogPreference` / `EditTextPreference` 构造时走的是
     * `TypedArrayUtils.getAttr(context, R.attr.editTextPreferenceStyle, android.R.attr.editTextPreferenceStyle)`：
     * 先找 androidx 的属性，**找不到就回退到 framework 的同名属性**。
     * 用 Application 的 context 就必然回退，于是对话框套上框架样式
     * `Preference.Material.DialogPreference.EditTextPreference`，
     * 其 `android:dialogLayout` 指向 `@android:layout/preference_dialog_edittext_material`，
     * 而 **Android 16 上那个框架布局里只有一个 TextView，没有 EditText** ——
     * 于是点开任意一项都会抛
     * `IllegalStateException: Dialog view must contain an EditText with id @android:id/edit`。
     *
     * `screen.context` 是 `SourcePreferencesFragment` 通过
     * `preferenceManager.createPreferenceScreen(requireContext())` 传进来的、
     * 带着 `preferenceTheme` 的 `ContextThemeWrapper`，用它构造才能解析到 androidx 的样式。
     *
     * ## 再加一道保险
     *
     * 每个 `EditTextPreference` 都显式钉住
     * [androidx.preference.DialogPreference.setDialogLayoutResource]，
     * 指向本模块自带的 `network_source_dialog_edittext`。这样即便将来主题或 Context 再出意外，
     * 对话框布局里也一定有 `android:id/edit`。
     *
     * 协议可选 WebDAV / FTP，两者的目录约定与读写路径完全一致，只是传输层不同。
     * 注意这个下拉框只是「地址里没写 scheme 时补哪一个」；地址里一旦写了 `ftp://`
     * 或 `http://`，就以地址里的为准（见 [NetworkSourcePreferences.Location.protocol]）。
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context
        screen.addPreference(
            ListPreference(ctx).apply {
                key = NetworkSourcePreferences.KEY_PROTOCOL
                title = context.stringResource(SYMR.strings.network_source_protocol)
                entries = arrayOf<CharSequence>(
                    context.stringResource(SYMR.strings.network_source_protocol_webdav),
                    context.stringResource(SYMR.strings.network_source_protocol_ftp),
                )
                entryValues = arrayOf<CharSequence>(
                    RemoteProtocol.WEBDAV.name,
                    RemoteProtocol.FTP.name,
                )
                setDefaultValue(RemoteProtocol.WEBDAV.name)
                summary = "%s"
            },
        )
        screen.addPreference(
            EditTextPreference(ctx).apply {
                key = NetworkSourcePreferences.KEY_SERVER_URL
                dialogLayoutResource = R.layout.network_source_dialog_edittext
                title = context.stringResource(SYMR.strings.network_source_server_url)
                dialogTitle = title
                dialogMessage = context.stringResource(SYMR.strings.network_source_server_url_summery)
            },
        )
        // 服务器地址是**完整 URL**（端口写在里面），所以既没有单独的端口项，也没有 HTTPS 开关。
        // 与「数据与存储」里的 WebDAV 同步保持一致。
        screen.addPreference(
            EditTextPreference(ctx).apply {
                key = NetworkSourcePreferences.KEY_USERNAME
                dialogLayoutResource = R.layout.network_source_dialog_edittext
                title = context.stringResource(SYMR.strings.network_source_username)
                dialogTitle = title
            },
        )
        screen.addPreference(
            EditTextPreference(ctx).apply {
                key = NetworkSourcePreferences.KEY_PASSWORD
                dialogLayoutResource = R.layout.network_source_dialog_edittext
                title = context.stringResource(SYMR.strings.network_source_password)
                dialogTitle = title
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            },
        )
        // 「测试连接」：点一下真的去服务器 MKCOL + PROPFIND 一次，结论写在条目摘要上。
        // 网络动作不能跑在主线程（点击回调就在主线程），所以开个一次性的 IO 协程，
        // 结果回主线程更新 summary —— 结论会留在界面上，直到下次测试，
        // 比弹一个转瞬即逝的 Toast 更有用。测试期间把这一行禁用，避免连点。
        screen.addPreference(
            RefreshablePreference(ctx).apply {
                key = NetworkSourcePreferences.KEY_TEST_CONNECTION
                title = context.stringResource(SYMR.strings.network_source_test_connection)
                summary = context.stringResource(SYMR.strings.network_source_test_connection_summery)
                setOnPreferenceClickListener {
                    isEnabled = false
                    summary = context.stringResource(SYMR.strings.network_source_test_running)
                    refresh()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        val result = testConnection()
                        withContext(Dispatchers.Main) {
                            summary = testResultText(result)
                            isEnabled = true
                            refresh()
                        }
                    }
                    true
                }
            },
        )
        // 「下载项目自动上传」：开启后书架「下载」分类里下载完毕的漫画会自动传到本图源的库。
        // 实际动作在 app 模块的 `UploadManager.autoUploadFinishedItems` 里，
        // 它通过 `NetworkSource.isAutoUploadEnabled()` 读这个开关。
        screen.addPreference(
            SwitchPreferenceCompat(ctx).apply {
                key = NetworkSourcePreferences.KEY_AUTO_UPLOAD
                title = context.stringResource(SYMR.strings.network_source_auto_upload)
                summary = context.stringResource(SYMR.strings.network_source_auto_upload_summery)
                setDefaultValue(false)
            },
        )
        // 「不通过软件代理」，默认开。局域网 WebDAV 走不通外网代理节点，
        // 被内置 Clash / 手动 HTTP 代理接管后只会 502，所以默认直连，见 `client`。
        screen.addPreference(
            SwitchPreferenceCompat(ctx).apply {
                key = NetworkSourcePreferences.KEY_BYPASS_PROXY
                title = context.stringResource(SYMR.strings.network_source_bypass_proxy)
                summary = context.stringResource(SYMR.strings.network_source_bypass_proxy_summery)
                setDefaultValue(true)
            },
        )
        // 「清除 WebDAV 内容」：把库目录（`TachiyomiX manga`）连内容一起删掉。
        // 这是**破坏性**操作，所以必须先弹二次确认；确认后结论写回 summary
        //（和「测试连接」同一套做法：结论留在界面上，比一闪而过的 Toast 有用）。
        screen.addPreference(
            RefreshablePreference(ctx).apply {
                key = NetworkSourcePreferences.KEY_CLEAR_LIBRARY
                title = context.stringResource(SYMR.strings.pref_clear_webdav)
                summary = context.stringResource(SYMR.strings.pref_clear_webdav_summary)
                setOnPreferenceClickListener {
                    // 用带 preferenceTheme 的 ctx 构对话框，理由见本方法开头那段注释
                    AlertDialog.Builder(ctx)
                        .setTitle(context.stringResource(SYMR.strings.pref_clear_webdav_confirm_title))
                        .setMessage(context.stringResource(SYMR.strings.pref_clear_webdav_confirm_message))
                        .setPositiveButton(context.stringResource(SYMR.strings.action_delete)) { _, _ ->
                            isEnabled = false
                            summary = context.stringResource(SYMR.strings.network_source_test_running)
                            refresh()
                            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                                val result = clearLibrary()
                                withContext(Dispatchers.Main) {
                                    summary = clearResultText(result)
                                    isEnabled = true
                                    refresh()
                                }
                            }
                        }
                        .setNegativeButton(context.stringResource(MR.strings.action_cancel), null)
                        .show()
                    true
                }
            },
        )
    }

    /** 把 [ClearLibraryResult] 翻成设置页里那一行摘要。 */
    private fun clearResultText(result: ClearLibraryResult): String = when (result) {
        ClearLibraryResult.NotConfigured -> context.stringResource(SYMR.strings.network_source_not_configured)
        is ClearLibraryResult.Ok ->
            context.stringResource(SYMR.strings.pref_clear_webdav_done, result.deleted)

        is ClearLibraryResult.Failed ->
            context.stringResource(SYMR.strings.pref_clear_webdav_failed, result.message)
    }

    /**
     * 把 [ConnectionTestResult] 翻成设置页里那一行摘要。
     *
     * 每一档都要能指向「下一步该改什么」：没填地址 / 改账号密码 / 查服务端或网络 /
     * 已经通了（库里有几本 / 还没建库）。
     */
    private fun testResultText(result: ConnectionTestResult): String = when (result) {
        ConnectionTestResult.NotConfigured -> context.stringResource(SYMR.strings.network_source_not_configured)
        ConnectionTestResult.AuthFailed -> context.stringResource(SYMR.strings.network_source_auth_failed)
        is ConnectionTestResult.Failed ->
            context.stringResource(SYMR.strings.network_source_connection_failed, result.message)

        is ConnectionTestResult.Ok -> {
            val size = result.librarySize
            if (size == null || size <= 0) {
                context.stringResource(SYMR.strings.network_source_test_ok_empty)
            } else {
                context.stringResource(SYMR.strings.network_source_test_ok, size)
            }
        }
    }

    // 内部

    private fun fileSystem(): RemoteFileSystem {
        check(preferences.isConfigured) {
            context.stringResource(SYMR.strings.network_source_not_configured)
        }
        return preferences.newFileSystem(client)
    }

    /**
     * 供 app 模块的上传逻辑读「下载项目自动上传」开关。
     *
     * `source-network` 不能反过来依赖 app，所以开关的读取入口放在图源这一侧，
     * 由 `UploadManager.autoUploadFinishedItems` 调用。
     */
    fun isAutoUploadEnabled(): Boolean = preferences.autoUpload

    /**
     * 服务器信息填好了没有。
     *
     * 供 app 侧判断上传入口能不能用（`UploadManager.isUploadAvailable()`）：
     * 没配置时上传按钮置灰，而不是让用户点下去再收一条「尚未配置」的失败通知。
     */
    fun isConfigured(): Boolean = preferences.isConfigured

    /**
     * 连通性测试，供图源设置页的「测试连接」用。
     *
     * 测的是**上传真正要用的那条链路**，而不只是「能不能 ping 通」：
     *
     * 1. 先用配置里的主机/账号密码去 `MKCOL` 库根目录 —— 这一步同时验证了
     *    地址可达、认证、**以及写权限**（上传要 PUT/MKCOL，只读的挂载点点到为止）。
     *    库根目录不存在时会被顺手建出来，这也正是首次上传要做的事。
     * 2. 再 `PROPFIND` 列一次根目录，确认读也没问题。
     * 3. 最后尽力读一下根索引，把「库里有几本漫画」报给用户 —— 读不到不算失败
     *    （还没建库、或索引文件被删了），此时返回「库还没有建立」。
     *
     * 这个动作是**只读判断 + 幂等建目录**，不会碰任何已有内容。
     */
    suspend fun testConnection(): ConnectionTestResult = withIOContext {
        // 结论和**实际用到的库根地址**都写进日志。这个理由很实际：设置页那一行摘要
        // 不在无障碍树里（`PreferenceFragmentCompat` 的行 uiautomator 抓不到），
        // 出问题时只能靠 `<存储根>/logs/` 里的日志 —— 地址拼错这种事故看一眼 rootUrl 就露馅。
        logcat(LogPriority.INFO) {
            "network source: connection test start, root='${preferences.rootUrl}'"
        }

        val result = if (!preferences.isConfigured) {
            ConnectionTestResult.NotConfigured
        } else {
            // 用和读取/上传同一个 client（含「不通过软件代理」开关），否则测出来的结论不适用于真实链路
            val remote = preferences.newFileSystem(client)
            try {
                remote.makeDirectory("")
                remote.list("")
                ConnectionTestResult.Ok(librarySize = readIndexSizeOrNull(remote))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteAuthException) {
                ConnectionTestResult.AuthFailed
            } catch (e: RemoteUnreachableException) {
                ConnectionTestResult.Failed(e.describe())
            } catch (e: Exception) {
                ConnectionTestResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

        logcat(LogPriority.INFO) { "network source: connection test -> $result" }
        result
    }

    /**
     * 库里有几条索引记录；读不到返回 null（还没建库 / 索引坏了，都不影响连通性结论）。
     */
    private suspend fun readIndexSizeOrNull(remote: RemoteFileSystem): Int? = ignoringFailure("test read index") {
        val text = remote.open(CONFIG_FILE_NAME).let { file ->
            file.stream.use { it.readBytes() }.decodeToString()
        }
        ConfigJson.decodeFromString(LibraryIndex.serializer(), text).mangas.size
    }

    /**
     * 「清除 WebDAV 内容」：把整个库目录（`TachiyomiX manga`）连内容一起删掉。
     *
     * 供图源设置页那一项调用。删掉之后**不重建**根目录 —— 下一次上传会自己
     * `ensureRoot()`（`MKCOL` 幂等），留着空目录反而会让「测试连接」显示成
     * 「库还在但没东西」，容易误解成没删干净。
     *
     * 走的是和上传同一条链路（[fileSystem] → [client]，含「不通过软件代理」开关），
     * 否则内网服务器会因为代理连不上。
     */
    suspend fun clearLibrary(): ClearLibraryResult = withIOContext {
        if (!preferences.isConfigured) return@withIOContext ClearLibraryResult.NotConfigured

        val remote = preferences.newFileSystem(client)
        // 先数一下删之前有多少条目：光回一句「已清除」用户没法确认到底动了什么
        val count = ignoringFailure("count before clear") { remote.list("").size } ?: 0

        logcat(LogPriority.INFO) {
            "network source: clear library start, root='${preferences.rootUrl}', items=$count"
        }

        try {
            remote.delete("")
            logcat(LogPriority.INFO) { "network source: clear library -> deleted $count items" }
            ClearLibraryResult.Ok(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteAuthException) {
            ClearLibraryResult.Failed(context.stringResource(SYMR.strings.network_source_auth_failed))
        } catch (e: RemoteUnreachableException) {
            ClearLibraryResult.Failed(e.describe())
        } catch (e: Exception) {
            ClearLibraryResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 取用户看得懂的那部分错误信息（HTTP 状态码在 cause 的 message 里）。 */
    private fun RemoteUnreachableException.describe(): String =
        (cause?.message ?: message)?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    /**
     * 给上传逻辑用的读写客户端。
     *
     * 图源自己内部读库用的是 [fileSystem]，但上传要写（PUT/MKCOL），
     * 语义上属于「库级操作」，所以包一层 [NetworkLibraryClient]。
     * 没配置好时 [fileSystem] 会抛异常，由调用方（`UploadManager`）转成「未配置」提示。
     */
    fun newLibraryClient(): NetworkLibraryClient = NetworkLibraryClient(fileSystem())

    /**
     * 单个漫画出问题（服务端偶发 5xx、某个文件读不动）不该让整个列表挂掉，
     * 所以这里吞掉异常返回 null，但保留日志。
     *
     * `CancellationException` 必须原样抛出，否则会吞掉协程取消。
     */
    private inline fun <T> ignoringFailure(tag: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "network source: $tag failed" }
            null
        }

    // 取图：远端流 → 合成的响应体

    /**
     * 把远端那份流直接当响应体，避免整页读进内存。
     * 关闭它会经 `okio` 一路传到底层连接，所以连接能被回收复用。
     */
    private class RemoteResponseBody(
        private val input: InputStream,
        private val length: Long,
        private val mediaType: MediaType?,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = length

        override fun source(): BufferedSource = input.source().buffer()
    }

    companion object {
        /**
         * 本地图源用的是 `0L`。生成出来的扩展图源 ID 是 MD5 派生的 63 位大数，
         * 所以这种小常量不会有冲突风险。
         */
        const val ID = 1L

        private const val HTTP_OK = 200

        private const val FALLBACK_URL = "http://localhost/"

        /** 列表扫描的并发上限：每个漫画文件夹至少要一次 PROPFIND，串行会很难看。 */
        private const val SCAN_CONCURRENCY = 6

        /** config.json / 压缩包条目的进程内记忆上限，超了直接清空重来。 */
        private const val MAX_MEMO_ENTRIES = 512

    }
}

/**
 * 「测试连接」那一行用的 Preference。
 *
 * 需要一个能从**外部**刷新界面的方法：`Preference.notifyChanged()` 是 `protected`，
 * 从 `apply { }` / 点击回调这些外部 lambda 里直接调会被编译器拦下
 * （`Cannot access 'fun notifyChanged()': it is protected`）。
 * 而改完 `summary` 不 notify 的话，要等这一行下次重新绑定才会变 ——
 * 测试的结论就看不见了，所以用一个私有子类把它露出来。
 */
private class RefreshablePreference(context: Context) : Preference(context) {
    fun refresh() = notifyChanged()
}

/**
 * [NetworkSource.testConnection] 的结果。
 *
 * 分成这几档是为了给用户**能照着改**的结论：没填地址 / 地址或密码不对 / 服务器连不上 /
 * 通了但库还没建 / 通了且库里有 N 本。文案由设置页负责组织（图源这一侧只管判定）。
 */
sealed interface ConnectionTestResult {
    /** 服务器地址还没填。 */
    data object NotConfigured : ConnectionTestResult

    /** 401/403：地址通了，但用户名或密码不对。 */
    data object AuthFailed : ConnectionTestResult

    /** 连不上，或服务端返回了错误状态码。 */
    data class Failed(val message: String) : ConnectionTestResult

    /**
     * 连接成功。
     *
     * @property librarySize 库里已有多少本漫画；`null` 表示根索引还读不到
     *   （还没建库，或索引文件被删了）—— 这不影响「连接成功」这个结论。
     */
    data class Ok(val librarySize: Int?) : ConnectionTestResult
}

/**
 * 「清除 WebDAV 内容」的结果。文案由设置页组装。
 */
sealed interface ClearLibraryResult {
    /** 服务器地址还没填。 */
    data object NotConfigured : ClearLibraryResult

    /** 删掉了多少个顶层条目（漫画文件夹 / 索引文件）。 */
    data class Ok(val deleted: Int) : ClearLibraryResult

    /** 没删掉，附带原因（认证失败 / 连不上 / 服务端报错）。 */
    data class Failed(val message: String) : ClearLibraryResult
}
