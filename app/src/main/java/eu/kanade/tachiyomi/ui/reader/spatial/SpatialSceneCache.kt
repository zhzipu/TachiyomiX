package eu.kanade.tachiyomi.ui.reader.spatial

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.File

class SpatialSceneCache(context: Context) {
    private val root = File(context.cacheDir, "$CACHE_PARENT_DIR/$CACHE_VERSION_DIR")

    fun sceneFile(page: ReaderPage): File {
        val chapterId = page.chapter.chapter.id
        val mangaId = page.chapter.chapter.manga_id
        val variant = page.enhancementKeySuffix
            .ifBlank { "full" }
            .replace(UNSAFE_FILE_CHARS, "_")
        return File(root, "$mangaId/$chapterId/${page.index}-$variant.d3ds")
    }

    fun cachedScene(page: ReaderPage): File? = sceneFile(page).takeIf { it.isFile && it.length() > 1024L }

    fun prepare(page: ReaderPage) {
        sceneFile(page).parentFile?.mkdirs()
    }

    companion object {
        private const val CACHE_PARENT_DIR = "depth-spatial-scenes"

        // 场景文件格式随实现变化，换版本即可让旧缓存自然失效。
        private const val CACHE_VERSION_DIR = "v27-hologram-edges"

        private val UNSAFE_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")

        /**
         * 清空全部景深场景缓存（退出阅读器 / 应用启动时调用）。
         */
        fun clear(context: Context) {
            File(context.cacheDir, CACHE_PARENT_DIR).deleteRecursively()
        }
    }
}
