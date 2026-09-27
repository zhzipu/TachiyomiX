package eu.kanade.tachiyomi.data.upload

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * 上传队列的**落盘**：把「队列里有哪几话、什么顺序」存到磁盘。
 *
 * 与 [eu.kanade.tachiyomi.data.download.DownloadQueueStore] 是同一套做法、同一套理由：
 * 用户要求退出软件后队列还在，重新打开能看见（但**不自动开始上传**）。
 *
 * 存的是每话的 `(mangaId, chapterId)` 加上这一批的 `askOnConflict` ——
 * 章节名 / 章节号可以从数据库按 chapterId 查回来，不必存；`askOnConflict` 是这一批的
 * 手动/自动属性，重建时没有别的来源，所以要存。
 */
internal class UploadQueueStore(private val context: Context) {

    @Serializable
    private data class Entry(
        val mangaId: Long,
        val chapterId: Long,
        val askOnConflict: Boolean,
    )

    @Serializable
    private data class Snapshot(
        val entries: List<Entry> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    /** 读回队列（按顺序）。文件不存在或坏了就当作空。 */
    suspend fun read(): List<UploadTask> = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext emptyList()
        runCatching {
            json.decodeFromString<Snapshot>(f.readText()).entries.map {
                // 章节名 / 章节号在恢复时会由 `UploadManager.restoreQueue` 从库里补上，
                // 这里先占位。
                UploadTask(
                    mangaId = it.mangaId,
                    chapterId = it.chapterId,
                    chapterName = "",
                    chapterNumber = 0.0,
                    askOnConflict = it.askOnConflict,
                )
            }
        }
            .onFailure { logcat(LogPriority.WARN, it) { "upload queue: unreadable snapshot, ignoring" } }
            .getOrDefault(emptyList())
    }

    /** 写队列。任何队列变动之后都该调一次。 */
    suspend fun write(tasks: List<UploadTask>) = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = Snapshot(
                tasks.map { Entry(it.mangaId, it.chapterId, it.askOnConflict) },
            )
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "upload queue: failed to persist" } }
    }

    /** 清空（队列空 / 全部取消时）。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
            .onFailure { logcat(LogPriority.WARN, it) { "upload queue: failed to clear" } }
    }

    private companion object {
        const val FILE_NAME = "upload_queue.json"
    }
}
