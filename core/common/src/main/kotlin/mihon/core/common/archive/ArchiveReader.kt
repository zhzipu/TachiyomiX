package mihon.core.common.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.hippo.unifile.UniFile
import me.zhanghai.android.libarchive.ArchiveException
import tachiyomi.core.common.storage.openFileDescriptor
import java.io.Closeable
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    val size = pfd.statSize
    val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    // SY -->
    var encrypted: Boolean = false
        private set
    var wrongPassword: Boolean? = null
        private set
    val archiveHashCode = pfd.hashCode()

    // 引用计数：mmap 的地址是「裸指针」，libarchive 的 readOpenMemoryUnsafe 不接管内存所有权，
    // 何时 munmap 完全由这里决定。**reader 自身先持 1 份引用**，每派生一个 ArchiveInputStream
    // +1、流关闭时 -1，**归零才真正 munmap**。
    //
    // 为什么 reader 必须自己持一份（而不是只数「当前打开的流」）：
    //   1) `useEntries {}` 结束后外层还会用同一个 reader 继续读 —— 上传路径先列条目
    //      （useEntries）再逐页 getInputStream；LocalSource 取封面同理。只数流的话，
    //      useEntries 一结束就 munmap，后面的 getInputStream 读已释放内存 → SIGSEGV。
    //   2) 阅读器的 ReaderPage.stream 是**惰性闭包**，getPages() 返回后才真正读流，
    //      recycle() → close() 时可能有解码中的流还没关。
    // 有 reader 这一份兜底，「reader 还活着 ⇒ mmap 一定活着」；close() 之后再等残留的流
    // 关完，最后一个 release() 时才 munmap。
    private val openStreams = java.util.concurrent.atomic.AtomicInteger(1)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val munmapped = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        checkEncryptionStatus()
    }
    // SY <--

    inline fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = ArchiveInputStream(
        address,
        size,
        // SY -->
        encrypted,
        // SY <--
        // SY -->
        onClose = ::release,
        onOpen = ::acquire,
        // SY <--
    ).use { block(generateSequence { it.getNextEntry() }) }

    fun getInputStream(entryName: String): InputStream? {
        val archive = ArchiveInputStream(
            address,
            size,
            /* SY --> */ encrypted, /* SY <-- */
            // SY -->
            onClose = ::release,
            onOpen = ::acquire,
            // SY <--
        )
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    // SY -->
    private fun checkEncryptionStatus() {
        // 探测流也参与引用计数，保证 isPasswordIncorrect 内部 getInputStream 的 release 不会
        // 把 mmap 提前 munmap（此时外层探测流还在读）。
        val archive = ArchiveInputStream(address, size, false, onClose = ::release, onOpen = ::acquire)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.isEncrypted) {
                    encrypted = true
                    isPasswordIncorrect(entry.name)
                    break
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
    }

    private fun isPasswordIncorrect(entryName: String) {
        try {
            getInputStream(entryName).use { stream ->
                stream!!.read()
            }
        } catch (e: ArchiveException) {
            if (e.message == "Incorrect passphrase") {
                wrongPassword = true
                return
            }
            throw e
        }
        wrongPassword = false
    }
    // SY <--

    // SY -->
    // @PublishedApi internal：useEntries 是 inline 公共函数，需引用这两个函数。
    @PublishedApi
    internal fun acquire() {
        openStreams.incrementAndGet()
    }

    @PublishedApi
    internal fun release() {
        if (openStreams.decrementAndGet() == 0) {
            munmap()
        }
    }

    private fun munmap() {
        // 幂等：close() 与 release() 都可能走到这里，只允许真正 unmap 一次。
        if (!munmapped.compareAndSet(false, true)) return
        if (address != 0L) {
            Os.munmap(address, size)
        }
    }
    // SY <--

    override fun close() {
        // SY -->
        // 只释放「reader 自己那一份」引用；若还有派生流没关，等最后一个流 close() 时再 munmap。
        // closed 保证幂等：重复 close 不会把计数减成负数，也不会重复 munmap。
        if (!closed.compareAndSet(false, true)) return
        release()
        // SY <--
    }
}

fun UniFile.archiveReader(context: Context) = openFileDescriptor(context, "r").use { ArchiveReader(it) }
