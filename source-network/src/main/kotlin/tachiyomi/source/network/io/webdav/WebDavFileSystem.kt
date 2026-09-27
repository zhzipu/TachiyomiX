package tachiyomi.source.network.io.webdav

import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.source.network.io.RemoteAuthException
import tachiyomi.source.network.io.RemoteEntry
import tachiyomi.source.network.io.RemoteFile
import tachiyomi.source.network.io.RemoteFileSystem
import tachiyomi.source.network.io.RemoteUnreachableException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * WebDAV 版 [RemoteFileSystem]。
 *
 * 列目录用 `PROPFIND` + `Depth: 1`（返回 207 Multi-Status 的 XML），
 * 取内容用 `GET`，写内容用 `PUT`，建目录用 `MKCOL`（逐层、幂等）。
 * 全部走 OkHttp，因此不需要任何第三方 WebDAV 库；
 * 响应 XML 直接交给项目已有的 jsoup（xmlParser）解析，避免再引入 XML 序列化框架。
 *
 * @param client 复用的 OkHttp 客户端（由 [tachiyomi.source.network.NetworkSource] 传入其 `client`）
 * @param baseUrl 归一化后的根地址，形如 `https://host:port/dav/manga`，**无尾斜杠**
 * @param basePath 归一化后的根路径，形如 `/dav/manga`，**无尾斜杠**，服务部署在根目录时为空串
 */
class WebDavFileSystem(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val basePath: String,
    username: String,
    password: String,
) : RemoteFileSystem {

    private val authorization: String? =
        if (username.isBlank()) null else Credentials.basic(username, password)

    override suspend fun list(path: String): List<RemoteEntry> = withIOContext {
        val url = urlFor(path, trailingSlash = true)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY)
            .header("Depth", "1")
            .apply { authorization?.let { header("Authorization", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 403 -> throw RemoteAuthException()
                !response.isSuccessful -> throw RemoteUnreachableException(
                    IOException("PROPFIND $url -> HTTP ${response.code}"),
                    code = response.code,
                )
            }
            parseEntries(response.body.string(), url)
        }
    }

    override suspend fun open(path: String): RemoteFile = withIOContext {
        val url = urlFor(path, trailingSlash = false)
        val request = Request.Builder()
            .url(url)
            .apply { authorization?.let { header("Authorization", it) } }
            // 关掉透明 gzip，否则 body 长度会变成未知，下载进度和续传判断都会失效
            .header("Accept-Encoding", "identity")
            .build()

        val response = client.newCall(request).execute()
        try {
            when {
                response.code == 401 || response.code == 403 -> throw RemoteAuthException()
                !response.isSuccessful -> throw RemoteUnreachableException(
                    IOException("GET $url -> HTTP ${response.code}"),
                    code = response.code,
                )
            }
            val body = response.body
            RemoteFile(
                // 关闭这个流会一并释放底层连接，符合 RemoteFileSystem.open 的约定
                stream = body.source().inputStream(),
                contentLength = body.contentLength().coerceAtLeast(0L),
                contentType = body.contentType()?.toString(),
            )
        } catch (e: Throwable) {
            response.close()
            throw e
        }
    }

    override suspend fun write(
        path: String,
        body: () -> InputStream,
        length: Long,
        contentType: String?,
    ): Unit = withIOContext {
        val url = urlFor(path, trailingSlash = false)
        val requestBody = object : RequestBody() {
            override fun contentType(): MediaType? = contentType?.toMediaTypeOrNull()

            override fun contentLength(): Long = length

            override fun writeTo(sink: BufferedSink) {
                // 每次调用都重新取一个流：HTTP 层可能因为重定向/重试而重复写请求体
                body().source().use { source -> sink.writeAll(source) }
            }
        }

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .apply { authorization?.let { header("Authorization", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 403 -> throw RemoteAuthException()
                !response.isSuccessful -> throw RemoteUnreachableException(
                    IOException("PUT $url -> HTTP ${response.code}"),
                    code = response.code,
                )
            }
        }
    }

    override suspend fun makeDirectory(path: String): Unit = withIOContext {
        // 根目录本身也要建：baseUrl 里已经带上了根目录名，所以空串就代表它
        mkcol(urlFor("", trailingSlash = true))

        var current = ""
        path.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                current = if (current.isEmpty()) segment else "$current/$segment"
                mkcol(urlFor(current, trailingSlash = true))
            }
    }

    /**
     * 建单层目录。
     *
     * 「已存在」不是错误：RFC 4918 里服务端一般回 `405 Method Not Allowed`，
     * 少数实现会回 301/302 让客户端去已存在的目录，这几种都当作成功，
     * 这样 [makeDirectory] 才能是幂等的。
     */
    private fun mkcol(url: String) {
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .apply { authorization?.let { header("Authorization", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 403 -> throw RemoteAuthException()
                response.isSuccessful -> Unit // 201 Created
                response.code == 405 -> Unit // 已存在
                response.code == 301 || response.code == 302 -> Unit // 已存在（重定向到目录）
                else -> throw RemoteUnreachableException(
                    IOException("MKCOL $url -> HTTP ${response.code}"),
                    code = response.code,
                )
            }
        }
    }

    override fun urlOf(path: String): String = urlFor(path, trailingSlash = false)

    /**
     * 递归删除。
     *
     * WebDAV 的 `DELETE` 对**集合**天然是递归的（RFC 4918 §9.6：删除集合会删掉它的所有成员），
     * 所以这一条请求就够了，不用自己遍历目录树 —— 那反而会在文件多的时候打出一串请求。
     *
     * 「不存在」按成功处理（幂等），因为清库跑第二遍时目录本来就没了；
     * 但只认 `404`，其它非 2xx 一律当失败往上抛（尤其是 401/403 —— 那是「没删掉」，
     * 不能装作成功，否则用户以为清干净了、实际上一堆东西还在服务器上）。
     */
    override suspend fun delete(path: String): Unit = withIOContext {
        val url = urlFor(path, trailingSlash = true)
        val request = Request.Builder()
            .url(url)
            .delete()
            .apply { authorization?.let { header("Authorization", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 403 -> throw RemoteAuthException()
                response.isSuccessful -> Unit // 204 No Content / 200 OK
                response.code == 404 -> Unit // 已经没了
                else -> throw RemoteUnreachableException(
                    IOException("DELETE $url -> HTTP ${response.code}"),
                    code = response.code,
                )
            }
        }
    }

    // 解析
    private fun parseEntries(xml: String, requestUrl: String): List<RemoteEntry> {
        // Depth: 1 只会返回「请求的目录自身」+「直接子项」，所以按请求地址把目录自身滤掉即可
        val selfPath = absolutePathOf(requestUrl)

        return Jsoup.parse(xml, "", Parser.xmlParser())
            .allElements
            .asSequence()
            .filter { it.localName() == "response" }
            .mapNotNull { parseEntry(it) }
            .filter { it.absPath != selfPath }
            .map { it.entry }
            .toList()
    }

    private fun parseEntry(response: Element): ParsedEntry? {
        val href = response.firstByLocalName("href")?.text()?.trim().orEmpty()
        if (href.isEmpty()) return null

        val absPath = absolutePathOf(href)
        val relativePath = relativePathOf(absPath) ?: return null

        val name = response.firstByLocalName("displayname")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: relativePath.substringAfterLast('/').takeIf { it.isNotEmpty() }
            ?: return null

        return ParsedEntry(
            absPath = absPath,
            entry = RemoteEntry(
                name = name,
                path = relativePath,
                // resourcetype 里带 <d:collection/> 的就是目录
                isDirectory = response.firstByLocalName("collection") != null,
                size = response.firstByLocalName("getcontentlength")?.text()?.trim()?.toLongOrNull() ?: 0L,
                lastModified = response.firstByLocalName("getlastmodified")?.text()?.trim()?.let(::parseDate) ?: 0L,
            ),
        )
    }

    private class ParsedEntry(val absPath: String, val entry: RemoteEntry)

    // 地址换算

    /** 相对根路径的地址 → 完整 URL。目录一定要带尾斜杠，否则不少服务端不认。 */
    private fun urlFor(path: String, trailingSlash: Boolean): String {
        val encoded = path.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }

        val base = buildString {
            append(baseUrl)
            append('/')
            append(encoded)
        }
        return if (trailingSlash && !base.endsWith('/')) "$base/" else base
    }

    /** 取出 URL 或 href 里的解码后绝对路径，并去掉尾斜杠。 */
    private fun absolutePathOf(url: String): String {
        val raw = runCatching { URI(url).path }.getOrNull() ?: url
        return decodePercent(raw).trimEnd('/')
    }

    /** 绝对路径 → 相对根路径的地址；不属于根目录时返回 null。 */
    private fun relativePathOf(absPath: String): String? {
        val prefix = basePath.trimEnd('/')
        if (prefix.isEmpty()) return absPath.trim('/')
        if (absPath != prefix && !absPath.startsWith("$prefix/")) return null
        return absPath.removePrefix(prefix).trim('/')
    }

    // 小工具

    private fun Element.localName(): String = tagName().substringAfterLast(':')

    private fun Element.firstByLocalName(name: String): Element? =
        allElements.firstOrNull { it !== this && it.localName() == name }

    private fun parseDate(value: String): Long =
        runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(0L)

    private fun decodePercent(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()) }
            .getOrDefault(value)

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

        /**
         * 边写边报进度时的分块大小。
         *
         * 64 KiB：足够细（几百 KB 就能动一格进度），又不会让进度回调太频繁
         * —— OkHttp 自己的段大小是 8 KiB，按 8 KiB 回调会密十倍且没有意义。
         */
    }
}
