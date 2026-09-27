package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * 阅读器图像增强（AI 放大）的统一入口。
 *
 * 负责把「阅读器偏好」翻译成增强配置哈希，并对外提供缓存查询与处理触发，
 * 供解码器（[eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder]）、
 * 页面加载器和阅读器视图复用，避免各处重复拼装同一份配置。
 */
object ReaderEnhancement {

    /**
     * 一次设置改动可能联动多个增强偏好（例如换模型会顺带纠正降噪档位），
     * 视图刷新前统一去抖这段时间，保证一次改动最多重新处理一次。
     */
    const val CONFIG_CHANGE_DEBOUNCE_MS = 150L

    /**
     * 阅读器内是否开启了图像增强。
     */
    fun isEnabled(preferences: ReaderPreferences = Injekt.get()): Boolean =
        preferences.realCuganEnabled().get()

    /**
     * 当前偏好对应的增强配置哈希，与缓存文件名、解码器使用的哈希保持一致。
     */
    fun configHash(preferences: ReaderPreferences = Injekt.get()): String =
        ImageEnhancementCache.getConfigHash(
            noise = preferences.realCuganNoiseLevel().get(),
            scale = preferences.realCuganScale().get(),
            model = preferences.realCuganModel().get(),
            realEsrganStyle = preferences.realEsrganStyle().get(),
            maxWidth = preferences.realCuganMaxSizeWidth().get(),
            maxHeight = preferences.realCuganMaxSizeHeight().get(),
            skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get(),
            skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get(),
            tileSize = preferences.realCuganTileSize().get(),
            precision = preferences.realCuganPrecision().get(),
            fp16Arithmetic = preferences.realCuganFp16Arithmetic().get(),
            processingBackend = preferences.realCuganProcessingBackend().get(),
        )

    /**
     * 查询某一页已经原生放大并落盘的文件；未开启增强或尚未生成时返回 null。
     */
    fun cachedFile(
        context: Context,
        page: ReaderPage,
        preferences: ReaderPreferences = Injekt.get(),
    ): File? {
        if (!isEnabled(preferences)) return null
        val (mangaId, chapterId) = chapterIds(page) ?: return null

        ImageEnhancementCache.init(context)
        return ImageEnhancementCache.getCachedImage(
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = page.index,
            configHash = configHash(preferences),
            pageVariant = page.enhancementKeySuffix,
        )
    }

    /**
     * 触发某一页的增强处理；当前可见页应传 [highPriority] = true 以插队处理。
     * 若要「提高优先级但不打断正在进行的任务」，可传 [preempt] = false（翻页停留场景使用）。
     */
    fun request(
        context: Context,
        page: ReaderPage,
        highPriority: Boolean = false,
        preempt: Boolean = true,
        preferences: ReaderPreferences = Injekt.get(),
    ) {
        if (!isEnabled(preferences)) return
        val (mangaId, chapterId) = chapterIds(page) ?: return

        ImageEnhancementCache.init(context)
        // 同一配置下当前章已经增强过（成品在缓存里），翻回该页时不再重复排队增强
        if (ImageEnhancementCache.getCachedImage(mangaId, chapterId, page.index, configHash(preferences), page.enhancementKeySuffix) != null) {
            return
        }
        // 之前已判定为超大图跳过时不再重复触发，避免白白解码一次原图
        if (ImageEnhancementCache.isSkipped(mangaId, chapterId, page.index, configHash(preferences), page.enhancementKeySuffix)) {
            return
        }
        ImageEnhancer.enhance(context, page, highPriority, preempt)
    }

    /**
     * 按「预加载页数」提前排队当前页之后若干页的增强，让翻页时直接命中成品。
     * 只处理已加载完成（Ready）的页；尚未加载的页等它们自己进入阅读器时再触发。
     */
    fun requestPreload(
        context: Context,
        page: ReaderPage,
        preferences: ReaderPreferences = Injekt.get(),
    ) {
        if (!isEnabled(preferences)) return
        // 与增强窗口的最小预加载页数保持一致（ImageEnhancer.ENHANCEMENT_MIN_PRELOAD = 3），
        // 否则当「预加载页数」为 0 或读取偏小时，进入章节中间页只会离队当前页、后续页永远不入队。
        val count = preferences.realCuganPreloadSize().get().coerceAtLeast(3)
        if (count <= 0) return

        val pages = page.chapter.pages ?: return
        val end = minOf(page.index + 1 + count, pages.size)
        for (i in page.index + 1 until end) {
            // 不再要求后续页已处于 Ready：进入章节中间页时后续页往往尚未加载原图，
            // 此时也应把它们加入增强队列，由增强器在拿到原图后填充处理，而不是只入队当前一页。
            val next = pages[i]
            request(context, next, preferences = preferences)
        }
    }

    /**
     * 该页是否已被增强流程判定为“超大图跳过”，将永远不会产出增强图。
     */
    fun isSkipped(
        context: Context,
        page: ReaderPage,
        preferences: ReaderPreferences = Injekt.get(),
    ): Boolean {
        if (!isEnabled(preferences)) return false
        val (mangaId, chapterId) = chapterIds(page) ?: return false
        ImageEnhancementCache.init(context)
        return ImageEnhancementCache.isSkipped(
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = page.index,
            configHash = configHash(preferences),
            pageVariant = page.enhancementKeySuffix,
        )
    }

    /**
     * 取页面对应的 manga / chapter id。
     * 页面尚未绑定章节（章节列表仍在加载）时返回 null，此时不做增强。
     */
    private fun chapterIds(page: ReaderPage): Pair<Long, Long>? {
        val chapter = try {
            page.chapter.chapter
        } catch (e: UninitializedPropertyAccessException) {
            return null
        }
        return when {
            chapter.manga_id == null || chapter.id == null -> null
            else -> chapter.manga_id!! to chapter.id!!
        }
    }
}
