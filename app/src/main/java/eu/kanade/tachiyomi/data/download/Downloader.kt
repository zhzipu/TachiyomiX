package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.saveTo
import exh.source.isEhBasedSource
import exh.util.DataSaver
import exh.util.DataSaver.Companion.getImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.common.archive.ZipWriter
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download.
 */
@OptIn(DelicateCoroutinesApi::class)
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val xml: XML = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    // SY -->
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // SY <--
) {

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = DownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        launchNow {
            val chapters = async { store.restore() }
            addAllToQueue(chapters.await())
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false

        DownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = combine(
                queueState,
                downloadPreferences.parallelSourceLimit.changes(),
            ) { a, b -> a to b }.transformLatest { (queue, parallelCount) ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter { it.status.value <= Download.State.DOWNLOADING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(parallelCount)
                        .map { (_, downloads) -> downloads.first() }
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(Download::statusFlow)) { states ->
                            states.contains(Download.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }
                .distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Download, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchDownloadJob(download: Download) = launchIO {
        try {
            downloadChapter(download)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            if (areAllDownloadsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            stop()
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
        if (chapters.isEmpty()) return

        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val wasEmpty = queueState.value.isEmpty()
        val chaptersToQueue = chapters.asSequence()
            // Filter out those already downloaded.
            .filter {
                provider.findChapterDir(
                    it.name,
                    it.scanlator,
                    it.url,
                    /* SY --> */ manga.ogTitle, /* SY <-- */
                    source,
                ) == null
            }
            // Add chapters to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { chapter -> queueState.value.none { it.chapter.id == chapter.id } }
            // Create a download for each one.
            .map { Download(source, manga, it) }
            .toList()

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(
                            MR.strings.download_queue_size_warning,
                            context.stringResource(MR.strings.app_name),
                        ),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                DownloadJob.start(context)
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: Download) {
        val mangaDir =
            provider.getMangaDir(/* SY --> */ download.manga.ogTitle /* SY <-- */, download.source).getOrElse { e ->
                download.status = Download.State.ERROR
                notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
                return
            }

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(
                context.stringResource(MR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val chapterDirname = provider.getChapterDirName(
            download.chapter.name,
            download.chapter.scanlator,
            download.chapter.url,
        )
        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = download.source.getPageList(download.chapter.toSChapter())

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
                download.pages = reIndexedPages
                reIndexedPages
            }

            val dataSaver = if (sourcePreferences.dataSaverDownloader.get()) {
                DataSaver(download.source, sourcePreferences)
            } else {
                DataSaver.NoOp
            }

            download.status = Download.State.DOWNLOADING

            // Start downloading images, consider we can have downloaded images already
            pageList.asFlow().flatMapMerge(concurrency = downloadPreferences.parallelPageLimit.get()) { page ->
                flow {
                    // Fetch image URL if necessary
                    if (page.imageUrl.isNullOrEmpty()) {
                        page.status = Page.State.LoadPage
                        try {
                            page.imageUrl = download.source.getImageUrl(page)
                        } catch (e: Throwable) {
                            page.status = Page.State.Error(e)
                        }
                    }

                    withIOContext { getOrDownloadImage(page, download, tmpDir, dataSaver) }
                    emit(page)
                }
                    .flowOn(Dispatchers.IO)
            }
                .collect {
                    // Do when page is downloaded.
                    notifier.onProgressChange(download)
                }

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = Download.State.ERROR
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )

            // Only rename the directory if it's downloaded
            if (downloadPreferences.saveChaptersAsCBZ.get()) {
                archiveChapter(mangaDir, chapterDirname, tmpDir, download.pages.orEmpty())
            } else {
                tmpDir.renameTo(chapterDirname)
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = Download.State.DOWNLOADED

            // SY -->
            // 完成提示：用户要求「下载完成时在底部提示 XXX 完成下载」，带上漫画名与章节名。
            // Toast 必须在主线程 show（后台线程建 Toast 会走没有 Looper 的线程），
            // 这里本来就在 IO 协程里，所以切一下。
            withContext(Dispatchers.Main) {
                context.toast(
                    context.stringResource(
                        SYMR.strings.download_toast_completed,
                        download.manga.title,
                        download.chapter.name,
                    ),
                )
            }
            // SY <--
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(page: Page, download: Download, tmpDir: UniFile, dataSaver: DataSaver) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)

        // Try to find the image file
        // SY -->
        // 按**页码**匹配（见 pageNumberOf），而不是按 `001.` 前缀：存储位置是 SAF 目录时，
        // 提供方会在重名时把文件自动改成 `001 (1).jpg`，那种名字按前缀永远匹配不上，
        // 于是每次续传都当「这一页没下过」重下一遍，副本越滚越多、完成判定也跟着失败。
        val existingFiles = tmpDir.listFiles().orEmpty()
            .filter { it.name?.let(::pageNumberOf) == page.number }
        // 多余副本就地删掉：留着既会让「这一话下完了没有」的判定一直不通过，
        // 也会在阅读和打包 CBZ 时变成重复的页
        existingFiles.drop(1).forEach { it.delete() }
        val imageFile = existingFiles.firstOrNull()
        // SY <--

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(page.imageUrl!!) ->
                    copyImageFromCache(chapterCache.getImageFile(page.imageUrl!!), tmpDir, filename)

                else -> downloadImage(page, download.source, tmpDir, filename, dataSaver)
            }

            // When the page is ready, set page path, progress (just in case) and status
            splitTallImageIfNeeded(page, tmpDir)

            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.Error(e)
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(
        page: Page,
        source: HttpSource,
        tmpDir: UniFile,
        filename: String,
        dataSaver: DataSaver,
    ): UniFile {
        page.status = Page.State.DownloadImage
        page.progress = 0
        return flow {
            val file = tmpDir.findFile("$filename.tmp")
                ?: tmpDir.createFile("$filename.tmp")!!

            try {
                source.getImage(page, dataSaver = dataSaver).use {
                    it.body.source().saveTo(
                        // If the server supports partial downloads (HTTP 206),
                        // append to the existing file.
                        // Otherwise, start from scratch and overwrite the file.
                        stream = file.openOutputStream(it.code == 206),
                    )
                    val extension = getImageExtension(it, file)
                    // SY -->
                    // 先删掉同名的旧文件再改名。SAF 存储下「改名到一个已存在的名字」会被提供方
                    // 悄悄改成 `001 (1).jpg`，那种名字后续既认不出是第几页（见 pageNumberOf），
                    // 又会被算进「目录里有几个文件」，把整话的完成判定拖挂。
                    tmpDir.findFile("$filename.$extension")?.delete()
                    // SY <--
                    file.renameTo("$filename.$extension")
                }
            } catch (e: HttpException) {
                if (e.code == 416) {
                    file.delete()
                }
                throw e
            }
            emit(file)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()).seconds)
                    if (source.isEhBasedSource()) {
                        page.imageUrl = source.getImageUrl(page)
                    }
                    true
                } else {
                    false
                }
            }
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        // Delete temp file if it exists
        tmpDir.findFile("$filename.tmp")?.delete()
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        // SY --> 同 downloadImage：先删同名旧文件，避免提供方把这次改名变成 `001 (1).jpg`
        tmpDir.findFile("$filename.${extension.extension}")?.delete()
        // SY <--
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    private fun splitTallImageIfNeeded(page: Page, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages.get()) return

        try {
            val filenamePrefix = "%03d".format(Locale.ENGLISH, page.number)
            // SY -->
            val files = tmpDir.listFiles().orEmpty()
            // 一个都列不出来时直接跳过：这条路径拿不到文件说明是存储层的问题，
            // 不在这里刷错误日志（那种情况由 isDownloadSuccessful 统一处理）
            if (files.isEmpty()) return

            // 同样按**页码**匹配（见 pageNumberOf）：按 `001` 前缀能匹配到一堆东西
            //（`001 (1).jpg` 副本、`001__002.jpg` 分割片段），挑错文件就白分割了
            val imageFile = files.firstOrNull { it.name?.let(::pageNumberOf) == page.number }
                ?: error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().contains("__")) return
            // SY <--

            ImageUtil.splitTallImage(
                tmpDir,
                imageFile,
                filenamePrefix,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false

        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }

        // Ensure that the chapter folder has all the pages
        // SY -->
        // **不再靠列目录验证文件是否齐全**。原因（本机实测过）：`UniFile.listFiles()` 走的是
        // 它内部的 `DocumentsContractApi21.listFilesNamed`，那里把查询异常**整个吞掉**、
        // 返回空数组；在这台机器的 SAF 目录上它就一直返回空。于是「目录里的文件数 == 页数」
        // 这种判断会把一话已经下好的漫画判死 —— 进度条显示 23/23、状态却是失败；
        // 而且一旦判失败，下次续传又会重下一遍并留下 `001 (1).jpg` 副本，越修越坏。
        //
        // 页状态是可信的：每一页的 Ready 都在文件确实写成功之后才置上（见 getOrDownloadImage）。
        // 列目录的结果只当线索写进日志，不再参与判定。
        val listedFiles = tmpDir.listFiles().orEmpty().mapNotNull { it.name }
        if (listedFiles.isEmpty()) {
            logcat(LogPriority.WARN) {
                "download: '$tmpDir' lists nothing (SAF listing unreliable on this device), " +
                    "trusting page states (ready=${download.downloadedImages}/$downloadPageCount)"
            }
        } else {
            logcat(LogPriority.INFO) {
                "download: '${download.chapter.name}' listed=${listedFiles.size} files, " +
                    "distinctPages=${listedFiles.mapNotNull { pageNumberOf(it) }.toSet().size}/$downloadPageCount"
            }
        }
        return true
        // SY <--
    }

    // SY -->
    /**
     * 从文件名里取出页码；不是页面图片（`.tmp` / `.nomedia` / `comic_info.json` …）时返回 null。
     *
     * 之所以不按「`001.` 前缀」匹配，是因为磁盘上的名字可能被外部改过：
     *
     * - 存储位置是 SAF 目录时，**重名**的文件会被提供方自动加上 ` (1)` 后缀
     *   （`001 (1).jpg`）—— 按前缀匹配就再也认不出它是第 1 页；
     * - 长图被分割后是 `001__001.jpg`、`001__002.jpg`…，同一页的多个片段都算第 1 页。
     *
     * 这两种都应当归到对应页码上，否则「这一页本地有没有」会判断错。
     */
    private fun pageNumberOf(fileName: String): Int? {
        if (fileName.endsWith(".tmp")) return null
        val base = fileName.substringBefore(" (").substringBefore('.')
        if (base.isEmpty()) return null
        return base.substringBefore("__").toIntOrNull()
    }
    // SY <--

    /**
     * Archive the chapter pages as a CBZ.
     *
     * SY -->
     * **不靠列目录打包**：`UniFile.listFiles()` 在部分设备的 SAF 目录上会静默返回空
     * （见 [isDownloadSuccessful] 的注释）。照它枚举就会打出一个 0 条目的空包，
     * 然后还把已经下好的图片删掉 —— 本机就是这么丢过一次数据。改成按**页**取文件：
     * 每一页的文件在下载时就记在 [Page.uri] 上，顺序也正好是包内顺序；
     * 长图被分割过的页原图会被删掉，这时用 [filesOfPage] 去找它的分割片段。
     *
     * @param pages 这一话的页，顺序即包内顺序
     * @throws IllegalStateException 一个文件都没打进去 —— 宁可判失败、把临时目录留着，
     *   也不要落一个空包（空包在阅读器里就是「一话零页」）
     * SY <--
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
        pages: List<Page>,
    ) {
        // SY -->
        val encrypt = CbzCrypto.getPasswordProtectDlPref() && CbzCrypto.isPasswordSet()
        // SY <--

        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        var packed = 0
        try {
            ZipWriter(context, zip, /* SY --> */ encrypt /* SY <-- */).use { writer ->
                pages.forEach { page ->
                    filesOfPage(page, tmpDir).forEach { file ->
                        try {
                            writer.write(file)
                            packed++
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "archive: cannot pack '${file.name}'" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            zip.delete()
            throw e
        }

        if (packed == 0) {
            zip.delete()
            error("archiveChapter: no page files found for '$dirname'")
        }

        zip.renameTo("$dirname.cbz")
        tmpDir.delete()
    }

    // SY -->
    /**
     * 一页在磁盘上的文件：正常情况下是原图；被长图分割过的话原图已经被删掉，
     * 只剩 `${前缀}__001.jpg`、`${前缀}__002.jpg`…
     *
     * 这里用 [UniFile.findFile] 逐个探片段，而不是列目录：设备存储上 `findFile` 走的是
     * 「按文件名拼 document id」的快路径（不查子项列表），在这台机器上它是**可靠**的 ——
     * 也正是它让「建目录 / 建文件」一直正常。
     */
    private fun filesOfPage(page: Page, tmpDir: UniFile): List<UniFile> {
        val uri = page.uri ?: return emptyList()
        val fileName = uri.pathSegments.lastOrNull()?.substringAfterLast('/') ?: return emptyList()

        UniFile.fromUri(context, uri)?.takeIf { it.exists() }?.let { return listOf(it) }

        // 原图不在 → 应该是被分割删掉的，按 `前缀__序号.扩展名` 连续探
        val prefix = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "jpg")
        val parts = mutableListOf<UniFile>()
        var index = 1
        while (true) {
            val part = tmpDir.findFile("%s__%03d.%s".format(Locale.ENGLISH, prefix, index, extension))
                ?: break
            parts += part
            index++
        }
        return parts
    }
    // SY <--

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = getCategories.await(manga.id).map { it.name.trim() }.takeUnless { it.isEmpty() }
        val urls = getTracks.await(manga.id)
            .mapNotNull { track ->
                track.remoteUrl.takeUnless { url -> url.isBlank() }?.trim()
            }
            .plus(source.getChapterUrl(chapter.toSChapter()).trim())
            .distinct()

        val comicInfo = getComicInfo(
            manga,
            chapter,
            urls,
            categories,
            source.name,
        )

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
