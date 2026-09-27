package tachiyomi.source.network.io

/**
 * 远端目录里的一个条目，对应本地图源里的一个 `UniFile`。
 *
 * @property name 文件/目录名（不含路径）
 * @property path 相对根路径的路径，不带前后斜杠；根目录自身为空串
 * @property isDirectory 是否为目录
 * @property size 字节数，目录或未知时为 0
 * @property lastModified 最后修改时间（毫秒），未知时为 0
 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
)
