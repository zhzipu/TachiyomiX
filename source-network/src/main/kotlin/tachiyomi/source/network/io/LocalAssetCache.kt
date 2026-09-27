package tachiyomi.source.network.io

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 把远端文件按需下载到本地缓存。
 *
 * ## 现在只服务封面
 *
 * 阅读器/书架加载 `thumbnail_url` 时走的是 Coil，**不会带图源自己的 Basic Auth**，
 * 所以只能先把封面拉到本地，再把 `file://` 地址交出去。
 *
 * 章节图片**不走这里**：一页就是一个普通文件，[tachiyomi.source.network.NetworkSource.getImage]
 * 直接把远端流当响应体返回，不落盘也不需要 libarchive（早先一话一个压缩包时，
 * 因为 `ArchiveReader` 必须 mmap 整个包，才不得不先整包下到本地 —— 那正是改成图片目录的原因）。
 *
 * ## 缓存键与失效
 *
 * 键 = `路径 | 大小 | 修改时间`，三者都来自 `PROPFIND`（[RemoteEntry]），
 * 所以远端文件一变，缓存键就变，不会读到旧内容。
 * 少数服务端不返回 `getcontentlength` / `getlastmodified`（都为 0），
 * 这时会退化成一个「按天」的桶，至少保证隔天会重新拉一次，而不是永远读旧文件。
 *
 * ## 清理
 *
 * 每次下载完顺手做一次容量清理：超过上限就按 `lastModified` 从旧到新删，
 * 另外清掉残留的 `.part` 半截文件。不做清理的话 `cacheDir` 会一直涨。
 */
class LocalAssetCache(
    private val context: Context,
) {

    private val root: File
        get() = File(context.cacheDir, ROOT_DIR_NAME)

    /** 同一路径并发请求时只下载一次，避免封面预取把带宽打满。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 取得 [entry] 对应的本地文件，没有就下载。
     *
     * @param bucket 缓存子目录名（目前只有 [BUCKET_COVERS]）
     */
    suspend fun obtain(remote: RemoteFileSystem, entry: RemoteEntry, bucket: String): File = withIOContext {
        val dir = File(root, bucket).apply { mkdirs() }
        val target = File(dir, fileNameOf(entry))

        if (isUsable(target)) {
            // 摸一下访问时间，供 LRU 清理用
            target.setLastModified(System.currentTimeMillis())
            return@withIOContext target
        }

        val lock = locks.getOrPut(entry.path) { Mutex() }
        try {
            lock.withLock {
                // 双重检查：等锁期间可能已经被别的协程下好了
                if (isUsable(target)) {
                    target.setLastModified(System.currentTimeMillis())
                } else {
                    val tmp = File(dir, target.name + PART_SUFFIX)
                    try {
                        // RemoteFile 自己不实现 Closeable，要关的是它里面的 stream
                        // （关流会经 okio 一路把底层连接释放掉）
                        remote.open(entry.path).stream.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (tmp.length() <= 0L) {
                            throw RemoteUnreachableException(IllegalStateException("empty body: ${entry.path}"))
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                    } finally {
                        tmp.delete()
                    }
                    target.setLastModified(System.currentTimeMillis())
                    prune(dir, maxBytesOf(bucket))
                }
            }
        } finally {
            locks.remove(entry.path, lock)
        }
        target
    }

    /** 只判断本地有没有，不联网。 */
    fun cachedFile(entry: RemoteEntry, bucket: String): File? =
        File(File(root, bucket), fileNameOf(entry)).takeIf { isUsable(it) }

    // 内部

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0L

    /**
     * 缓存文件名：键的 SHA-1 十六进制 + 原扩展名。
     * 保留扩展名只是方便肉眼排查，真正取图时的格式判断依旧靠内容嗅探。
     */
    private fun fileNameOf(entry: RemoteEntry): String {
        val key = buildString {
            append(entry.path)
            append('|').append(entry.size)
            append('|').append(entry.lastModified)
            // 服务端不给大小也不给时间时，退化成按天失效
            if (entry.size <= 0L && entry.lastModified <= 0L) {
                append('|').append(System.currentTimeMillis() / DAY_MS)
            }
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { append(HEX[it.toInt() shr 4 and 0xF]).append(HEX[it.toInt() and 0xF]) }
        }
        val ext = entry.name.substringAfterLast('.', "").lowercase().take(10)
        return if (ext.isEmpty()) hex else "$hex.$ext"
    }

    /** 每个桶的容量上限。现在只有封面一个桶，压缩包缓存已随「一话一个图片目录」一起删掉了。 */
    private fun maxBytesOf(bucket: String): Long = when (bucket) {
        BUCKET_COVERS -> MAX_COVER_BYTES
        else -> MAX_COVER_BYTES
    }

    /** 超限就按最久未访问的先删；同时清掉残留的 `.part`。 */
    private fun prune(dir: File, maxBytes: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()

        val alive = ArrayList<File>(files.size)
        for (file in files) {
            if (file.name.endsWith(PART_SUFFIX)) {
                if (now - file.lastModified() > STALE_PART_MS) file.delete()
            } else {
                alive.add(file)
            }
        }

        var total = alive.sumOf { it.length() }
        if (total <= maxBytes) return

        alive.sortBy { it.lastModified() }
        for (file in alive) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        const val BUCKET_COVERS = "covers"

        private const val ROOT_DIR_NAME = "network_source"
        private const val PART_SUFFIX = ".part"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val STALE_PART_MS = DAY_MS

        private const val MAX_COVER_BYTES = 128L * 1024 * 1024

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
