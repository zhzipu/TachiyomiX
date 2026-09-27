package tachiyomi.source.network.library

import tachiyomi.source.network.config.CONFIG_FILE_NAME
import tachiyomi.source.network.config.ConfigJson
import tachiyomi.source.network.config.ConfigJsonEncoder
import tachiyomi.source.network.config.LibraryIndex
import tachiyomi.source.network.config.MangaConfig
import tachiyomi.source.network.config.chapterFolderIndex
import tachiyomi.source.network.config.chapterFolderName
import tachiyomi.source.network.io.RemoteEntry
import tachiyomi.source.network.io.RemoteFileSystem
import java.io.InputStream
import kotlin.random.Random

/**
 * 网络图源「库」这一层的读写入口，供 App 侧的上传功能使用。
 *
 * [RemoteFileSystem] 只提供「列目录 / 取流 / 写流 / 建目录」四个原语，
 * 这里把它们组合成「按目录约定操作库」的动作：读根索引、分配随机漫画文件夹、
 * 分配 `No.NNNN` 章节文件夹、读写 `config.json`、放章节图片（一页一个文件）与封面。
 *
 * 目录约定的定义见 [MangaConfig] 与 [LibraryIndex]；这个类只是它的执行者，
 * 不重复定义规范。
 *
 * 读路径上的容错策略与 [tachiyomi.source.network.NetworkSource] 保持一致：
 * 单个文件读不动不该让整个操作崩掉，读失败按「没有」处理。
 */
class NetworkLibraryClient(
    private val fs: RemoteFileSystem,
) {

    // 读

    /** 根目录下的直接子目录（用来判文件夹重名）。跳过隐藏项。 */
    private suspend fun listRootDirs(): List<RemoteEntry> =
        fs.list("")
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .distinctBy { it.name }

    /**
     * 读库索引（根目录 `config.json`）。
     *
     * 读不到（库还是空的、文件被删、内容坏了）返回空索引 —— 对调用方来说等价于
     * 「服务器上还没有任何漫画」，后面自然会走上「分配新文件夹」的分支。
     */
    suspend fun readIndex(): LibraryIndex = runCatching {
        val text = fs.open(CONFIG_FILE_NAME).let { file -> file.stream.use { it.readBytes() }.decodeToString() }
        ConfigJson.decodeFromString(LibraryIndex.serializer(), text)
    }.getOrDefault(LibraryIndex())

    /** 读某个漫画文件夹的 `config.json`；不存在或解析失败都返回 null。 */
    suspend fun readConfig(folderPath: String): MangaConfig? = runCatching {
        val text = fs.open("$folderPath/$CONFIG_FILE_NAME")
            .let { file -> file.stream.use { it.readBytes() }.decodeToString() }
        ConfigJson.decodeFromString(MangaConfig.serializer(), text)
    }.getOrNull()

    /**
     * 按漫画名找服务器上已有的漫画文件夹。
     *
     * **只能以根索引为准** —— 漫画文件夹是随机字符串，光看目录名不可能知道是哪本漫画。
     * 索引里有、但目录已经被删掉的悬空条目按「没有」处理（顺便让调用方去重建）。
     */
    suspend fun findFolderByMangaName(mangaName: String): RemoteEntry? {
        val folderName = readIndex().find(mangaName)?.folderName ?: return null
        return listRootDirs().firstOrNull { it.name == folderName }
    }

    /**
     * 分配一个没被占用的漫画文件夹名：**随机字符串**。
     *
     * 随机而不是递增数字，是为了让「文件夹名」完完全全只是个 ID —— 既不会暴露库里
     * 有多少本、也不会因为中间删掉一本就出现空号。
     */
    suspend fun allocateFolderName(): String {
        val used = listRootDirs().map { it.name }.toHashSet()
        repeat(RANDOM_NAME_ATTEMPTS) {
            val candidate = randomFolderName()
            if (candidate !in used) return candidate
        }
        // 极端情况下（随机撞满）加个时间戳后缀兜底，保证一定给出唯一值
        return randomFolderName() + System.currentTimeMillis().toString(36)
    }

    /** 列出某个漫画文件夹下的章节文件夹（`No.NNNN`），按序号升序。列不动就当空表。 */
    suspend fun listChapterFolders(mangaFolderPath: String): List<RemoteEntry> = runCatching {
        fs.list(mangaFolderPath)
            .filter { it.isDirectory && chapterFolderIndex(it.name) != null }
            .distinctBy { it.name }
            .sortedBy { chapterFolderIndex(it.name) ?: Int.MAX_VALUE }
    }.getOrDefault(emptyList())

    /**
     * 下一个可用的章节文件夹名：现有最大序号 + 1。
     *
     * [existing] 传**当前已占用的名字集合**（调用方在批量上传时要边分配边加进去，
     * 否则同一个批次里会算出同一个名字）。
     */
    fun nextChapterFolderName(existing: Collection<String>): String =
        chapterFolderName((existing.mapNotNull(::chapterFolderIndex).maxOrNull() ?: 0) + 1)

    // 写

    /** 确保根目录存在（固定为 `TachiyomiX manga`）。 */
    suspend fun ensureRoot() = fs.makeDirectory("")

    /** 递归创建目录，幂等。 */
    suspend fun ensureFolder(path: String) = fs.makeDirectory(path)

    /** 写出库索引（根目录 `config.json`）。 */
    suspend fun writeIndex(index: LibraryIndex) {
        val bytes = ConfigJsonEncoder.encodeToString(LibraryIndex.serializer(), index).toByteArray(Charsets.UTF_8)
        fs.write(
            path = CONFIG_FILE_NAME,
            body = { bytes.inputStream() },
            length = bytes.size.toLong(),
            contentType = JSON_MEDIA_TYPE,
        )
    }

    /**
     * 把一本漫画登记进根索引（已存在就更新它的文件夹），并返回更新后的索引。
     *
     * 先读后写：索引是**整份覆盖**，不先读会把别的漫画全抹掉。
     */
    suspend fun upsertIndexEntry(mangaName: String, folderName: String): LibraryIndex {
        val updated = readIndex().upsert(mangaName, folderName)
        writeIndex(updated)
        return updated
    }

    suspend fun writeConfig(folderPath: String, config: MangaConfig) {
        val bytes = ConfigJsonEncoder.encodeToString(MangaConfig.serializer(), config).toByteArray(Charsets.UTF_8)
        fs.write(
            path = "$folderPath/$CONFIG_FILE_NAME",
            body = { bytes.inputStream() },
            length = bytes.size.toLong(),
            contentType = JSON_MEDIA_TYPE,
        )
    }

    /**
     * 把一个页图片放进 `漫画文件夹/章节文件夹/`。
     *
     * 一话的每一页都要调一次（而不是以前那样「一话一个压缩包」）—— 这样读的时候
     * 才能一页一请求，不必先把整话下完。文件名沿用本地那一份（`001.jpg` 这种），
     * 顺序靠**文件名自然序**表达，不再依赖压缩包内部的条目顺序。
     *
     * 调用方需先保证 `漫画文件夹` 与 `漫画文件夹/章节文件夹` 都已存在。
     * [body] 是「产出流的工厂」，理由见 [RemoteFileSystem.write]。
     */
    suspend fun putChapterImage(
        mangaFolderPath: String,
        chapterFolder: String,
        fileName: String,
        length: Long,
        body: () -> InputStream,
    ) {
        fs.write(
            path = "$mangaFolderPath/$chapterFolder/$fileName",
            body = body,
            length = length,
            contentType = guessMediaType(fileName),
        )
    }

    /** 把封面放进漫画文件夹。 */
    suspend fun putCover(
        folderPath: String,
        fileName: String,
        length: Long,
        body: () -> InputStream,
    ) {
        fs.write(
            path = "$folderPath/$fileName",
            body = body,
            length = length,
            contentType = guessMediaType(fileName),
        )
    }

    /**
     * 随机漫画文件夹名：只用小写字母 + 数字，且剔掉容易看混的 `0/o/1/l/i`。
     *
     * 名字要手输/手改的场合（比如用户直接翻 WebDAV）不至于看错。
     */
    private fun randomFolderName(): String = buildString(RANDOM_NAME_LENGTH) {
        repeat(RANDOM_NAME_LENGTH) {
            append(RANDOM_NAME_ALPHABET[Random.nextInt(RANDOM_NAME_ALPHABET.length)])
        }
    }

    private fun guessMediaType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "zip", "cbz" -> "application/zip"
        "rar", "cbr" -> "application/vnd.rar"
        "7z", "cb7" -> "application/x-7z-compressed"
        "tar", "cbt" -> "application/x-tar"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        else -> null
    }

    companion object {
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

        /** 随机漫画文件夹名的长度。 */
        private const val RANDOM_NAME_LENGTH = 6

        /** 随机名字的字符集（去掉了 0/o/1/l/i 这些易混字符）。 */
        private const val RANDOM_NAME_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        /** 随机重名的重试次数，超过就用时间戳兜底。 */
        private const val RANDOM_NAME_ATTEMPTS = 8
    }
}
