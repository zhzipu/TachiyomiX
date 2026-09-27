package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    // SY -->
    /** Value to check if this page is used to as if it was too wide */
    var shiftedPage: Boolean = false,
    /** Value to check if a page is can be doubled up, but can't because the next page is too wide */
    var isolatedPage: Boolean = false,
    // SY <--
    var stream: (() -> InputStream)? = null,

) : Page(index, url, imageUrl, null), ReaderItem {

    // 图像增强（AI 放大）结果流与缓存键后缀
    var enhancementStream: (() -> InputStream)? = null
    var enhancementKeySuffix: String = ""

    /**
     * 当前 [stream] 是否已指向增强成品。
     * 页面加载时命中增强缓存会替换 [stream]，之后再次查询缓存未必命中，
     * 用这个标记保证「显示的图」与「是否显示增强水印」始终一致。
     */
    var usingEnhancedStream: Boolean = false

    open lateinit var chapter: ReaderChapter

    /** Value to check if a page is too wide to be doubled up */
    var fullPage: Boolean = false
        set(value) {
            field = value
            if (value) shiftedPage = false
        }
}
