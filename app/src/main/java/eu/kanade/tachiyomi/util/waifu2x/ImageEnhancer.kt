package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.mangaId
import eu.kanade.tachiyomi.data.coil.chapterId
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.data.coil.pageVariant
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import eu.kanade.tachiyomi.data.coil.customDecoder
import logcat.LogPriority
import java.util.concurrent.PriorityBlockingQueue
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import coil3.request.CachePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ImageEnhancer {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingRequests = ConcurrentHashMap<String, Int>()

    /**
     * 左下角「处理状态」展示所需的快照：正在处理的页（0 基，-1 表示空闲）、排队中的页数，
     * 以及本次批次的处理进度（本次已完成张数 / 本次批次张数）。
     */
    data class ProcessingStatus(
        val activePageIndex: Int = -1,
        val queuedCount: Int = 0,
        /** 本次会话已完成处理的张数，不含历史已缓存的成果。 */
        val sessionProcessedCount: Int = 0,
        /** 本次批次的张数 = 本次已完成 + 队列中 + 正在处理的。 */
        val sessionBatchCount: Int = 0,
    )

    private val _status = MutableStateFlow(ProcessingStatus())

    /** 供阅读器左下角状态指示器订阅。 */
    val status: StateFlow<ProcessingStatus> = _status.asStateFlow()

    /** 各章在本次会话中已完成处理的页索引，用于批次进度；历史缓存不计入。 */
    private val sessionProcessedPages = ConcurrentHashMap<String, MutableSet<Int>>()

    private fun chapterKey(mangaId: Long, chapterId: Long): String = "${mangaId}_$chapterId"

    private fun publishStatus() {
        val processedPages = sessionProcessedPages[chapterKey(activeMangaId, activeChapterId)].orEmpty()
        // 按页索引去重统计批次：同一页被插队重排时不会在队列里重复计数，
        // 这样分母始终是本批次真实涉及的张数，进度条不会因插队而跳动
        val queuedPages = queue.asSequence()
            .filter { it.mangaId == activeMangaId && it.chapterId == activeChapterId }
            .map { it.pageIndex }
            .toSet()
        val activePages = if (activeChapterId > 0L && activePageIndex >= 0) {
            setOf(activePageIndex)
        } else {
            emptySet()
        }
        val batchPages = processedPages + queuedPages + activePages
        _status.value = ProcessingStatus(
            activePageIndex = activePageIndex,
            queuedCount = queue.size,
            sessionProcessedCount = processedPages.size,
            sessionBatchCount = batchPages.size,
        )
    }
    
    // Priority Queue order:
    // 1. Current visible primary page
    // 2. Current visible secondary page in double-page mode
    // 3. Other promoted/high-priority requests
    // 4. Normal preload requests
    // Then Distance from Target ASC, Seq ASC
    private val queue = PriorityBlockingQueue<EnhanceRequest>()
    private val seqGenerator = AtomicInteger(0)
    private val generation = AtomicInteger(0)

    /**
     * 页面增强完成后的刷新回调：key = requestKey(mangaId, chapterId, pageIndex, pageVariant)。
     * PagerPageHolder 注册后，其对应页在后台完成增强时会被立即刷新为增强图，
     * 这样用户翻到该页时画面已经是成品（水印立即可见），不再先显示原图。
     */
    private val onEnhancedListeners = ConcurrentHashMap<String, () -> Unit>()

    /** 注册某页增强完成后的刷新回调（由 [PagerPageHolder] 调用）。 */
    fun addOnEnhancedListener(pageKey: String, listener: () -> Unit) {
        onEnhancedListeners[pageKey] = listener
    }

    /** 注销某页的增强完成回调（由 [PagerPageHolder] 在分离视图/换章时调用，避免残留）。 */
    fun removeOnEnhancedListener(pageKey: String) {
        onEnhancedListeners.remove(pageKey)
    }

    @Volatile
    private var lastResetTime = 0L

    @Volatile
    private var isFirstRequestAfterReset = false

    @Volatile
    private var initialTargetEnqueued = false

    @Volatile
    private var activeMangaId = -1L

    @Volatile
    private var activeChapterId = -1L

    @Volatile
    private var activePageIndex = -1

    @Volatile
    private var activePageVariant = ""

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeRequest: EnhanceRequest? = null

    @Volatile
    private var nativeResetJob: Job? = null

    // Current page the user is viewing. Used to prioritize requests closest to this page.
    @Volatile
    var targetPageIndex: Int = 0

    @Volatile
    private var targetPageVariant: String = ""

    @Volatile
    private var targetSecondaryPageIndex: Int = -1

    @Volatile
    private var targetSecondaryPageVariant: String = ""

    /** 翻页停留后待插队的页索引；与 [settlePending] 配合，在上一张增强完成后据此重建预加载窗口。 */
    @Volatile
    private var settlePageIndex = -1

    /** 是否有一次「翻页停留」触发的插队尚未执行（等当前正在增强的图片结束后处理）。 */
    @Volatile
    private var settlePending = false

    /** 「本章节」整章增强模式：打开时忽略预加载窗口，允许整章入队（配合「本章节」按钮使用）。 */
    @Volatile
    private var wholeChapterMode = false

    /** 增强预加载窗口的最小页数：即使「预加载页数」设置很小，也至少缓冲该数量的后续页原图。 */
    private const val ENHANCEMENT_MIN_PRELOAD = 3

    /**
     * 当前增强预加载窗口长度（页）。取用户在设置里选择的「预加载页数」；
     * 读取失败（如增强偏好尚未初始化）时退回 0，等价于只专注当前页。
     */
    private fun currentPreloadWindow(): Int {
        return try {
            Injekt.get<ReaderPreferences>().realCuganPreloadSize().get().coerceAtLeast(0)
        } catch (t: Throwable) {
            0
        }
    }

    data class EnhanceRequest(
        val context: Context,
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val pageVariant: String,
        val dataProvider: () -> Any?,
        val priority: Int, // 1 = promoted/high priority, 0 = preload
        val generation: Int,
        val seq: Int = 0
    ) : Comparable<EnhanceRequest> {
        val cancelled = AtomicBoolean(false)
        val requeueOnCancel = AtomicBoolean(false)

        private fun effectivePriority(): Int {
            return when {
                pageIndex == targetPageIndex && pageVariant == targetPageVariant -> 3
                pageIndex == targetSecondaryPageIndex && pageVariant == targetSecondaryPageVariant -> 2
                priority > 0 -> 1
                else -> 0
            }
        }

        override fun compareTo(other: EnhanceRequest): Int {
            // 1. Effective priority based on current visible spread and promotion state.
            val p = other.effectivePriority().compareTo(effectivePriority()) // Descending
            if (p != 0) return p
            
            // 2. Distance from Target Page (Closer > Farther)
            // Even if multiple pages are "High Priority", the one closest to user focus wins.
            val currentTarget = targetPageIndex
            val dist1 = kotlin.math.abs(pageIndex - currentTarget)
            val dist2 = kotlin.math.abs(other.pageIndex - currentTarget)
            
            val d = dist1.compareTo(dist2) // Ascending (0 distance is best)
            if (d != 0) return d

            // 3. Fallback: FIFO (Older seq first)
            return seq.compareTo(other.seq)
        }
    }

    init {
        // Worker Loop
        scope.launch {
            while (true) {
                try {
                    if (isFirstRequestAfterReset) {
                        val elapsed = System.currentTimeMillis() - lastResetTime
                        if (elapsed < 700) {
                            kotlinx.coroutines.delay(700 - elapsed)
                        }
                        isFirstRequestAfterReset = false
                    }

                    val req = runInterruptible { queue.take() }
                    if (req.generation == generation.get()) {
                        processRequest(req)
                    } else {
                        pendingRequests.remove(req.key, req.generation)
                    }
                } catch (e: Exception) {
                    if (e !is InterruptedException && e !is CancellationException) {
                        logcat(LogPriority.ERROR, e) { "ImageEnhancer: Worker loop error" }
                    }
                }
            }
        }

    }

    fun enhance(context: Context, page: ReaderPage, highPriority: Boolean = false, preempt: Boolean = true) {
        val mangaId = page.chapter.chapter.manga_id ?: -1L
        val chapterId = page.chapter.chapter.id ?: -1L
        
        if (mangaId == -1L || chapterId == -1L) return

        enhanceLazy(
            context = context,
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = page.index,
            highPriority = highPriority,
            pageVariant = page.enhancementKeySuffix,
            preempt = preempt,
        ) {
            // Streams are opened only after queue de-duplication and on the worker dispatcher.
            page.enhancementStream?.let(::bufferStream)
                ?: page.stream?.let(::bufferStream)
                ?: page.imageUrl
        }
    }

    fun enhance(context: Context, mangaId: Long, chapterId: Long, pageIndex: Int, data: Any, highPriority: Boolean, pageVariant: String = "", preempt: Boolean = true) {
        enhanceLazy(context, mangaId, chapterId, pageIndex, highPriority, pageVariant, preempt = preempt) { data }
    }

    fun enhanceLazy(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        highPriority: Boolean,
        pageVariant: String = "",
        preempt: Boolean = true,
        dataProvider: () -> Any?,
    ) {
        if (ImageEnhancementCache.isSavePending(mangaId, chapterId, pageIndex, pageVariant)) {
            logcat(LogPriority.DEBUG) { "ImageEnhancer: 第 $pageIndex/$pageVariant 页正在落盘，跳过入队" }
            return
        }
        // 同一配置下成品已存在时不再入队：内部重排/抢占后的 requeue 会绕过 ReaderEnhancement.request
        // 的缓存校验，若这里不拦，已增强完成的页会被重新处理一遍
        if (hasCachedResult(context, mangaId, chapterId, pageIndex, pageVariant)) {
            logcat(LogPriority.DEBUG) { "ImageEnhancer: 第 $pageIndex/$pageVariant 页已有增强成品，跳过入队" }
            return
        }

        val isInitialTargetRequest = !initialTargetEnqueued && pageIndex == targetPageIndex
        val effectiveHighPriority = highPriority || isInitialTargetRequest

        // 需求 3：非高优先级（预加载/加载器自动触发）的请求只在「当前页 ~ 当前页+预加载页数」窗口内入队，
        // 超出窗口的整章自动入队请求被丢弃，真正让「预加载页数」设置生效。整章增强模式不受此限制。
        // 需求 4：预加载窗口最少 3 页，即使设置很小/读取缺失也不至于只增强当前一页。
        if (!effectiveHighPriority && !wholeChapterMode) {
            val window = currentPreloadWindow().coerceAtLeast(ENHANCEMENT_MIN_PRELOAD)
            if (pageIndex < targetPageIndex || pageIndex > targetPageIndex + window) {
                logcat(LogPriority.DEBUG) {
                    "ImageEnhancer: page $pageIndex/$pageVariant 超出预加载窗口 [target=$targetPageIndex, +$window]，跳过入队"
                }
                return
            }
        }

        val requestKey = requestKey(mangaId, chapterId, pageIndex, pageVariant)
        val requestGeneration = generation.get()
        
        val existingGeneration = pendingRequests[requestKey]
        if (existingGeneration != null) {
            if (effectiveHighPriority) {
                 // Upgrade priority: Remove existing (likely Low) and re-add as High
                 val removed = queue.removeIf { 
                     it.mangaId == mangaId &&
                         it.chapterId == chapterId &&
                         it.pageIndex == pageIndex &&
                         it.pageVariant == pageVariant &&
                         it.generation == existingGeneration
                 }
                 if (removed) {
                     logcat(LogPriority.DEBUG) { "ImageEnhancer: Upgrading page $pageIndex/$pageVariant to High Priority" }
                     pendingRequests.remove(requestKey, existingGeneration)
                     // Proceed to add below
                 } else {
                     // Already processing or failed to remove, skip
                     return
                 }
            } else {
                // Already pending and we are Low priority, so skip
                return
            }
        }

        if (pendingRequests.putIfAbsent(requestKey, requestGeneration) != null) return

        if (isInitialTargetRequest) {
            initialTargetEnqueued = true
        }

        val priorityLevel = if (effectiveHighPriority) 1 else 0
        // 队列已清空且没有处理中的任务时，本次入队视作新一批的开始：
        // 批次计数（已完成张数 / 本批总张数）从零重新累计
        if (queue.isEmpty() && activePageIndex < 0) {
            sessionProcessedPages.clear()
        }
        val req = EnhanceRequest(
            context = context,
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = pageIndex,
            pageVariant = pageVariant,
            dataProvider = dataProvider,
            priority = priorityLevel,
            generation = requestGeneration,
            seq = seqGenerator.getAndIncrement(),
        )
        queue.offer(req)
        publishStatus()

        // A visible page may interrupt background work, but another preloaded page should not be
        // discarded just because the reader advanced within the preload window.
        if (
            effectiveHighPriority &&
            preempt &&
            activePageIndex >= 0 &&
            (activePageIndex != pageIndex || activePageVariant != pageVariant) &&
            !isFocusedTarget(activePageIndex, activePageVariant)
        ) {
            preemptActiveRequest(
                reason = "visible page requested",
                requeue = activePageIndex > targetPageIndex,
            )
        }
        
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Enqueued page $pageIndex/$pageVariant (priority=$priorityLevel)" }
    }

    fun reset(initialPageIndex: Int = 0) {
        cancelAll(reason = "reset")
        queue.clear()
        pendingRequests.clear()
        targetPageIndex = initialPageIndex
        targetPageVariant = ""
        targetSecondaryPageIndex = -1
        targetSecondaryPageVariant = ""
        settlePageIndex = -1
        settlePending = false
        wholeChapterMode = false
        seqGenerator.set(0)
        lastResetTime = System.currentTimeMillis()
        isFirstRequestAfterReset = true
        initialTargetEnqueued = false
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Resetting state to page $initialPageIndex" }
    }

    fun cancelAll(reason: String = "cancelAll", resetNative: Boolean = true) {
        generation.incrementAndGet()
        queue.clear()
        pendingRequests.clear()
        activeJob?.cancel(CancellationException("Image enhancement cancelled: $reason"))
        activeRequest?.cancelled?.set(true)
        activeJob = null
        activeRequest = null
        activeMangaId = -1L
        activeChapterId = -1L
        activePageIndex = -1
        activePageVariant = ""
        initialTargetEnqueued = false
        publishStatus()
        if (resetNative) {
            try {
                Waifu2x.abortProcessing()
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "ImageEnhancer: Failed to signal native abort" }
            }
            if (nativeResetJob?.isActive != true) {
                nativeResetJob = scope.launch {
                    try {
                        Waifu2x.resetRealCugan()
                    } catch (t: Throwable) {
                        logcat(LogPriority.ERROR, t) { "ImageEnhancer: Failed to reset native upscaler" }
                    }
                }
            }
        }
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Cancelled all enhancement work (reason=$reason)" }
    }

    fun reprioritizeAround(
        pageIndex: Int,
        pageVariant: String = "",
        secondaryPageIndex: Int? = null,
        secondaryPageVariant: String = "",
    ) {
        targetPageIndex = pageIndex
        targetPageVariant = pageVariant
        targetSecondaryPageIndex = secondaryPageIndex ?: -1
        targetSecondaryPageVariant = if (secondaryPageIndex != null) secondaryPageVariant else ""
        preemptActiveRequestIfBehindTarget()
        val snapshot = mutableListOf<EnhanceRequest>()
        queue.drainTo(snapshot)
        if (snapshot.isNotEmpty()) {
            queue.addAll(snapshot)
            logcat(LogPriority.DEBUG) {
                "ImageEnhancer: Reprioritized ${snapshot.size} queued pages around target=$pageIndex/$pageVariant secondary=${targetSecondaryPageIndex}/${targetSecondaryPageVariant}"
            }
        }
    }

    /**
     * 阅读器切换到新页时应调用的入口：更新「当前可见目标页」并让队列按距新目标最近优先重排。
     * 只重排优先级，绝不打断正在进行中的增强（需求 1：不要打断正在增强的任务）。
     */
    fun updateFocus(
        pageIndex: Int,
        pageVariant: String = "",
        secondaryPageIndex: Int? = null,
        secondaryPageVariant: String = "",
    ) {
        targetPageIndex = pageIndex
        targetPageVariant = pageVariant
        targetSecondaryPageIndex = secondaryPageIndex ?: -1
        targetSecondaryPageVariant = if (secondaryPageIndex != null) secondaryPageVariant else ""
        // 按距离重新排序：越靠近当前目标页的请求越先处理
        val snapshot = mutableListOf<EnhanceRequest>()
        queue.drainTo(snapshot)
        if (snapshot.isNotEmpty()) {
            queue.addAll(snapshot)
        }
    }

    /** 开启/关闭「本章节」整章增强模式（由设置页「本章节」按钮调用）。 */
    fun setWholeChapterMode(enabled: Boolean) {
        wholeChapterMode = enabled
        logcat(LogPriority.DEBUG) { "ImageEnhancer: wholeChapterMode=$enabled" }
    }

    /**
     * 翻页后停留 1 秒未再翻页时调用（需求 1）：登记一次「窗口重建插队」。
     * 这里只登记意图，不做任何打断；等当前正在增强的图片完成后，
     * 由 worker 在 [processRequest] 的 finally 里依据 [settlePending] 决定是否重建队列。
     */
    fun settle(
        pageIndex: Int,
        pageVariant: String = "",
        secondaryPageIndex: Int? = null,
        secondaryPageVariant: String = "",
    ) {
        settlePageIndex = pageIndex
        settlePending = true
        updateFocus(pageIndex, pageVariant, secondaryPageIndex, secondaryPageVariant)
    }

    /**
     * 需求 2：翻页停留插队执行的地方 —— 取消旧的预加载队列，以停留页为开头向后重建
     * 「当前页 ~ 当前页+预加载页数」窗口；已经增强完成的页保留（批次进度与缓存不动），避免重复增强。
     */
    private fun rebuildWindowFrom(mangaId: Long, chapterId: Long, windowStart: Int) {
        // 整章增强模式下不裁剪队列
        if (wholeChapterMode) {
            logcat(LogPriority.DEBUG) { "ImageEnhancer: 整章模式，跳过窗口重建" }
            return
        }
        val window = currentPreloadWindow().coerceAtLeast(ENHANCEMENT_MIN_PRELOAD)
        val end = windowStart + window
        val before = queue.size
        queue.removeIf { q ->
            if (
                q.mangaId == mangaId &&
                q.chapterId == chapterId &&
                (q.pageIndex < windowStart || q.pageIndex > end)
            ) {
                pendingRequests.remove(q.key, q.generation)
                true
            } else {
                false
            }
        }
        // 重新按距离排序，让窗口开头（停留页）排在前面
        val snapshot = mutableListOf<EnhanceRequest>()
        queue.drainTo(snapshot)
        if (snapshot.isNotEmpty()) {
            queue.addAll(snapshot)
        }
        publishStatus()
        logcat(LogPriority.DEBUG) {
            "ImageEnhancer: 停留 [$windowStart] 后重建预加载窗口 [+$window]，丢弃 ${before - queue.size} 个旧任务（已完成页保留）"
        }
    }


    fun hasRequest(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return pendingRequests.containsKey(requestKey(mangaId, chapterId, pageIndex, pageVariant)) ||
            ImageEnhancementCache.isSavePending(mangaId, chapterId, pageIndex, pageVariant)
    }

    fun isFocusedTarget(pageIndex: Int, pageVariant: String = ""): Boolean {
        return (pageIndex == targetPageIndex && pageVariant == targetPageVariant) ||
            (pageIndex == targetSecondaryPageIndex && pageVariant == targetSecondaryPageVariant)
    }

    fun isActivelyProcessing(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return activeMangaId == mangaId &&
            activeChapterId == chapterId &&
            activePageIndex == pageIndex &&
            activePageVariant == pageVariant
    }

    fun cancel(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = "") {
        val requestKey = requestKey(mangaId, chapterId, pageIndex, pageVariant)
        if (pendingRequests.remove(requestKey) != null) {
             val removed = queue.removeIf { 
                 it.mangaId == mangaId && it.chapterId == chapterId && it.pageIndex == pageIndex && it.pageVariant == pageVariant
             }
             if (removed) {
                 logcat(LogPriority.DEBUG) { "ImageEnhancer: Cancelled page $pageIndex/$pageVariant" }
             }
        }
        if (
            activeMangaId == mangaId &&
            activeChapterId == chapterId &&
            activePageIndex == pageIndex &&
            activePageVariant == pageVariant
        ) {
            preemptActiveRequest("page cancelled")
        }
        publishStatus()
    }

    fun cancelRequestsLessThan(context: Context, mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex < thresholdPageIndex) {
                pendingRequests.remove(req.key, req.generation)
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Pruned page ${req.pageIndex}/${req.pageVariant} (reason: < $thresholdPageIndex)" }
                true
            } else {
                false
            }
        }
        publishStatus()
    }

    fun cancelRequestsGreaterThan(context: Context, mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex > thresholdPageIndex) {
                pendingRequests.remove(req.key, req.generation)
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Pruned page ${req.pageIndex}/${req.pageVariant} (reason: > $thresholdPageIndex)" }
                true
            } else {
                false
            }
        }
        publishStatus()
    }

    private suspend fun processRequest(req: EnhanceRequest) {
        try {
            if (req.generation != generation.get()) return
            activeMangaId = req.mangaId
            activeChapterId = req.chapterId
            activePageIndex = req.pageIndex
            activePageVariant = req.pageVariant
            activeRequest = req
            publishStatus()
            logcat(LogPriority.DEBUG) { "ImageEnhancer: Processing page ${req.pageIndex}/${req.pageVariant} (priority=${req.priority})" }
            val data = req.dataProvider() ?: return
            if (req.generation != generation.get() || req.cancelled.get()) return
            Waifu2x.prepareProcessing()
            if (req.cancelled.get()) return
            val request = ImageRequest.Builder(req.context)
                .data(data)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .customDecoder(true)
                .enhanced(true)
                .mangaId(req.mangaId)
                .chapterId(req.chapterId)
                .pageIndex(req.pageIndex)
                .pageVariant(req.pageVariant)
                .build()
            
            val disposable = SingletonImageLoader.get(req.context).enqueue(request)
            val job = disposable.job
            activeJob = job
            if (req.generation != generation.get() || req.cancelled.get()) {
                Waifu2x.abortProcessing()
                job.cancel(CancellationException("Image enhancement request became obsolete"))
            }
            job.await()
            // 增强完成（成品已落盘）：通知持有该页的视图立即刷新为增强图，
            // 即使页面尚在后台，翻到时也能直接看到成品而不再先显示原图。
            onEnhancedListeners[req.key]?.invoke()
            // 本次会话完成该页，计入批次进度
            sessionProcessedPages
                .getOrPut(chapterKey(req.mangaId, req.chapterId)) { ConcurrentHashMap.newKeySet() }
                .add(req.pageIndex)
        } finally {
            val shouldRequeue = req.requeueOnCancel.get() && req.generation == generation.get()
            activeMangaId = -1L
            activeChapterId = -1L
            activePageIndex = -1
            activePageVariant = ""
            activeJob = null
            activeRequest = null
            pendingRequests.remove(req.key, req.generation)
            publishStatus()

            if (shouldRequeue) {
                logcat(LogPriority.DEBUG) {
                    "ImageEnhancer: Re-queueing preempted preload page ${req.pageIndex}/${req.pageVariant}"
                }
                enhanceLazy(
                    context = req.context,
                    mangaId = req.mangaId,
                    chapterId = req.chapterId,
                    pageIndex = req.pageIndex,
                    highPriority = false,
                    pageVariant = req.pageVariant,
                    dataProvider = req.dataProvider,
                )
            }

            // 需求 1/2：翻页停留插队。当前正在增强的图片完成后：
            // - 若期间用户没有再次翻页（目标页仍是停留页），则重建预加载窗口并让停留页排在前面；
            // - 若在完成之前用户已切换页面（目标页变了），丢弃这次插队，不做任何动作。
            if (settlePending) {
                val shouldRebuild = settlePageIndex == targetPageIndex
                settlePending = false
                if (shouldRebuild) {
                    rebuildWindowFrom(req.mangaId, req.chapterId, settlePageIndex)
                }
            }
        }
    }

    private val EnhanceRequest.key: String
        get() = requestKey(mangaId, chapterId, pageIndex, pageVariant)

    private fun requestKey(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): String {
        return "${mangaId}_${chapterId}_${pageIndex}_${pageVariant}"
    }

    /**
     * 该页在当前增强配置下是否已有落盘成品。
     * 用于入队前去重，避免已完成的页被重复增强。
     */
    private fun hasCachedResult(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        pageVariant: String,
    ): Boolean {
        return try {
            if (!ReaderEnhancement.isEnabled()) return false
            ImageEnhancementCache.init(context)
            ImageEnhancementCache.isCached(
                mangaId = mangaId,
                chapterId = chapterId,
                pageIndex = pageIndex,
                configHash = ReaderEnhancement.configHash(),
                pageVariant = pageVariant,
            )
        } catch (t: Throwable) {
            false
        }
    }

    private fun bufferStream(streamFactory: () -> java.io.InputStream): Any? {
        return try {
            streamFactory().use { okio.Buffer().readFrom(it) }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ImageEnhancer: Failed to read enhancement source" }
            null
        }
    }

    private fun preemptActiveRequestIfBehindTarget() {
        if (activePageIndex >= 0 && activePageIndex < targetPageIndex) {
            preemptActiveRequest("active page is behind visible target")
        }
    }

    private fun preemptActiveRequest(reason: String, requeue: Boolean = false) {
        val request = activeRequest ?: return
        if (requeue) {
            request.requeueOnCancel.set(true)
        }
        if (!request.cancelled.compareAndSet(false, true)) return

        logcat(LogPriority.DEBUG) {
            "ImageEnhancer: Preempting active page $activePageIndex/$activePageVariant ($reason)"
        }
        Waifu2x.abortProcessing()
        activeJob?.cancel(CancellationException("Image enhancement preempted: $reason"))
    }
}
