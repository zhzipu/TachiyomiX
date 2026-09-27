package eu.kanade.tachiyomi.data.upload

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.common.archive.archiveReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.sy.SYMR
import tachiyomi.source.network.NetworkSource
import tachiyomi.source.network.config.ChapterConfig
import tachiyomi.source.network.config.MangaConfig
import tachiyomi.source.network.io.RemoteEntry
import tachiyomi.source.network.library.NetworkLibraryClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream

/**
 * 把本地已下载的漫画上传到网络图源的 WebDAV 库（一页一个文件，见 `uploadChapterPages`）。
 *
 * ## 数据流
 *
 * ```
 * 书架「下载」分类          ──┐
 * 多选 → 上传按钮            ──┼──→ UploadManager.enqueue ──→ 串行 worker ──→ WebDAV
 * 下载完毕 + 自动上传开关打开  ──┘
 * ```
 *
 * ## 为什么是串行的
 *
 * 上传是长任务，目的是把本地内容同步到远端库，并发没有任何收益，
 * 反而容易把家用 WebDAV 服务器打爆。所以队列就是一个**有序列表 + 单个消费者**，
 * 列表可以由用户在上传队列页里重排 / 撤销 / 暂停（见 [queueState]）。
 *
 * ## 冲突处理
 *
 * 服务器上已经有同名漫画时：
 * - 手动上传 → 弹窗问「合并（只补缺失章节）」还是「新建文件夹」（[pendingDecision]）；
 *   弹窗超时（[CONFLICT_TIMEOUT_MS]）按取消处理，避免没人回答时把单消费者队列堵死
 * - 自动上传 → 不弹窗，**默认合并**。自动流程里没人能回答弹窗，而每次新建
 *   都会留下一份重复副本，合并是唯一不会造成破坏的选择。
 *
 * ## 生命周期
 *
 * 上传跑在 App 进程自己的 [scope] 里（[UploadManager] 在 `AppModule` 里注册为单例，
 * 启动时就会被创建）。进程被系统杀掉时长任务会中断，这一点和下载一致 ——
 * 下载靠 `DownloadJob` + WorkManager 兜住前台服务，上传目前不做，
 * 因为它的进度只需要在书架上体现（见 [LibraryMangaProgress]）。
 *
 * ## 退出后保留、启动不自动跑
 *
 * 队列会落盘（[UploadQueueStore]）：退出前队列里有哪几话、什么顺序，下次启动还原回来，
 * 用户点进上传队列页就能看见。但**启动时一律是暂停态**（见 [restoreQueue]）——
 * 需求是「保留进度，但不自动上传」，所以恢复之后必须等用户自己点「继续」。
 * 自动上传盯的是「下载刚跑完」和「开关刚打开」两个时机（见 [startAutoUploadWatcher]），
 * 启动时都不成立，所以也不会凭空开跑。
 */
class UploadManager(
    private val context: Context,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val downloadCategory: DownloadCategory = Injekt.get(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val notifier = UploadNotifier(context)

    /** 队列落盘。退出后被还原的队列就靠它。 */
    private val queueStore = UploadQueueStore(context)

    private val _state = MutableStateFlow<Map<Long, UploadState>>(emptyMap())

    /** 每本漫画的上传状态，键是 manga id。书架拿它画上传进度条。 */
    val state: StateFlow<Map<Long, UploadState>> = _state.asStateFlow()

    private val _pendingDecision = MutableStateFlow<UploadPendingDecision?>(null)

    /** 非空表示正在等用户回答「合并还是新建」，界面据此弹窗。 */
    val pendingDecision: StateFlow<UploadPendingDecision?> = _pendingDecision.asStateFlow()

    private var decision: CompletableDeferred<UploadChoice>? = null

    // SY -->
    /**
     * 等待上传的任务，**有序**，粒度是**一话**。顺序就是用户在上传队列页里拖出来的顺序。
     *
     * 这里刻意不用 `Channel`：`Channel` 只能按入队顺序取，没法重排、没法把还没开始的任务
     * 撤回来（取消）、也没法在排队时「暂停」。上传队列页要的正是这四件事，所以队列状态
     * 必须是一份可以直接读写的列表，worker 只是它的消费者。
     */
    private val _queue = MutableStateFlow<List<UploadTask>>(emptyList())

    /** 上传队列（队伍里等着的，不含正在传的那一话）。上传队列页据此画列表。 */
    val queueState: StateFlow<List<UploadTask>> = _queue.asStateFlow()

    /**
     * worker 当前正在跑的那一话。
     *
     * `queueState` 里不包含它 —— 它在被取走的那一刻就已经从队列里摘掉了。
     * 界面把 [currentTask] 和 `queueState` 拼起来展示，顺序是「当前话在最前」。
     */
    private val _current = MutableStateFlow<UploadTask?>(null)
    val currentTask: StateFlow<UploadTask?> = _current.asStateFlow()

    /** 当前正在上传的那一话的 job —— 「全部取消」要能立刻把它打断。 */
    private var currentJob: Job? = null

    /**
     * 整本上传的收尾状态，键是 manga id。
     *
     * 一话传完不代表这一本传完了：`config.json`（章节清单）和封面要等**这一本的
     * 所有话都处理完**才写一次，否则每话都重写一遍配置，既慢又容易写坏。
     * 收尾也要知道「本批次总共几话、已经处理了几话、各自落在哪个章节文件夹」，
     * 所以收尾所需的东西集中放在这里，由最后一话的任务触发。
     */
    private val batchStates = mutableMapOf<Long, MangaBatchState>()
    private val batchLock = Mutex()

    /**
     * 上传是否在跑。
     *
     * `true`：worker 会继续从队列里取下一个任务。
     * `false`：worker 取到任务后会**等在门口**（见 [init] 里的 worker 循环），
     * 已经在传的那一话不打断。
     *
     * 为什么暂停不打断当前话：一页就是一个 PUT，中止点随时可能落在一次请求中间，
     * 留下半个文件；而且本地已完成的部分下次会重传（没有断点续传），除了更慢没有好处。
     * 所以暂停的语义是「不再开始新的」。（「全部取消」是例外 —— 用户明确要求立即中断，
     * 那条路径用 [cancelCurrentJob]。）
     *
     * ## 为什么初值是 `true` 而启动后又是暂停的
     *
     * 需求是「**退出后队列保留、下次启动不自动跑**」，但同一个进程里新入队的任务必须
     * 立刻能跑（用户点了「上传」却不开始，那是坏了）。这两件事不矛盾：
     * [restoreQueue] 只把**从磁盘恢复出来的那批**单独按暂停处理，见那里的实现。
     * 这里的初值仍是 `true`，所以一次全新的入队不受影响。
     */
    private val _isUploaderRunning = MutableStateFlow(true)
    val isUploaderRunning: StateFlow<Boolean> = _isUploaderRunning.asStateFlow()

    /** 已在队列里或正在上传的**漫画** id，用来避免同一本被排两遍。 */
    private val inFlight = mutableSetOf<Long>()
    private val inFlightLock = Mutex()

    /** 队首取出 / 插回 / 重排都在这个锁里做，避免和 worker、界面同时改。 */
    private val queueLock = Mutex()
    // SY <--

    @Volatile
    private var autoWatcherStarted = false

    /**
     * 从磁盘恢复出来的那批任务是否还在「等用户点继续」。
     *
     * 需求：退出后队列保留，**下次启动不自动开跑**。但 [_isUploaderRunning] 不能一上来就
     * 置 false —— 那样同一进程里用户新点的「上传」也不会开始。
     * 所以恢复出来的任务单独用这个标记挡住：worker 取队首时若发现队首是「恢复来的」，
     * 就等用户点继续（[startUploads] 会把它翻成 false 唤醒）。
     *
     * 只在启动的 [restoreQueue] 里置 true；用户点一次「继续」之后它就永久为 false 了 ——
     * 恢复队列是**一次性**的事，之后入队的任务都是用户当前意图，不该再被挡。
     */
    @Volatile
    private var restoredQueuePendingResume = false

    init {
        // SY -->
        // 单消费者的 worker：一次只传一话。取到任务后先看要不要暂停 —— 要暂停就等在门口，
        // 任务原样留在手里（不塞回队列，否则后面的任务会被抢先，顺序就乱了）。
        scope.launch {
            while (true) {
                val task = queueLock.withLock { _queue.value.firstOrNull() }
                if (task == null) {
                    // 队列是列表不是 Channel，没有「阻塞等下一个」可用，只能轮询。
                    // 半秒一次的空转完全可以忽略，换来的是队列可以随意重排 / 撤销。
                    delay(QUEUE_POLL_MS)
                    continue
                }

                // 恢复出来的队列：启动时一律不动，等用户点「继续」（见 restoredQueuePendingResume）
                if (restoredQueuePendingResume) {
                    delay(QUEUE_POLL_MS)
                    continue
                }

                // 暂停时等在门口：任务仍然留在手里，取走它就已经从 _queue 里摘掉了，
                // 如果这时塞回去，后来的任务会排到它前面 —— 顺序就乱了。
                _isUploaderRunning.first { it }

                // 等它这段时间里可能已经被用户取消 / 整队清空了，要重新确认一次
                val stillQueued = queueLock.withLock {
                    if (_queue.value.any { it.chapterId == task.chapterId }) {
                        _queue.update { list -> list.filterNot { it.chapterId == task.chapterId } }
                        true
                    } else {
                        false
                    }
                }
                if (!stillQueued) {
                    releaseIfLastOfManga(task.mangaId)
                    continue
                }

                _current.value = task
                val job = scope.launch {
                    try {
                        upload(task)
                    } catch (e: CancellationException) {
                        // 被「全部取消」打断：这一话标成取消，然后让出去，别把 worker 也带走
                        markChapterCancelled(task)
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e)
                        failChapter(task, e.message)
                    } finally {
                        // 不管成功失败，都要看看这一本是不是已经收尾完了
                        val done = releaseIfLastOfManga(task.mangaId)
                        if (done) {
                            runCatching { finalizeManga(task.mangaId) }
                                .onFailure { logcat(LogPriority.ERROR, it) }
                        }
                        // 这一话已经离开队列了 → 把队列现状落盘。
                        // 挂在 finally 上是因为**正常传完、失败、被取消**三种结局都该记一次，
                        // 否则用户传了 10 话之后退出，磁盘上还是最早的 10 话（重开又全排一遍）。
                        persistQueue()
                    }
                }
                currentJob = job
                try {
                    job.join()
                } finally {
                    currentJob = null
                    _current.value = null
                }
            }
        }
        // SY <--
        restoreQueue()
        startAutoUploadWatcher()
    }

    /**
     * 还原上次退出时的上传队列。
     *
     * 恢复出来的任务**全部保持暂停**：需求是「保留了进度，但不要自动开始跑」，
     * 用户点「继续」之前 worker 不会取任务（见 [isUploaderRunning]）。
     *
     * 只恢复**本地还下得到**的那些话 —— 上下文里说得很清楚：队列里的话被用户
     * 从「下载」里删掉了，就不该再传上去。用户的下载被清掉之后，`localChapters`
     * 里不会再有它，正好用同一个判据。
     */
    private fun restoreQueue() {
        scope.launch {
            val stored = queueStore.read()
            if (stored.isEmpty()) return@launch

            val restored = mutableListOf<UploadTask>()

            stored.groupBy { it.mangaId }.forEach { (mangaId, tasks) ->
                val manga = getManga.await(mangaId) ?: return@forEach
                val localIds = localChapters(manga).mapTo(mutableSetOf()) { it.id }
                val chapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
                    .associateBy { it.id }

                val keep = tasks.filter { it.chapterId in localIds }
                // 本地还下得到、且真的数得出页数的话才留下（数不出页数的传上去也是空的）
                val withPages = keep.mapNotNull { task ->
                    val chapter = chapters[task.chapterId] ?: return@mapNotNull null
                    countChapterPages(manga, chapter)
                        .takeIf { it > 0 }
                        ?.let { task.copy(chapterName = chapter.name, chapterNumber = chapter.chapterNumber) to it }
                }

                // 这本的话本地都没了（比如用户退出期间把下载删了）→ 整本跳过。
                // 注意**不要**在这里写盘：`_queue` 最后会被整体覆盖成 `restored` 并写一次，
                // 中途写会把别的漫画的条目抹掉。
                if (withPages.isEmpty()) return@forEach

                restored += withPages.map { it.first }

                val askOnConflict = keep.first().askOnConflict
                batchLock.withLock {
                    batchStates[mangaId] = MangaBatchState(
                        mangaTitle = manga.title,
                        askOnConflict = askOnConflict,
                        total = withPages.size,
                        pagesTotal = withPages.sumOf { it.second },
                        remaining = withPages.size,
                    )
                }
                inFlightLock.withLock { inFlight.add(mangaId) }

                updateState(
                    UploadState(
                        mangaId = mangaId,
                        mangaTitle = manga.title,
                        status = UploadStatus.QUEUED,
                        total = withPages.size,
                        pagesTotal = withPages.sumOf { it.second },
                        chapters = withPages.associate { (task, pages) ->
                            task.chapterId to UploadChapterState(
                                chapterId = task.chapterId,
                                chapterName = task.chapterName,
                                chapterNumber = task.chapterNumber,
                                status = UploadStatus.QUEUED,
                                pagesTotal = pages,
                            )
                        },
                    ),
                )
            }

            // 先把闸门拉上，**再**把恢复出来的任务放进队列。
            // 顺序不能颠倒：worker 每 500ms 轮询一次，先放队列的话它可能在这两条语句之间
            // 就取走队首开始上传了 —— 那正是需求要避免的「启动自动上传」。
            if (restored.isNotEmpty()) {
                restoredQueuePendingResume = true
            }
            queueLock.withLock { _queue.value = restored }
            persistQueue()
        }
    }

    /** 把当前队列写回磁盘（队列每次变动后都要调一次）。 */
    private fun persistQueue() {
        scope.launch {
            val snapshot = queueLock.withLock { _queue.value }
            queueStore.write(snapshot)
        }
    }

    // 对外接口

    /**
     * 排队上传。
     *
     * 幂等：已经在队列里或正在上传的漫画会被跳过，所以重复点「上传」不会重复传。
     *
     * **同一本漫画可以被多次调用** —— 第二次调用不会重排已有的任务，只会把
     * 「本地已下载、但还没进这一批」的话**追加**到队尾。这是「一话下完就传」需要的：
     * 一本漫画下了 10 话，前 3 话先传着，第 4 话下完时 `enqueue` 再进来一次，
     * 把第 4 话接上，而不是等整本传完再从头排一遍。
     *
     * @param askOnConflict 服务器上已有同名漫画时是否弹窗询问（手动上传 true，自动上传 false）
     */
    fun enqueue(mangaIds: Collection<Long>, askOnConflict: Boolean = true) {
        if (mangaIds.isEmpty()) return

        scope.launch {
            mangaIds.forEach { mangaId ->
                val alreadyTracked = inFlightLock.withLock { mangaId in inFlight }
                if (alreadyTracked) {
                    // 已经在传这一本了 → 只补「新下完、还没排上」的话
                    appendNewChapters(mangaId)
                    return@forEach
                }

                inFlightLock.withLock { inFlight.add(mangaId) }

                val manga = getManga.await(mangaId)
                if (manga == null) {
                    inFlightLock.withLock { inFlight.remove(mangaId) }
                    return@forEach
                }

                // 展开成「一话一个任务」。展开必须在入队时就做（而不是等 worker 取到再拆），
                // 否则用户点进上传队列页看到的是一行「整本」，取消 / 重排的粒度也就成了整本
                // —— 而需求要的是按话操作。
                val chapters = localChapters(manga)
                if (chapters.isEmpty()) {
                    inFlightLock.withLock { inFlight.remove(mangaId) }
                    updateState(
                        UploadState(
                            mangaId = mangaId,
                            mangaTitle = manga.title,
                            status = UploadStatus.ERROR,
                            message = context.stringResource(SYMR.strings.upload_no_downloaded_chapters),
                        ),
                    )
                    return@forEach
                }

                // 每话的页数也在这里数出来：队列页要立刻显示每话的分母，
                // 而且「这一话本地根本没内容」的话现在就该被剔除，不要让它在队列里空转一趟。
                val withPages = chapters.mapNotNull { chapter ->
                    val pages = countChapterPages(manga, chapter)
                    if (pages == 0) {
                        logcat(LogPriority.WARN) {
                            "upload: nothing to upload for '${chapter.name}', skipped at enqueue"
                        }
                        null
                    } else {
                        chapter to pages
                    }
                }
                if (withPages.isEmpty()) {
                    inFlightLock.withLock { inFlight.remove(mangaId) }
                    updateState(
                        UploadState(
                            mangaId = mangaId,
                            mangaTitle = manga.title,
                            status = UploadStatus.ERROR,
                            message = context.stringResource(SYMR.strings.upload_no_downloaded_chapters),
                        ),
                    )
                    return@forEach
                }

                // 本批次的收尾信息：这一本总共有几话、一共多少页。
                // 收尾（写 config.json + 封面 + 索引）由**最后一话**触发，见 finalizeManga。
                val totalPages = withPages.sumOf { it.second }
                batchLock.withLock {
                    batchStates[mangaId] = MangaBatchState(
                        mangaTitle = manga.title,
                        askOnConflict = askOnConflict,
                        total = withPages.size,
                        pagesTotal = totalPages,
                        remaining = withPages.size,
                    )
                }

                val chapterStates = withPages.associate { (chapter, pages) ->
                    chapter.id to UploadChapterState(
                        chapterId = chapter.id,
                        chapterName = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        status = UploadStatus.QUEUED,
                        pagesTotal = pages,
                    )
                }
                updateState(
                    UploadState(
                        mangaId = mangaId,
                        mangaTitle = manga.title,
                        status = UploadStatus.QUEUED,
                        total = withPages.size,
                        pagesTotal = totalPages,
                        chapters = chapterStates,
                    ),
                )

                val tasks = withPages.map { (chapter, _) ->
                    UploadTask(
                        mangaId = mangaId,
                        chapterId = chapter.id,
                        chapterName = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        askOnConflict = askOnConflict,
                    )
                }
                queueLock.withLock { _queue.update { it + tasks } }
                persistQueue()
            }
        }
    }

    /**
     * 往一本**已经在传**的漫画里补「新下完、还没排上」的话。
     *
     * 这是「一话下完就传」的关键：`enqueue` 第二次被同一本漫画调进来时走这里。
     * 只有满足下面全部条件的话才会被追加：
     * - 本地已下载完成（`localChapters`，`_tmp` 不算）
     * - **不在**下载队列里还没下完的那些之中（不然会把半截内容排上去，虽然
     *   `awaitChapterDownloaded` 会挡住，但队列里多一行「等下载」的噪音没意义）
     * - 既不在上传队列、也不在本批次的 `UploadState.chapters` 里
     *   （已经在的：排队中的、正在传的、传完的、取消的 —— 一律不重排）
     *
     * 追加后同步更新批次的 `total` / `pagesTotal` / `remaining`，否则
     * [releaseIfLastOfManga] 会在原来的话跑完时就误判「整本结束」并收尾。
     */
    private suspend fun appendNewChapters(mangaId: Long) {
        val batch = batchLock.withLock { batchStates[mangaId] } ?: return
        val manga = getManga.await(mangaId) ?: return

        val known = stateOf(mangaId)?.chapters.orEmpty().keys
        val queuedChapterIds = queueLock.withLock { _queue.value.mapTo(mutableSetOf()) { it.chapterId } }

        val newcomers = localChapters(manga)
            .filterNot { it.id in known || it.id in queuedChapterIds }
            // 页数数不出来（文件读不动 / 位置错了）的不上队：排上去也只是空转一趟，
            // 等下一次本地下完新的章节时再看。
            // 注意「下到一半」的话根本不会走到这里 —— `localChapters` 用的是
            // `isChapterDownloaded`，`_tmp` 目录不算，所以这里看到的都是**下载完毕**的话。
            .mapNotNull { chapter ->
                countChapterPages(manga, chapter).takeIf { it > 0 }?.let { chapter to it }
            }

        if (newcomers.isEmpty()) return

        // 状态不存在就什么都别做 —— 下面已经把 total / remaining 加上去了再返回的话，
        // 这一批的计数器就永远收不回零，收尾再也触发不了。
        val state = stateOf(mangaId) ?: return

        var newTotal = 0
        var newPagesTotal = 0
        batchLock.withLock {
            val current = batchStates[mangaId] ?: return
            current.total += newcomers.size
            current.pagesTotal += newcomers.sumOf { it.second }
            current.remaining += newcomers.size
            // 这一批之前可能已经收过尾了（第一话传完就写了 config.json、置了 COMPLETED）。
            // 现在补了新话进来，必须把「已收尾」的标记清掉，否则这一批跑完时
            // `finalizeManga` 会在开头直接 return，新话的章节文件夹就**永远写不进
            // config.json** —— 服务器上图片在、但图源读不到这一话。
            current.finalized = false
            newTotal = current.total
            newPagesTotal = current.pagesTotal
        }

        // 每话的状态先登记好，队列页和书架进度条才看得到它们
        val chapterStates = state.chapters + newcomers.associate { (chapter, pages) ->
            chapter.id to UploadChapterState(
                chapterId = chapter.id,
                chapterName = chapter.name,
                chapterNumber = chapter.chapterNumber,
                status = UploadStatus.QUEUED,
                pagesTotal = pages,
            )
        }
        updateState(
            state.copy(
                total = newTotal,
                pagesTotal = newPagesTotal,
                chapters = chapterStates,
            ),
        )

        val tasks = newcomers.map { (chapter, _) ->
            UploadTask(
                mangaId = mangaId,
                chapterId = chapter.id,
                chapterName = chapter.name,
                chapterNumber = chapter.chapterNumber,
                askOnConflict = batch.askOnConflict,
            )
        }
        queueLock.withLock { _queue.update { it + tasks } }
        persistQueue()
    }

    /** 开始 / 继续上传。 */
    fun startUploads() {
        // 恢复队列的「只此一次」闸门：用户点了继续就永久放开
        restoredQueuePendingResume = false
        _isUploaderRunning.value = true
    }

    /**
     * 暂停上传。
     *
     * 只是「不再开始新的一话」，正在传的那一话会传完 —— 见 [isUploaderRunning] 的说明。
     */
    fun pauseUploads() {
        _isUploaderRunning.value = false
    }

    /**
     * 清空队列：还没开始的全部撤下 **+ 立刻打断正在传的那一话**。
     *
     * 用户明确要求「全部取消」是硬到底的 —— 所以这里除清队列之外还会
     * `cancel()` 当前 job。代价是当前那一页 PUT 可能断在中间，服务器上留个半截文件；
     * 下一次上传会重传同一话并覆盖它，所以不会累积垃圾（远端没有 DELETE，
     * 但同一话的页码/文件名是稳定的）。
     *
     * 被撤下的条目状态置为 [UploadStatus.CANCELLED]，同时把进度通知收掉。
     */
    fun clearQueue() {
        scope.launch {
            val removed = queueLock.withLock {
                val current = _queue.value
                _queue.value = emptyList()
                current
            }
            removed.forEach { markChapterCancelled(it) }

            // 正在传的也要断掉
            _current.value?.let { markChapterCancelled(it) }
            currentJob?.cancel()

            // 这一本这一批的任务全没了 → 收尾信息也一起丢掉，
            // 否则下次再给这本入队时，残留的批次会被当成「同一批」。
            batchLock.withLock { batchStates.clear() }

            // 队列都被清空了，磁盘上那份也没有留着的意义
            persistQueue()

            if (removed.isNotEmpty() || _current.value != null) {
                notifier.dismissProgress()
            }
        }
    }

    /**
     * 按界面拖出来的顺序重排队列。
     *
     * 只认「现在还在队列里的」那些（正在传的那一话不在 `queueState` 里，拖不动它，
     * 也不该因为一次重排就把它插到队伍中间）—— 所以先按传入顺序收一遍，
     * 再补上漏掉的（理论上不会有，写了只是防止界面数据过期导致任务凭空消失）。
     */
    fun reorderQueue(tasks: List<UploadTask>) {
        scope.launch {
            queueLock.withLock {
                val queued = _queue.value.associateBy { it.chapterId }
                val ordered = tasks
                    .filter { it.chapterId in queued }
                    .distinctBy { it.chapterId }
                val missing = _queue.value.filterNot { task -> ordered.any { it.chapterId == task.chapterId } }
                _queue.value = ordered + missing
            }
            persistQueue()
        }
    }

    /**
     * 取消队列里的若干话（还没开始的）。正在传的那一话由「全部取消」负责。
     *
     * 某一本的话被取消之后，如果这一本**再也没有别的任务**了，就把它的批次清掉 ——
     * 否则那本漫画的 [UploadState] 会永远停在「排队中」，书架上的进度条也下不去。
     */
    fun cancelQueuedUploads(tasks: List<UploadTask>) {
        if (tasks.isEmpty()) return
        scope.launch {
            val ids = tasks.map { it.chapterId }.toSet()
            val removed = queueLock.withLock {
                val current = _queue.value
                _queue.value = current.filterNot { it.chapterId in ids }
                current.filter { it.chapterId in ids }
            }
            removed.forEach { markChapterCancelled(it) }
            removed.map { it.mangaId }.distinct().forEach { releaseIfLastOfManga(it) }
            persistQueue()
            if (removed.isNotEmpty()) {
                notifier.dismissProgress()
            }
        }
    }

    /** 用户在冲突弹窗里做出选择之后调用。 */
    fun resolveDecision(choice: UploadChoice) {
        _pendingDecision.value = null
        decision?.complete(choice)
        decision = null
    }

    /**
     * 这本书的下载被删掉了，把它的上传任务一并停掉、清掉。
     *
     * 需求：「正在上传和下载的任务，在下载中删除则自动停止上传并清空任务」。
     * 删除下载挂在 `DownloadManager.deleteChapters` / `deleteManga` 上，
     * 那两处调到这里。
     *
     * 三种要清的东西：
     * - **队列里还没跑的**：直接撤掉，标 CANCELLED
     * - **正在传的那一话**：`currentJob.cancel()` 硬打断（和「全部取消」同一套语义）
     * - **这一本的下载已经全没了**（`keepDownloaded = true`）→ 连上传状态一起清掉，
     *   否则书架上会留着一条「排队中」的幽灵进度条，点进去什么都没有
     *
     * @param keepDownloaded 删完之后这本漫画**在本地还有没有**已下载章节。
     *   `true`（还有别的章节下载着）时只清队列、保留状态；`false` 时状态也一并清掉。
     */
    fun cancelUploadsOfManga(mangaId: Long, keepDownloaded: Boolean) {
        scope.launch {
            val removed = queueLock.withLock {
                val current = _queue.value
                val mine = current.filter { it.mangaId == mangaId }
                _queue.value = current.filterNot { it.mangaId == mangaId }
                mine
            }
            removed.forEach { markChapterCancelled(it) }

            // 正在传的就是这本 → 立刻断掉
            if (_current.value?.mangaId == mangaId) {
                _current.value?.let { markChapterCancelled(it) }
                currentJob?.cancel()
            }

            batchLock.withLock { batchStates.remove(mangaId) }
            inFlightLock.withLock { inFlight.remove(mangaId) }
            persistQueue()

            if (!keepDownloaded) {
                // 本地什么都没有了，这条状态本身就没意义（书架的进度条靠它）
                _state.update { it - mangaId }
                notifier.dismissProgress()
            }
        }
    }

    // 自动上传

    /**
     * 盯住几个「该干活了」的时机：
     *
     * 1. **「下载」分类里已下载的章节数发生了变化** —— 说明刚有一话下完（或用户删了下载）。
     *    需求是「只要有章节下载完毕，上传就不要排队，优先上传已经完成下载的章节」，
     *    所以触发点从老版本的「整批下载跑完」提前到了「**任何一话**下完」。
     * 2. **下载器从「忙」变成「闲」** —— 兜底：整批刚结束，确保最后那几话也被排上
     *    （本地章节数在最后几话下完时已经变过，这一条主要是防漏）。
     * 3. **「下载项目自动上传」开关从关变开** —— 需求是「开启之后自动上传」，
     *    所以用户刚打开开关时要把**已经**下好的那批补传一次，否则得等到下一次
     *    下载结束才会动，看起来就像开关没生效。
     *
     * 之所以不挂在 `Downloader.stop()` 上，是为了避免 `DownloadManager` → `UploadManager`
     * 的反向依赖（[UploadManager] 本来就依赖 [DownloadManager]），
     * 观察流量是无环的，也不用改下载器的代码。
     *
     * 开关那一侧没有可订阅的 Flow（值存在图源自己的 SharedPreferences 里），
     * 只能按节拍采样；一次 map 查找 + 一次 boolean 读取，代价可以忽略。
     * **不要**改成 `OnSharedPreferenceChangeListener` —— SharedPreferences 是用
     * WeakHashMap 存监听器的，得自己强引用住，漏了就静默失效。
     */
    private fun startAutoUploadWatcher() {
        if (autoWatcherStarted) return
        autoWatcherStarted = true

        // SY -->
        // 「本地已下载章节总数」变了就试着补传一次。
        // 这是「一话下完就传」的主触发点：`getTotalDownloadCount()` 只读 `DownloadCache`
        // 的内存缓存（`renewCache` 自带节流），按 [LOCAL_DOWNLOAD_SAMPLE_MS] 采样一次
        // 的代价可以忽略；真正重的 `countChapterPages` 只在真的进了
        // `autoUploadFinishedItems` 之后才会跑到，而去重（`inFlight` + 状态判断）
        // 保证了同一本漫画不会反复入队。
        scope.launch {
            var lastCount = downloadManager.getDownloadCount()
            while (true) {
                delay(LOCAL_DOWNLOAD_SAMPLE_MS)
                val count = downloadManager.getDownloadCount()
                if (count == lastCount) continue
                // 增加才是「有章节下完了」；减少是用户删下载，那时下载侧自己会
                // 调 `cancelUploadsOfManga`，这里不必跟着排队。
                val grew = count > lastCount
                lastCount = count
                if (grew) {
                    runCatching { autoUploadFinishedItems() }
                        .onFailure { logcat(LogPriority.ERROR, it) }
                }
            }
        }
        // SY <--

        scope.launch {
            var wasBusy = false
            downloadsBusyFlow().collect { busy ->
                if (wasBusy && !busy) {
                    runCatching { autoUploadFinishedItems() }
                        .onFailure { logcat(LogPriority.ERROR, it) }
                }
                wasBusy = busy
            }
        }

        scope.launch {
            // 启动时按当前值初始化，避免每次启动都凭空跑一次上传
            var wasEnabled = isAutoUploadEnabled()
            while (true) {
                delay(AUTO_UPLOAD_TOGGLE_TICK_MS)
                val enabled = isAutoUploadEnabled()
                if (enabled == wasEnabled) continue
                wasEnabled = enabled
                if (enabled) {
                    runCatching { autoUploadFinishedItems() }
                        .onFailure { logcat(LogPriority.ERROR, it) }
                }
            }
        }
    }

    /** 读「下载项目自动上传」开关（在图源自己的设置里）。 */
    private fun isAutoUploadEnabled(): Boolean =
        (sourceManager.get(NetworkSource.ID) as? NetworkSource)?.isAutoUploadEnabled() == true

    private suspend fun autoUploadFinishedItems() {
        // 开关没打开就什么都不做 —— 这也是「网络图源设置」里那一项的落点
        if (!isAutoUploadEnabled()) return
        // 服务器信息还没填好时也别排队：排了只会给每本漫画发一条「尚未配置」的失败通知，
        // 和「上传按钮置灰」是同一个判断。
        if (!isUploadAvailable()) return

        val categoryId = downloadCategory.ensureId() ?: return

        val targets = getLibraryManga.await()
            .filter { categoryId in it.categories }
            .mapNotNull { libraryManga ->
                val downloaded = downloadManager.getDownloadCount(libraryManga.manga)
                if (downloaded == 0) return@mapNotNull null

                // 传完的不重复排队；但用户之后又下了新章节时，本地已下载数会超过
                // 上次上传的章数，这时要让它再排一次队 —— 否则一本漫画只会被自动传一次。
                val last = _state.value[libraryManga.manga.id]
                if (last?.status == UploadStatus.COMPLETED && last.uploaded >= downloaded) {
                    return@mapNotNull null
                }
                libraryManga.manga.id
            }

        if (targets.isNotEmpty()) {
            enqueue(targets, askOnConflict = false)
        }
    }

    // 上传流程

    /**
     * 上传入口现在能不能用：网络图源可解析 **且** 服务器信息已经填好。
     *
     * 书架「下载」分类的多选底栏据此把「上传」按钮置灰 —— 没配置的情况下点下去
     * 只会收到一条「尚未配置」的失败通知，不如直接不给点。
     */
    fun isUploadAvailable(): Boolean =
        (sourceManager.get(NetworkSource.ID) as? NetworkSource)?.isConfigured() == true

    /**
     * 「还有下载没跑完」的流量。
     *
     * **不能只看 `queueState` 是不是空的**：跑完的条目里只有成功的会被 `removeFromQueue`，
     * 失败（ERROR）的会一直挂在队列里等用户重试。所以「队列非空」≠「还在下载」，
     * 拿它当忙闲判据会让下载失败一次之后就永远等不到闲。
     * 判据与 `Downloader.areAllDownloadsFinished()` 保持一致：队列里还有
     * QUEUE/DOWNLOADING 的条目，或者前台下载任务还在跑。
     *
     * 只用于 [startAutoUploadWatcher] 判断「一批下载刚跑完」这个边沿，
     * **不再作为上传的前置条件** —— 见 [awaitChapterDownloaded]。
     */
    private fun downloadsBusyFlow(): Flow<Boolean> = combine(
        downloadManager.isDownloaderRunning,
        downloadManager.queueState,
    ) { running, queue ->
        running || queue.any { it.status.value <= Download.State.DOWNLOADING.value }
    }
        .distinctUntilChanged()

    /**
     * 这一话**自己的**下载还在跑吗？
     *
     * 判据有两条，任一成立就算「还没下完」：
     * - 下载队列里有这一话，且状态还没到终态（`status <= DOWNLOADING`，
     *   与 [downloadsBusyFlow] 同一套口径：ERROR 也是终态，不能再等下去），
     *   而且页还没下齐
     * - 本地还找不到这一话落好盘的目录（`isChapterDownloaded`，`_tmp` 不算）
     *
     * 第二条是兜底：第一条判「队列空了」，但队列空了也可能是下载失败 / 被用户删了，
     * 这时本地仍然没有内容，不该继续等 —— 由 [awaitChapterDownloaded] 的循环
     * 用「本地有没有」来做最终裁决，而不是靠队列状态。
     */
    private fun chapterDownloadedFlow(chapterId: Long): Flow<Boolean> = combine(
        downloadManager.queueState,
        downloadManager.isDownloaderRunning,
    ) { queue, _ ->
        queue.any {
            it.chapter.id == chapterId &&
                it.status.value <= Download.State.DOWNLOADING.value
        }
    }
        .distinctUntilChanged()

    /**
     * 挂起直到**这一话**下载完成（或确定等不到）。
     *
     * 与老版本的区别：老版本等的是「所有下载都结束」（单消费者上传队列会因此整条卡住，
     * 整批下载期间一话都传不上去）。现在是**一话一话地放行** ——
     * 哪一话下完了就轮到哪一话，剩下的下载继续在后台跑，互不阻塞。
     *
     * 裁决标准是**本地有没有这一话**（`isChapterDownloaded`），队列状态只用来
     * 决定「什么时候重新看一眼」：队列里这一话变成终态 / 消失时就再查一次。
     * 这样既不会漏掉刚下完的话，也不会在「下载失败」「用户删了下载」时死等 ——
     * 那两种情况下本地永远没有内容，[uploadChapter] 的 `pages == 0` 守卫会跳过它。
     *
     * 另加一层保险：等得太久（[CHAPTER_WAIT_MS]）就放行，避免意料之外的情况
     * 把单消费者的上传队列永久堵死。
     */
    private suspend fun awaitChapterDownloaded(task: UploadTask) {
        val chapter = getChaptersByMangaId.await(task.mangaId, applyScanlatorFilter = true)
            .firstOrNull { it.id == task.chapterId }
            ?: return

        withTimeoutOrNull(CHAPTER_WAIT_MS) {
            // 本地已经有了就立刻放行 —— 这是「这一话早就下完、用户在补传」的常见路径。
            // 否则等队列状态变化，每次变化后重新确认本地内容。
            chapterDownloadedFlow(task.chapterId)
                .first { !it || isChapterOnDisk(task.mangaId, chapter) }
        }
    }

    /** 这一话在本地落好盘了吗（`_tmp` 不算，与下载器的口径一致）。 */
    private suspend fun isChapterOnDisk(mangaId: Long, chapter: Chapter): Boolean {
        val manga = getManga.await(mangaId) ?: return false
        return downloadManager.isChapterDownloaded(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.ogTitle,
            sourceId = manga.source,
        )
    }

    private suspend fun upload(task: UploadTask) = withIOContext {
        awaitChapterDownloaded(task)
        uploadChapter(task)
    }

    /**
     * 上传**一话**。
     *
     * 与老版本的区别：这里只处理一话，`config.json` / 封面 / 索引都留给
     * [finalizeManga]（由这一本的最后一话触发）。这样一话的任务能独立取消、独立重排 ——
     * 否则「取消某一话」就得把整本的收尾也一并拆掉，根本做不到。
     */
    private suspend fun uploadChapter(task: UploadTask) {
        val manga = getManga.await(task.mangaId) ?: return

        val networkSource = sourceManager.get(NetworkSource.ID) as? NetworkSource
        if (networkSource == null) {
            failChapter(task, context.stringResource(SYMR.strings.upload_network_source_missing))
            return
        }

        val client = try {
            // 上传走的网络策略与读库完全一致：`newLibraryClient()` 内部是
            // `NetworkSource.fileSystem()` → `NetworkSource.client`，
            // 而那个 `client` 已经按「不通过软件代理」开关切成了直连客户端。
            // 换句话说，开了那个开关，上传也不会绕道内置 Clash / 手动 HTTP 代理。
            // 上传链路上只有这一处会访问网络（章节图片、封面都读本地文件），
            // 所以**不要**在这里另起 OkHttp，否则就把这个开关漏掉了。
            networkSource.newLibraryClient()
        } catch (e: Exception) {
            failChapter(task, context.stringResource(SYMR.strings.upload_not_configured))
            return
        }

        val chapter = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
            .firstOrNull { it.id == task.chapterId }
            ?: return

        // 服务器上已经有同名漫画了吗？决定「合并」还是「新建文件夹」。
        // 结果按本缓存（`resolveFolder`），同一本的后面几话不会再问一遍。
        client.ensureRoot()
        val folderPath = resolveFolder(task, manga, client) ?: return

        val chapterFolder = client.nextChapterFolderName(client.listChapterFolders(folderPath).map { it.name }.toSet())
        val pages = countChapterPages(manga, chapter)
        if (pages == 0) {
            markChapterSkipped(task)
            return
        }

        updateChapterState(task) {
            it.copy(status = UploadStatus.UPLOADING, pagesTotal = pages)
        }
        updateMangaStatus(task.mangaId, UploadStatus.UPLOADING)

        client.ensureFolder("$folderPath/$chapterFolder")
        val uploadedPages = uploadChapterPages(
            client = client,
            manga = manga,
            chapter = chapter,
            folderPath = folderPath,
            chapterFolder = chapterFolder,
            alreadyUploaded = 0,
            task = task,
        )
        if (uploadedPages == 0) {
            markChapterSkipped(task)
            return
        }

        // 记下这一话落在哪个章节文件夹 —— 收尾写 config.json 时要用它
        batchLock.withLock {
            batchStates[task.mangaId]?.uploadedChapters?.add(
                ChapterConfig(title = chapter.name, number = chapter.chapterNumber, folder = chapterFolder),
            )
        }

        updateChapterState(task) {
            it.copy(status = UploadStatus.COMPLETED, pagesUploaded = it.pagesTotal)
        }
        updateMangaStatus(task.mangaId, UploadStatus.UPLOADING)
    }

    /**
     * 一本漫画全部话都处理完之后收尾：写 `config.json`、传封面、登记根索引。
     *
     * 必须等整本的话都跑完再做，因为 config.json 是**整本的章节清单**，
     * 每话写一次会让文件反复被覆盖（还可能写坏）。
     */
    private suspend fun finalizeManga(mangaId: Long) {
        val batch = batchLock.withLock {
            if (batchStates[mangaId]?.finalized != false) return
            batchStates[mangaId]?.also { it.finalized = true }
        } ?: return

        val manga = getManga.await(mangaId) ?: return
        val networkSource = sourceManager.get(NetworkSource.ID) as? NetworkSource ?: return
        val client = runCatching { networkSource.newLibraryClient() }.getOrNull() ?: return
        val folderPath = batch.folderPath ?: return

        val allChapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)

        // config.json：保留服务器上原有的章节，补上这次的。
        // 没上传的章节也写进去：**只写章节名和章节号、不写 folder**。读取端会照样把它们
        // 列进章节列表（顺序按章节号），但置灰、点开提示「无数据」。
        // 放在 uploadedEntries **前面**：同一话如果这次真传上去了，会被后面的条目覆盖掉
        //（`mergeChapters` 里「有 folder 的赢」），于是拿到真实的章节文件夹。
        val existingConfig = client.readConfig(folderPath)
        val pendingEntries = allChapters.map { chapter ->
            ChapterConfig(title = chapter.name, number = chapter.chapterNumber)
        }
        val mergedChapters = mergeChapters(
            existingConfig?.chapters.orEmpty(),
            pendingEntries + batch.uploadedChapters.toList(),
        )

        // 封面：优先自定义封面，其次缓存里的封面；都没有就沿用服务器上原有的
        val cover = prepareCover(manga)
        val coverName = if (cover != null) {
            try {
                client.putCover(folderPath, cover.name, cover.file.length()) { cover.file.inputStream() }
                cover.name
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "upload: cover failed, keeping the existing one" }
                existingConfig?.coverPath
            }
        } else {
            existingConfig?.coverPath
        }

        client.writeConfig(
            folderPath,
            MangaConfig(
                name = manga.title,
                cover = coverName,
                // 作者 / 简介 / 标签：来源是本地书架里这本漫画的元数据。
                // 本地没有（源没提供、用户也没手填）时**保留服务器上原有的** ——
                // 那些可能是用户自己写进 config.json 的，不能被空值抹掉。
                author = manga.author?.trim()?.takeIf { it.isNotEmpty() } ?: existingConfig?.authorName,
                description = manga.description?.trim()?.takeIf { it.isNotEmpty() } ?: existingConfig?.synopsis,
                tags = manga.genre
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?.takeIf { it.isNotEmpty() }
                    ?: existingConfig?.allTags.orEmpty(),
                chapters = mergedChapters,
            ),
        )

        // 根索引登记。漫画文件夹是随机串，光看目录名认不出是哪本 —— 不登记的话
        // 下一次上传会以为服务器上没有这本漫画，再新建一份，库里就越攒越多。
        // 放在写 config 之后：万一中途挂了，宁可留一个「索引里没有」的空文件夹，
        // 也不要留一个「索引里有、点进去什么都没有」的坏条目。
        client.upsertIndexEntry(manga.title, folderPath)

        // 收尾：整本标完成，页数对齐分母
        val skipped = batch.skipped
        updateState(
            UploadState(
                mangaId = manga.id,
                mangaTitle = manga.title,
                status = UploadStatus.COMPLETED,
                uploaded = batch.uploadedChapters.size,
                total = batch.total,
                pagesUploaded = batch.pagesTotal,
                pagesTotal = batch.pagesTotal,
                folderPath = folderPath,
                chapters = stateOf(manga.id)?.chapters.orEmpty(),
            ),
        )
        notifier.onComplete(manga.title, batch.uploadedChapters.size, folderPath)

        if (skipped > 0) {
            logcat(LogPriority.WARN) {
                "upload: '${manga.title}' finished with $skipped/${batch.total} chapters skipped (no local content)"
            }
        }

        withContext(Dispatchers.Main) {
            val text = if (skipped > 0) {
                context.stringResource(
                    SYMR.strings.upload_toast_completed_skipped,
                    manga.title,
                    skipped.toString(),
                )
            } else {
                context.stringResource(SYMR.strings.upload_toast_completed, manga.title)
            }
            context.toast(text)
        }
    }

    /**
     * 解析「这次传到服务器上的哪个文件夹」，结果按本缓存。
     *
     * 同一本漫画的多个章节任务会先后调用它，但只能**问一次**冲突弹窗 ——
     * 否则用户回答完，下一话又来问一遍。
     */
    private suspend fun resolveFolder(
        task: UploadTask,
        manga: Manga,
        client: NetworkLibraryClient,
    ): String? {
        // 已经解析过就直接复用
        batchLock.withLock { batchStates[task.mangaId]?.folderPath }?.let { return it }

        val existing = client.findFolderByMangaName(manga.title)
        val folderPath = when {
            existing == null -> client.allocateFolderName()
            !task.askOnConflict -> existing.path
            else -> when (askConflict(manga, existing.name)) {
                UploadChoice.MERGE -> existing.path
                UploadChoice.NEW_FOLDER -> client.allocateFolderName()
                UploadChoice.CANCEL -> return null
            }
        }

        client.ensureFolder(folderPath)
        batchLock.withLock { batchStates[task.mangaId]?.folderPath = folderPath }
        return folderPath
    }

    /**
     * 一本漫画当前这一批上传任务的收尾信息。
     *
     * 一话一个任务，所以「这一本传完了没有」要在这里记账：[remaining] 归零时，
     * 取走它的那一话负责调 [finalizeManga] 写 config.json / 封面 / 索引。
     *
     * @property mangaTitle 漫画标题（通知文案用）
     * @property askOnConflict 这一批是否要弹冲突窗（同一本只问一次）
     * @property total 这一批总共几话（**可变**：一话下完就传，同一本会分几次补进来）
     * @property pagesTotal 这一批总共多少页（同样可变，见 [appendNewChapters]）
     * @property remaining 还剩几话没处理（每完成 / 跳过 / 取消一话减一）
     * @property folderPath 服务器上的漫画文件夹；还没解析出来时是 null
     * @property uploadedChapters 已经传上去的章节条目，收尾写 config.json 用
     * @property skipped 本地没内容被跳过的话数
     * @property finalized 收尾是否已经跑过（防止重复写 config）
     */
    private class MangaBatchState(
        val mangaTitle: String,
        val askOnConflict: Boolean,
        var total: Int,
        var pagesTotal: Int,
        var remaining: Int,
        var folderPath: String? = null,
        val uploadedChapters: MutableList<ChapterConfig> = mutableListOf(),
        var skipped: Int = 0,
        var finalized: Boolean = false,
    )

    /**
     * 这一本的话全都处理完了吗？是的话把批次从表里摘掉并返回 `true`。
     *
     * 同时顺手把 `inFlight` 去掉 —— 只有这样同一本漫画才能被再次排队
     * （用户又下了新章节时，`autoUploadFinishedItems` 靠这个重新放行）。
     */
    private suspend fun releaseIfLastOfManga(mangaId: Long): Boolean {
        val last = batchLock.withLock {
            val batch = batchStates[mangaId] ?: return@withLock false
            batch.remaining--
            if (batch.remaining <= 0) {
                batch
            } else {
                null
            }
        }
        if (last == null) return false

        // 还有话在队列里（被重排挤到后面了 / 后来又补了新话）就不算结束。
        //
        // **只查 `_queue`，不能查 `_current`**：worker 在 `finally` 里调这个方法时，
        // `_current.value` 就是刚跑完的这一话本身（第 246 行赋值、join 之后才清空），
        // 拿它当「还有话在等」的判据恒为 true → `remaining` 被永远重置为 1 →
        // 收尾（写 config.json + 登记索引 + 置 COMPLETED）永不触发。
        val stillPending = queueLock.withLock { _queue.value.any { it.mangaId == mangaId } }
        if (stillPending) {
            // 记回去，等最后一话真的跑完再说
            batchLock.withLock { batchStates[mangaId]?.remaining = 1 }
            return false
        }

        inFlightLock.withLock { inFlight.remove(mangaId) }
        return true
    }

    /** 这一话被跳过（本地没内容 / 读不出来）：标记状态并记账。 */
    private fun markChapterSkipped(task: UploadTask) {
        // 这里不加锁：`batchStates` 的读写只发生在 worker 那一条协程里
        //（`markChapterSkipped` 只由 worker 调），加锁反而要把它变成 suspend。
        batchStates[task.mangaId]?.let { it.skipped += 1 }
        updateChapterState(task) { it.copy(status = UploadStatus.CANCELLED, message = null) }
        notifier.dismissProgress()
    }

    /** 这一话被用户取消（清队列 / 单话取消 / 中断当前任务都走它）。 */
    private fun markChapterCancelled(task: UploadTask) {
        updateChapterState(task) { it.copy(status = UploadStatus.CANCELLED) }
    }

    /** 改某一话的状态，并把它所属漫画的汇总状态一起刷新。 */
    private fun updateChapterState(task: UploadTask, transform: (UploadChapterState) -> UploadChapterState) {
        val state = stateOf(task.mangaId) ?: return
        val chapter = state.chapters[task.chapterId] ?: return
        updateState(state.copy(chapters = state.chapters + (task.chapterId to transform(chapter))))
    }

    /** 刷新整本漫画的汇总状态（页数 / 话数从各话汇总回来）。 */
    private fun updateMangaStatus(mangaId: Long, status: UploadStatus) {
        val state = stateOf(mangaId) ?: return
        val done = state.chapters.values.count { it.status == UploadStatus.COMPLETED }
        val pagesDone = state.chapters.values.sumOf { it.pagesUploaded }
        updateState(state.copy(status = status, uploaded = done, pagesUploaded = pagesDone))

        val batch = batchStates[mangaId]
        if (batch != null) {
            val percent = if (batch.pagesTotal > 0) pagesDone * 100 / batch.pagesTotal else 0
            notifier.onProgress(state.mangaTitle, percent, done, batch.total)
        }
    }

    /** 让这一话失败（网络异常等）。 */
    private fun failChapter(task: UploadTask, message: String?) {
        updateChapterState(task) { it.copy(status = UploadStatus.ERROR, message = message) }
        updateMangaStatus(task.mangaId, UploadStatus.ERROR)
        notifier.onError(stateOf(task.mangaId)?.mangaTitle.orEmpty(), message)
    }

    /**
     * 挂起等待用户选择。
     *
     * 界面观察到 [pendingDecision] 之后弹窗，用户点了就调 [resolveDecision]，
     * 这里才继续往下走。手动上传是有人看着的，所以挂起是安全的。
     */
    private suspend fun askConflict(manga: Manga, remoteFolderName: String): UploadChoice {
        val pending = CompletableDeferred<UploadChoice>()
        decision = pending

        // 只改整本状态，**不要**重建 UploadState —— 那会把每话的状态（chapters）清掉，
        // 弹窗期间上传队列页上这一本的话就全消失了。
        stateOf(manga.id)?.let { current ->
            updateState(current.copy(status = UploadStatus.WAITING_CONFIRM, folderPath = remoteFolderName))
        }
        _pendingDecision.value = UploadPendingDecision(manga.id, manga.title, remoteFolderName)

        return try {
            // 上传 worker 是**单消费者**：一个永远没人回答的弹窗会把整条队列卡死，
            // 后面所有漫画都再也传不上去。所以必须有超时兜底，超时按「取消」处理。
            // （正常路径是用户点按钮 → resolveDecision。）
            val choice = withTimeoutOrNull(CONFLICT_TIMEOUT_MS) { pending.await() } ?: UploadChoice.CANCEL
            // 用户选了「取消」→ 这一本整批都不传了，把还没跑的从队列里撤掉，
            // 否则后面几话会各自去 resolveFolder 时又撞上同一个冲突。
            if (choice == UploadChoice.CANCEL) {
                dropQueuedChaptersOfManga(manga.id)
            }
            choice
        } finally {
            _pendingDecision.value = null
            // 只在还是自己那一份的时候清掉，别把后来者的 deferred 抹掉
            if (decision === pending) decision = null
        }
    }

    /** 把某本漫画还没跑的队列条目全部撤掉（用户取消了整批）。 */
    private suspend fun dropQueuedChaptersOfManga(mangaId: Long) {
        val removed = queueLock.withLock {
            val current = _queue.value
            _queue.value = current.filterNot { it.mangaId == mangaId }
            current.filter { it.mangaId == mangaId }
        }
        removed.forEach { markChapterCancelled(it) }
        batchLock.withLock { batchStates[mangaId]?.remaining = 1 }
        persistQueue()
    }

    // 打包

    /**
     * 定位一章在本地的落盘内容。
     *
     * 可能是压缩包文件（开了「保存为 CBZ」），也可能是图片目录 —— 两种形态
     * [packChapter] 与 [countChapterPages] 都要认，所以查找放在一处。
     */
    private fun findChapterContent(manga: Manga, chapter: Chapter): UniFile? {
        val source = sourceManager.getOrStub(manga.source)
        return downloadProvider.findChapterDir(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.ogTitle,
            source = source,
        )
    }

    /**
     * 数一章本地已下载内容里有多少页。
     *
     * 只数条目、不读内容：压缩包走阅读器/图源同一套 `archiveReader`（libarchive），
     * 图片目录走 `listFiles`。`ImageUtil.isImage` 对常见扩展名是直接返回的，
     * 不会为了判断类型把每个文件都打开一遍。
     *
     * 数不出来（文件被删、压缩包损坏、SAF 存放下 mmap 不了）返回 0 ——
     * 总页数会因此偏小甚至为 0，上层会退化成按章节数显示，不会卡住上传。
     */
    private fun countChapterPages(manga: Manga, chapter: Chapter): Int {
        val content = findChapterContent(manga, chapter) ?: return 0

        return if (content.isFile) {
            runCatching {
                content.archiveReader(context).use { reader ->
                    reader.useEntries { entries ->
                        entries.count {
                            it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! }
                        }
                    }
                }
            }.getOrDefault(0)
        } else {
            content.listFiles().orEmpty()
                .count { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
        }
    }

    /**
     * 把一章的已下载内容逐页传到服务器。
     *
     * 本地可能是两种形态，都要认（跟下载器的设置有关）：
     * - **图片目录**（默认）→ 每个图片文件一个 PUT，文件名原样沿用
     * - **CBZ 文件**（开了「保存为 CBZ」）→ 用 libarchive 逐个条目读出来再 PUT
     *
     * 服务器上**永远是图片目录**：一页一个请求，读的时候就能逐页取，不必先下整话。
     *
     * 页数进度是**精确**的（传完一页 +1，不是按字节折算的近似值）。
     *
     * @return 实际传上去的页数；0 表示这一章没内容可传（调用方跳过它）。
     */
    private suspend fun uploadChapterPages(
        client: NetworkLibraryClient,
        manga: Manga,
        chapter: Chapter,
        folderPath: String,
        chapterFolder: String,
        alreadyUploaded: Int,
        task: UploadTask,
    ): Int {
        val content = findChapterContent(manga, chapter) ?: return 0

        var uploaded = 0
        fun afterPage() {
            uploaded++
            // 这一话的页数进度。整本的汇总页数由各话累加得到
            //（`updateMangaStatus`），所以这里只改这一话。
            updateChapterState(task) { it.copy(pagesUploaded = alreadyUploaded + uploaded) }
            updateMangaStatus(task.mangaId, UploadStatus.UPLOADING)
        }

        if (content.isFile) {
            // 本地是压缩包：读条目 → 逐页 PUT。整章共用一个 reader（mmap 一次），
            // 每页读进内存再传（`getInputStream` 不给长度，而 PUT 带上真实长度更稳）。
            // 嵌套 `getInputStream` 做类型嗅探是项目里的既有用法，见 `ArchivePageLoader`。
            content.archiveReader(context).use { reader ->
                val names = reader.useEntries { entries ->
                    entries
                        .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .map { it.name }
                        .toList()
                        .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                }
                for (name in names) {
                    val bytes = reader.getInputStream(name)?.use { it.readBytes() } ?: continue
                    if (bytes.isEmpty()) continue
                    client.putChapterImage(
                        mangaFolderPath = folderPath,
                        chapterFolder = chapterFolder,
                        fileName = name.substringAfterLast('/'),
                        length = bytes.size.toLong(),
                        body = { bytes.inputStream() },
                    )
                    afterPage()
                }
            }
            return uploaded
        }

        val pages = content.listFiles().orEmpty()
            .filter { it.isFile && !it.name.isNullOrEmpty() && ImageUtil.isImage(it.name) { it.openInputStream() } }
            .sortedWith { a, b -> (a.name ?: "").compareToCaseInsensitiveNaturalOrder(b.name ?: "") }

        for (page in pages) {
            val name = page.name ?: continue
            val length = page.length()
            if (length <= 0L) continue
            client.putChapterImage(
                mangaFolderPath = folderPath,
                chapterFolder = chapterFolder,
                fileName = name,
                length = length,
                body = { page.openInputStream() },
            )
            afterPage()
        }
        return uploaded
    }

    private class PreparedCover(val file: File, val name: String)

    /**
     * 封面来源：自定义封面 > 缓存里的封面。
     *
     * 缓存里的封面文件名是 hash，没有扩展名，所以要用 [ImageUtil.findImageType]
     * 从文件头判断真实格式，给它补一个正确的扩展名 —— 否则 WebDAV 上会多出
     * 一个没有后缀、Coil 也认不出来的文件。
     */
    private fun prepareCover(manga: Manga): PreparedCover? {
        val file = sequenceOf(
            coverCache.getCustomCoverFile(manga.id),
            manga.thumbnailUrl?.let { coverCache.getCoverFile(it) },
        )
            .filterNotNull()
            .firstOrNull { it.exists() && it.length() > 0 }
            ?: return null

        val extension = runCatching {
            ImageUtil.findImageType { file.inputStream() }?.extension
        }.getOrNull() ?: "jpg"

        return PreparedCover(file, "cover.$extension")
    }

    // 小工具

    /**
     * 合并服务器上原有的章节与这次上传的章节。
     *
     * 合并模式下如果不带上原有的，`config.json` 一覆盖，服务器上那些
     * 不在这台设备上的章节就会从图源里消失。
     *
     * 去重键用**章节文件夹**（`No.0001`）：它是存放位置的唯一标识（一话的图片就在里面）。
     * 排序也按章节文件夹序号 —— 写出来的 config.json 就是 `No.0001, No.0002…` 的顺序，
     * 跟服务器上的目录排列一致，人看着舒服。
     */
    /**
     * 合并服务器上原有的章节条目与本次要写入的条目。
     *
     * 身份键用 [ChapterConfig.identity]（优先章节号）：同一话最稳定的标识，
     * 本机改名、换扫描组都不影响，所以不会因为标题变了就多出一条重复章节。
     *
     * 两条规则：
     * - **后写入的覆盖先写入的**：调用方把「未上传的占位条目」放在前面、本次真正传上去的
     *   放在后面，于是占位条目会被真实条目顶掉（拿到 `folder`）。
     * - **有 `folder` 的条目永远不会被没 `folder` 的顶掉**：服务器上真有内容的那一话，
     *   不能因为本机没有下载、只生成了一条占位，就把它的位置从 config 里抹掉 ——
     *   那会让**已经传上去的章节**在图源里变成「无数据」。
     */
    private fun mergeChapters(
        existing: List<ChapterConfig>,
        added: List<ChapterConfig>,
    ): List<ChapterConfig> {
        val byIdentity = LinkedHashMap<String, ChapterConfig>()

        fun put(config: ChapterConfig) {
            val key = config.identity ?: return
            val old = byIdentity[key]
            if (old?.chapterFolder != null && config.chapterFolder == null) return
            byIdentity[key] = config
        }

        existing.forEach(::put)
        added.forEach(::put)

        return byIdentity.values.sortedWith(
            compareBy(
                // 认不出章节号的排在最后（读取端本来也是这个规矩）
                { it.number ?: Double.MAX_VALUE },
                { it.displayTitle.orEmpty() },
            ),
        )
    }

    private fun stateOf(mangaId: Long): UploadState? = _state.value[mangaId]

    private fun updateState(newState: UploadState?) {
        if (newState == null) return
        _state.update { it + (newState.mangaId to newState) }
    }

    /**
     * 这本书**已经下载到本地**的章节，按章节号升序。
     *
     * 「有本地内容」的判据用 `DownloadManager.isChapterDownloaded`（和下载器一致），
     * 展开成任务、以及数页数都依赖它。
     */
    private suspend fun localChapters(manga: Manga): List<Chapter> =
        getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
            .filter { chapter ->
                downloadManager.isChapterDownloaded(
                    chapterName = chapter.name,
                    chapterScanlator = chapter.scanlator,
                    chapterUrl = chapter.url,
                    mangaTitle = manga.ogTitle,
                    sourceId = manga.source,
                )
            }
            .sortedBy { it.chapterNumber }

    /** 让这一本整本失败（整本级别的错误，如网络图源不可用 / 未配置）。 */
    private fun fail(mangaId: Long, message: String?) {
        val current = stateOf(mangaId)
            ?: UploadState(mangaId = mangaId, mangaTitle = "", status = UploadStatus.QUEUED)

        updateState(current.copy(status = UploadStatus.ERROR, message = message))
        notifier.onError(current.mangaTitle, message)
    }

    companion object {
        /**
         * 「本地已下载章节数」的采样间隔 —— 也就是「一话下完 -> 触发上传」的反应时间。
         *
         * 一次 `DownloadCache` 内存查表（自带节流），1 秒的节拍代价可以忽略；
         * 再快也不会让上传更早开始（真正启动上传还要经过展开任务、数页数）。
         */
        private const val LOCAL_DOWNLOAD_SAMPLE_MS = 1_000L

        /**
         * 「下载项目自动上传」开关的采样间隔。
         *
         * 只在用户手动开关时才会有变化，5 秒足够及时；一次 map 查找 + 一次 boolean 读取。
         */
        private const val AUTO_UPLOAD_TOGGLE_TICK_MS = 5_000L

        /**
         * 冲突弹窗的最长等待时间。
         *
         * 上传 worker 是单消费者，弹窗没人回答就会把整条队列堵死，所以到点按「取消」继续。
         * 给得足够宽：用户可能正在翻别的页面，看到通知才回来点。
         */
        private const val CONFLICT_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * 等「这一话下载完成」的上限。
         *
         * 正常情况下队列状态一变就会放行，这个上限只是兜底：万一判据因为某种
         * 意料之外的情况没触发（页面列表读不出来、队列条目卡在非终态等），
         * 也不至于把单消费者的上传队列永久堵死。
         * 到点放行后由 `uploadChapter` 自己按当时本地的内容决定传还是跳过。
         */
        private const val CHAPTER_WAIT_MS = 10 * 60 * 1000L

        /** worker 在空队列上打转的间隔。队列是列表不是 Channel，只能轮询。 */
        private const val QUEUE_POLL_MS = 500L
    }
}
