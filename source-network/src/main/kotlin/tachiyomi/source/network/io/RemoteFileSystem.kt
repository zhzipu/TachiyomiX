package tachiyomi.source.network.io

import java.io.InputStream

/**
 * 远端文件系统抽象，对应本地图源的 `LocalSourceFileSystem`。
 *
 * 这一层刻意做得跟本地那套一样薄：只暴露「列目录」和「取流」两个动作，
 * 让上层 [tachiyomi.source.network.NetworkSource] 的目录树映射逻辑可以照搬本地图源。
 * 换协议（WebDAV / FTP）就是换一个实现，上层完全不用动。
 */
interface RemoteFileSystem {

    /**
     * 列出 [path]（相对根路径，空串表示根目录）下的直接子条目。
     *
     * @throws RemoteAuthException 认证失败
     * @throws RemoteUnreachableException 网络不可达或服务端返回错误
     */
    suspend fun list(path: String): List<RemoteEntry>

    /**
     * 把 [path] 的读取流打开。
     *
     * **调用方负责关闭** [RemoteFile.stream]：关闭它同时会释放底层连接，
     * 所以实现必须把连接的清理挂在流的 `close()` 上。
     */
    suspend fun open(path: String): RemoteFile

    /**
     * 把 [body] 产出的字节整体写到 [path]，已存在则覆盖。**父目录必须已存在**
     * （需要新建目录先调 [makeDirectory]）。
     *
     * [body] 是「产出流的工厂」而不是现成的流：HTTP 层在重试或重定向时会**重复写一次请求体**，
     * 这就要求每次都能拿到一个全新的流。
     *
     * @param length 字节数；能算出来就一定要给，未知时传 -1
     * @param contentType MIME 类型，未知时为 null
     *
     * @throws RemoteAuthException 认证失败
     * @throws RemoteUnreachableException 网络不可达或服务端返回错误
     */
    suspend fun write(
        path: String,
        body: () -> InputStream,
        length: Long,
        contentType: String?,
    )

    /**
     * 递归创建目录，**幂等**：已经存在不算失败。
     *
     * 空串表示「根路径本身」（[urlOf] 里已经带上了根目录名）。
     *
     * @throws RemoteAuthException 认证失败
     * @throws RemoteUnreachableException 网络不可达或服务端返回错误
     */
    suspend fun makeDirectory(path: String)

    /**
     * 删除 [path]，**递归**：目录连里面的东西一起删掉。
     *
     * 只给「清除 WebDAV 内容」用（图源设置页里那一项）：上传本身从不删东西，
     * 覆盖同名文件是靠 [write]。
     *
     * 路径不存在**不算失败**（幂等）—— 清库跑第二遍时目录已经没了，
     * 不该报错给用户看。
     *
     * 空串表示「根路径本身」（[urlOf] 里已经带上了根目录名），
     * 也就是把整个 `TachiyomiX manga` 删掉。
     *
     * @throws RemoteAuthException 认证失败
     * @throws RemoteUnreachableException 网络不可达或服务端返回错误
     */
    suspend fun delete(path: String)

    /**
     * 把相对路径拼成可展示的完整地址。
     *
     * 页面用它当缓存键（[eu.kanade.tachiyomi.source.model.Page.imageUrl]），
     * 因此必须是稳定且唯一的，但**不要求真的能被外部直接访问**。
     */
    fun urlOf(path: String): String
}

/**
 * 一个已打开的远端文件。
 *
 * @property stream 文件内容；关闭时一并释放底层连接
 * @property contentLength 字节数，未知时为 0（供下载进度与分片续传判断）
 * @property contentType MIME 类型，未知时为 null
 */
class RemoteFile(
    val stream: InputStream,
    val contentLength: Long,
    val contentType: String?,
)

/** 认证失败（401/403）。由上层翻译成用户可读的文案。 */
class RemoteAuthException : Exception()

/**
 * 网络不可达，或服务端返回了非预期的状态码。
 *
 * @property code HTTP 状态码（拿得到时）；`404` 与「连不上」不是一回事 ——
 *   前者说明服务器是通的、只是这个路径不存在（比如库根目录还没建），
 *   连通性测试据此区分「连不上」和「库还没建立」。
 *   非 HTTP 层的失败（socket、DNS）为 null。
 */
class RemoteUnreachableException(
    cause: Throwable? = null,
    val code: Int? = null,
) : Exception(cause)
