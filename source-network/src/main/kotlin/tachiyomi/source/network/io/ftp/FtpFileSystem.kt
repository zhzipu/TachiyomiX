package tachiyomi.source.network.io.ftp

import kotlinx.coroutines.sync.Semaphore
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.source.network.io.RemoteAuthException
import tachiyomi.source.network.io.RemoteEntry
import tachiyomi.source.network.io.RemoteFile
import tachiyomi.source.network.io.RemoteFileSystem
import tachiyomi.source.network.io.RemoteUnreachableException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * FTP 版 [RemoteFileSystem]。
 *
 * 目录约定、读取、上传全部与 WebDAV 版**完全一致** —— 这一层只负责「列目录 / 取流 /
 * 写流 / 建目录」四个原语，上层（[tachiyomi.source.network.NetworkSource] 的目录树映射、
 * `UploadManager` 的上传）一行都不用改。所以 FTP 和 WebDAV 之间可以随时切换，
 * 服务器上的 `TachiyomiX manga/` 结构一模一样。
 *
 * ## 与 WebDAV 版的几处必然差异
 *
 * - **被动模式**：主动模式要服务器反过来连客户端，手机在 NAT / 移动网络后面基本连不通，
 *   所以固定 `PASV`。
 * - **列目录**：FTP 没有 WebDAV 那种结构化响应。优先 `MLSD`（RFC 3659，名字/类型/大小/时间
 *   都是结构化的），服务器不支持时回落到解析 `LIST` 文本。
 * - **一次连接只能干一件事**：`RETR` 之后控制连接上还挂着一次未完成的传输，必须先收尾
 *   才能发下一条命令。所以 [open] 会**一直占着**这条连接直到调用方读完并关流。
 * - **[write] 的 `length` / `contentType` 用不上**：`STOR` 本身就是「读到 EOF 为止」，
 *   类型由连接的 `BINARY` 模式决定。接口保留这两个参数是为了跟 WebDAV 版一致。
 *
 * @param baseUrl 归一化后的根地址，形如 `ftp://host:2121/TachiyomiX%20manga`，**编码过、无尾斜杠**
 * @param basePath 归一化后的根路径，形如 `/TachiyomiX manga`，**解码态、无尾斜杠**
 * @param host 主机（IPv6 已去方括号）
 * @param port 端口
 */
class FtpFileSystem(
    private val baseUrl: String,
    private val basePath: String,
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteFileSystem {

    private val pool = ftpConnectionPools
        .computeIfAbsent(PoolKey(baseUrl, host, port, username, password)) { ConnectionPool() }

    override suspend fun list(path: String): List<RemoteEntry> {
        val client = pool.obtain(::connect)
        try {
            return withIOContext { listOn(client, path) }
        } finally {
            pool.release(client)
        }
    }

    override suspend fun open(path: String): RemoteFile {
        val client = pool.obtain(::connect)
        var handedOff = false
        try {
            val filePath = ftpPath(path)

            // 大小必须在**开始取流之前**问：`retrieveFileStream` 之后控制连接上已经有一次
            // 未完成的传输，这时再发别的命令会让应答串位（commons-net 明确要求先读完再发命令）。
            // 拿不到就算了（下载进度会退化成「不知道总大小」），不影响取流。
            val length = withIOContext {
                runCatching { client.mlistFile(filePath)?.size }
                    .getOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
            }

            val stream = withIOContext {
                client.retrieveFileStream(filePath) ?: throw mapError(client, "RETR $filePath")
            }

            handedOff = true
            return RemoteFile(
                // 关这个流会把连接收尾并还回池子，符合 RemoteFileSystem.open 的约定
                stream = PooledInputStream(client, stream),
                contentLength = length,
                contentType = null,
            )
        } finally {
            if (!handedOff) pool.release(client)
        }
    }

    override suspend fun write(
        path: String,
        body: () -> InputStream,
        length: Long,
        contentType: String?,
    ) {
        val client = pool.obtain(::connect)
        try {
            withIOContext {
                val filePath = ftpPath(path)
                // `STOR` 天然覆盖同名文件，不需要先删。
                // `body` 是「产出流的工厂」，这里只取一次 —— HTTP 层那套「重试会重写请求体」
                // 的顾虑在 FTP 上不存在（一次 STOR 就是一次完整传输）。
                val stored = body().use { client.storeFile(filePath, it) }
                if (!stored) throw mapError(client, "STOR $filePath")
            }
        } finally {
            pool.release(client)
        }
    }

    override suspend fun makeDirectory(path: String) {
        val client = pool.obtain(::connect)
        try {
            withIOContext {
                // 根目录（basePath）自己也要建，和 WebDAV 版一致
                makeDirectoryOn(client, basePath)

                var current = basePath
                path.trim('/')
                    .split('/')
                    .filter { it.isNotEmpty() }
                    .forEach { segment ->
                        current = "$current/$segment"
                        makeDirectoryOn(client, current)
                    }
            }
        } finally {
            pool.release(client)
        }
    }

    override fun urlOf(path: String): String {
        val encoded = path.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
        return if (encoded.isEmpty()) baseUrl else "$baseUrl/$encoded"
    }

    /**
     * 递归删除。
     *
     * FTP 没有「删非空目录」这东西：`RMD` 在目录非空时只会回 550，
     * 所以必须自己先深度优先把子项删光，再删目录本身。
     *
     * 而且要先按文件清、再按目录清，**不能**按 [list] 拿到的 `isDirectory` 复用
     * 「先删文件再删目录」以外的任何优化 —— 递归结构里每一层都要重新列一次。
     *
     * 空串表示根路径本身：把 `TachiyomiX manga` 连内容一起删掉，这是「清除 WebDAV 内容」要的。
     * 删完**不重建**根目录 —— 下一次上传时 [makeDirectory] 会自己建回来（它是幂等的）。
     */
    override suspend fun delete(path: String) {
        val target = ftpPath(path)
        // 根路径本身也是可以删的（basePath 是图源自己的库目录，不是 FTP 账号的家目录）
        if (target.isEmpty() || target == "/") return

        val client = pool.obtain(::connect)
        try {
            withIOContext { deleteOn(client, target) }
        } finally {
            pool.release(client)
        }
    }

    /**
     * 删掉 [ftpDir]（绝对路径）及其全部内容。
     *
     * 「本来就不在」当成功：递归删到一半时某些条目可能刚好被外部删掉，报错没有意义。
     */
    private fun deleteOn(client: FTPClient, ftpTarget: String) {
        val children = runCatching { listFilesOn(client, ftpTarget) }.getOrNull()
        if (children != null) {
            for (child in children) {
                val name = child.name ?: continue
                if (name == "." || name == "..") continue
                val childPath = "$ftpTarget/$name"
                if (child.isDirectory) {
                    // 软链接目录（isDirectory 为 false 但删起来像目录）会在下面落到 removeFile 分支，
                    // FTPFile.isDirectory 对符号链接的判断本身就不一致，不做额外处理。
                    deleteOn(client, childPath)
                    if (!client.removeDirectory(childPath)) {
                        // 目录里可能有服务器没列出来的东西（权限受限等），报出去比静默留着好
                        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                            throw mapError(client, "RMD $childPath")
                        }
                    }
                } else {
                    if (!client.deleteFile(childPath)) {
                        // 已经不在 → 550 且文件确实列不到了，当成成功；其它回码照抛
                        val stillThere = runCatching {
                            listFilesOn(client, ftpTarget).any { it.name == name }
                        }.getOrDefault(false)
                        if (stillThere) throw mapError(client, "DELE $childPath")
                    }
                }
            }
        }

        // 目录自己：不在（列不出来）也当成功
        if (!client.removeDirectory(ftpTarget)) {
            val exists = runCatching { client.changeWorkingDirectory(ftpTarget) }.getOrDefault(false)
            if (exists) throw mapError(client, "RMD $ftpTarget")
        }
    }

    // 连接

    /**
     * 新建一条已登录、已切到二进制 + 被动模式的连接。
     *
     * 认证失败与「连不上」要分开报（设置页的「测试连接」靠它给不同的结论）：
     * 连不上 → [RemoteUnreachableException]；登录被拒 → [RemoteAuthException]。
     */
    private fun connect(): FTPClient {
        val client = FTPClient()

        // 控制连接的编码必须在 connect 之前设好：登录用的 USER/PASS 就是按它编的。
        // 我们自己的目录名都是 ASCII，只有用户填的共享目录名可能带非 ASCII 字符。
        client.setControlEncoding(Charsets.UTF_8.name())
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.defaultTimeout = READ_TIMEOUT_MS
        // 数据连接的读超时是 `Duration` 版本的重载（commons-net 3.7+ 把 int 那版废弃了，
        // Kotlin 的属性语法只会挑到新的那个，直接赋 Int 会报类型不匹配）
        client.dataTimeout = Duration.ofMillis(READ_TIMEOUT_MS.toLong())

        try {
            client.connect(host, port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw RemoteUnreachableException(
                    IOException("FTP connect $host:$port -> ${client.replyString?.trim().orEmpty()}"),
                    code = client.replyCode,
                )
            }

            val user = username.ifBlank { ANONYMOUS }
            val pass = if (username.isBlank()) ANONYMOUS else password
            val loggedIn = try {
                client.login(user, pass)
            } catch (e: IOException) {
                // 有的服务器用「直接断开连接」表示认证失败（回码 530）
                if (client.replyCode == AUTH_FAILED_CODE) throw RemoteAuthException() else throw e
            }
            if (!loggedIn) throw RemoteAuthException()

            // 被动模式，理由见类注释
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.setListHiddenFiles(false)
            // 让服务端也按 UTF-8 解释路径。不支持这条命令的服务器会回 5xx，
            // 那也没关系 —— 忽略结果即可（路径里的非 ASCII 会退回服务端默认编码）。
            client.sendCommand("OPTS", "UTF8 ON")

            return client
        } catch (e: Throwable) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    // 单条连接上的操作

    private fun listOn(client: FTPClient, path: String): List<RemoteEntry> {
        val parent = path.trim('/')
        return listFilesOn(client, ftpPath(path)).mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            if (name == "." || name == "..") return@mapNotNull null
            RemoteEntry(
                name = name,
                // 相对根路径的路径，跟 WebDAV 版保持一致
                path = if (parent.isEmpty()) name else "$parent/$name",
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.size.coerceAtLeast(0L),
                lastModified = file.timestamp?.timeInMillis ?: 0L,
            )
        }
    }

    /**
     * 列出目录内容。
     *
     * 优先 `MLSD`：它返回结构化的名字/类型/大小/时间，不用猜 `LIST` 的文本格式
     * （不同服务器、不同语言环境的格式都不一样）。部分服务器（尤其 Windows 自带的 FTP）
     * 不支持，就回落到 `LIST` 解析。
     */
    private fun listFilesOn(client: FTPClient, dir: String): List<FTPFile> {
        var failure: IOException? = null

        try {
            val entries = client.mlistDir(dir)
            if (entries != null && entries.isNotEmpty()) return entries.toList()
        } catch (e: IOException) {
            failure = e
        }

        try {
            val entries = client.listFiles(dir)
            if (entries != null) return entries.toList()
        } catch (e: IOException) {
            failure = e
        }

        // 空目录时上面两条都会「成功但没有条目」，所以只有回复码不是肯定时才当失败
        if (FTPReply.isPositiveCompletion(client.replyCode) || FTPReply.isPositivePreliminary(client.replyCode)) {
            return emptyList()
        }
        throw failure?.let { RemoteUnreachableException(it, code = client.replyCode) }
            ?: mapError(client, "LIST $dir")
    }

    /**
     * 建单层目录，**幂等**。
     *
     * FTP 没有 MKCOL 那种「已存在」的明确回码：绝大多数服务器就是 550。
     * 用 `CWD` 探一下，进得去就当成功。我们所有操作都用绝对路径，
     * 所以 `CWD` 改掉工作目录这个副作用不影响后面的命令。
     */
    private fun makeDirectoryOn(client: FTPClient, dir: String) {
        if (dir.isEmpty() || dir == "/") return
        if (client.makeDirectory(dir)) return
        if (runCatching { client.changeWorkingDirectory(dir) }.getOrDefault(false)) return
        throw mapError(client, "MKD $dir")
    }

    /** 相对根路径 → FTP 上的绝对路径（解码态；编码由控制连接的 encoding 决定）。 */
    private fun ftpPath(path: String): String {
        val relative = path.trim('/')
        return if (relative.isEmpty()) basePath else "$basePath/$relative"
    }

    /**
     * 把否定应答翻成异常。
     *
     * 回码写进 [RemoteUnreachableException.code]（那里原本的意义是 HTTP 状态码，
     * 在 FTP 下就是 FTP 回复码），消息里带上命令和回复原文 —— 设置页的「测试连接」
     * 和 `<存储位置>/logs/` 里的日志直接用得上。
     */
    private fun mapError(client: FTPClient, what: String): Exception {
        val reply = client.replyCode
        if (reply == AUTH_FAILED_CODE) return RemoteAuthException()
        val text = client.replyString?.trim().orEmpty()
        return RemoteUnreachableException(IOException("$what -> $reply $text"), code = reply)
    }

    /**
     * 把「读完就还连接」挂在流的 `close()` 上。
     *
     * `RETR` 在控制连接上留了一次未完成的传输：不调 [FTPClient.completePendingCommand]
     * 就发下一条命令的话，拿到的应答会串位（得到的是上一次的），这条连接基本就废了。
     * 所以收尾失败时**不能再放回池子**，直接丢掉重连。
     */
    private inner class PooledInputStream(
        private val client: FTPClient,
        stream: InputStream,
    ) : FilterInputStream(stream) {

        private var finished = false

        override fun close() {
            if (finished) return
            finished = true
            try {
                super.close()
            } finally {
                val completed = runCatching { client.completePendingCommand() }.getOrDefault(false)
                pool.release(client, reusable = completed)
            }
        }
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
}

/**
 * 一个「连接参数」对应一份连接池。
 *
 * ### 为什么必须是**进程级**的
 *
 * `NetworkSource.fileSystem()` **每次调用都会 new 一个 [FtpFileSystem]** —— 对 WebDAV
 * 那样做没问题（OkHttp 自己管连接池，实例本身无状态），但 FTP 的 TCP 连接是握在
 * 实例手里的。按实例建池的话，每读一页都会新建一条连接然后永远不关，几十页就能把
 * 服务器连爆（FTP 服务器普遍限制同一账号的并发连接数，超了直接拒绝新连接）。
 * 按连接参数共用一份，顺带让阅读器 / 下载器 / 上传复用同几条连接。
 *
 * 池子不主动清理：换了服务器地址就会多一个桶，但里面闲置的连接会被服务器在超时后
 * 断开，下次取用时的 `NOOP` 探测会把它丢掉。实际最多也就多出几条闲置连接。
 */
private val ftpConnectionPools = ConcurrentHashMap<PoolKey, ConnectionPool>()

private class PoolKey(
    val baseUrl: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

private class ConnectionPool {

    private val permits = Semaphore(MAX_CONNECTIONS)
    private val idle = ArrayDeque<FTPClient>()
    private val lock = Any()

    suspend fun obtain(factory: () -> FTPClient): FTPClient {
        permits.acquire()

        while (true) {
            // `pollLast` 用 `java.util.ArrayDeque` 自己的方法：`removeLastOrNull` 是 Kotlin 给
            // 标准库那个 ArrayDeque 的扩展，对 java.util 的这份不一定有。
            val reused = synchronized(lock) { idle.pollLast() } ?: break
            // 空闲久了会被服务器单方面断开，所以复用前先探一下
            if (reused.isConnected && runCatching { reused.sendNoOp() }.getOrDefault(false)) {
                return reused
            }
            runCatching { reused.disconnect() }
        }

        return try {
            factory()
        } catch (e: Throwable) {
            permits.release()
            throw e
        }
    }

    fun release(client: FTPClient, reusable: Boolean = true) {
        val kept = reusable && synchronized(lock) {
            if (client.isConnected && idle.size < MAX_CONNECTIONS) {
                idle.addLast(client)
                true
            } else {
                false
            }
        }
        if (!kept) runCatching { client.disconnect() }
        permits.release()
    }
}

/**
 * 同一时间最多开几条连接。
 *
 * 上限来自 FTP 服务器一侧：同一账号的并发连接数普遍被限制在 5 左右，开多了会被拒。
 * 又不能太小：[FtpFileSystem.open] 会一直占着连接直到读完并关流，池子太小会让
 * 「列目录」排在正在读的图片后面。4 是这两者之间的折中。
 */
private const val MAX_CONNECTIONS = 4

private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 30_000

/** 认证失败的标准回码（Not logged in）。 */
private const val AUTH_FAILED_CODE = 530

private const val ANONYMOUS = "anonymous"
