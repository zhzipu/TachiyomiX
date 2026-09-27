package mihon.core.common.archive

import eu.kanade.tachiyomi.util.storage.CbzCrypto
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import mihon.core.common.archive.ArchiveEntry as MihonArchiveEntry

class ArchiveInputStream(
    buffer: Long,
    size: Long,
    // SY -->
    encrypted: Boolean,
    // SY <--
    // SY -->
    private val onClose: (() -> Unit)? = null,
    private val onOpen: (() -> Unit)? = null,
    // SY <--
) : InputStream() {
    private val lock = Any()

    @Volatile
    private var isClosed = false

    // SY -->
    // 标记 acquire 是否已执行，确保 close() 只在与 onOpen 配对时触发 onClose，
    // 避免 readOpenMemoryUnsafe 失败路径（未 acquire 却 close）把引用计数减成负数。
    @Volatile
    private var acquired = false
    // SY <--

    private val archive = Archive.readNew()

    init {
        try {
            // SY -->
            if (encrypted) {
                Archive.readAddPassphrase(archive, CbzCrypto.getDecryptedPasswordCbz())
            }
            // SY <--
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenMemoryUnsafe(archive, buffer, size)
            // SY -->
            // archive 已成功打开、开始持有 mmap 地址的引用，通知 ArchiveReader 计数 +1。
            acquired = true
            onOpen?.invoke()
            // SY <--
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    override fun read(): Int {
        read(oneByteBuffer)
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buffer = ByteBuffer.wrap(b, off, len)
        read(buffer)
        return if (buffer.hasRemaining()) buffer.remaining() else -1
    }

    private fun read(buffer: ByteBuffer) {
        buffer.clear()
        Archive.readData(archive, buffer)
        buffer.flip()
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)

        // SY -->
        // 只有成功 open（acquired）过的流才负责把引用计数 -1，避免失败路径减成负数。
        if (acquired) {
            onClose?.invoke()
        }
        // SY <--
    }

    fun getNextEntry(): MihonArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            // SY -->
            val isEncrypted = ArchiveEntry.isEncrypted(entry)
            // SY <--
            MihonArchiveEntry(
                name,
                isFile,
                // SY -->
                isEncrypted,
                // SY <--
            )
        }
    }
}
