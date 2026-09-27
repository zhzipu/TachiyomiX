package tachiyomi.source.network.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * WebDAV 库的目录约定。
 *
 * ```
 * <WebDAV 根>/TachiyomiX manga/          ← 固定根路径（图源里不可改，也不显示）
 *   ├── config.json                      ← 根索引：漫画名 → 漫画文件夹，见 [LibraryIndex]
 *   └── kR7pQ2/                          ← 漫画文件夹，随机字符串命名；只是个 ID，不显示给用户
 *        ├── config.json                 ← 本文件
 *        ├── cover.jpg                   ← 封面，文件名由 config.cover 指定
 *        └── No.0001/                    ← 章节文件夹，**递增序号**命名
 *             ├── 001.jpg                 ← 该话的图片，**一页一个文件**，按文件名自然序排列
 *             ├── 002.jpg
 *             └── 003.jpg
 * ```
 *
 * ## 为什么一页一个文件，而不是打成一个压缩包
 *
 * 早先每话打成一个 `image.cbz`，看起来更整齐，但**在线阅读被卡死了**：
 * 本图源读压缩包要靠 libarchive，而它必须先把整个包 mmap 到本地（见 `NetworkSource` 类注释），
 * 于是「点开一话」的第一步就变成「把整包下完」，第一页要等几十 MB 传完才出现，
 * 缓存里也直接占掉一整话的体积。
 *
 * 改成图片目录之后：页表来自一次 PROPFIND，每一页各自 GET —— 第一页几乎立刻可见，
 * 弱网/移动网下也不会为了看第一页先烧掉整话流量。代价是文件数变多（一话几十个），
 * 这一层由服务端承担，换来的是秒开。
 *
 * 压缩包形态仍然**读得动**（只读兼容）：老数据里 `chapters[].archive` 指向的包会照旧打开
 * （先下整包再解），新上传一律写图片。所以 [ChapterConfig.archive] 现在只是兼容字段。
 *
 * ## 为什么根目录要一份索引
 *
 * 漫画文件夹名是随机串，没法反推漫画名，所以「有哪些漫画」这件事由根目录的
 * `config.json`（[LibraryIndex]）说了算。它也顺带解决了「根目录混进无关文件夹」
 * 的问题：**不在索引里的目录一律不认**，不用再靠猜（以前是靠「有没有 config.json /
 * 章节目录」来判断）。
 *
 * ## 章节文件夹为什么用递增序号
 *
 * `No.0001`、`No.0002`… 只是**存放位置的编号**，跟章节号无关（章节可能没有章节号、
 * 也可能两话同号）。章节名与章节号记在下面的 `chapters[]` 里，一一对应的是
 * `chapters[].folder`。序号在一本漫画内部递增，重复上传同一话会**复用原来的文件夹**，
 * 不会越传越乱（见 `UploadManager`）。
 *
 * ## 字段
 *
 * ```json
 * {
 *   "name": "漫画名",
 *   "cover": "cover.jpg",
 *   "author": "作者",
 *   "description": "简介……",
 *   "tags": ["标签A", "标签B"],
 *   "chapters": [
 *     { "title": "第01话", "number": 1, "folder": "No.0001" }
 *   ]
 * }
 * ```
 *
 * - `name`：显示用的漫画名。
 * - `cover`：封面文件，相对**漫画文件夹**的路径；缺省时退回漫画文件夹里的第一张图片。
 * - `author`：作者。
 * - `description`：简介，可以带换行。
 * - `tags`：标签/分类，字符串数组。别名 `genres`（有人会顺手写这个），两者会合并去重。
 * - `chapters[].folder`：章节文件夹名（形如 `No.0001`），**一话的图片就在里面**。
 *   **不写 `folder` = 这一话在服务器上还没有内容**（本机没下载、或者还没传上来）。
 *   这种条目照样会被列进章节列表（顺序按章节号），但读取端给它的地址是
 *   [PENDING_CHAPTER_URL_PREFIX] 开头的**虚拟地址** —— 上层据此置灰、点开提示「无数据」。
 * - `chapters[].title` / `name`：章节名，两者取其一（`title` 优先）。
 * - `chapters[].number`：章节号，缺省时按 [eu.kanade.tachiyomi.domain.chapter.service.ChapterRecognition] 从标题解析。
 *
 * 作者 / 简介 / 标签由**上传端**（`UploadManager`）从本地书架里的漫画元数据写入，
 * 读取端（[tachiyomi.source.network.NetworkSource]）再填回
 * [eu.kanade.tachiyomi.source.model.SManga]，这样一本漫画在两端看到的信息一致。
 * 手写这几项也完全可以，上传时**不会覆盖非空的原值**（本地没有元数据时保留服务器上已有的）。
 *
 * **`chapters` 里是这本漫画的「全部章节」**：上传过的写 `folder`（有内容），
 * 没上传的只写章节名 / 章节号（没有内容，界面上置灰）。上传端会从本地书架的章节列表补齐，
 * 所以图源里能一眼看到「这本一共多少话、我传了几话」。
 * 整个列表缺省（或为空）时，图源会退化成「扫描漫画文件夹下的
 * `No.NNNN` 章节文件夹，把里面的图片按自然序当作一话的页」，用**章节文件夹名**当章节名，
 * 这样即使 `config.json` 写坏了，漫画也不会整个打不开。
 *
 * 解析时 [ConfigJson] 打开了 `ignoreUnknownKeys`，所以将来往 `config.json` 里加字段
 * 不会让老版本图源解析失败。
 */
@Serializable
data class MangaConfig(
    @SerialName("name") val name: String = "",
    @SerialName("cover") val cover: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    /** [tags] 的别名：手写配置时写 `genres` 一样认（两者会合并去重）。 */
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("chapters") val chapters: List<ChapterConfig> = emptyList(),
) {
    val displayName: String? get() = name.trim().takeIf { it.isNotEmpty() }

    val coverPath: String? get() = cover?.trim()?.takeIf { it.isNotEmpty() }

    /** 作者；空串按「没写」处理。 */
    val authorName: String? get() = author?.trim()?.takeIf { it.isNotEmpty() }

    /** 简介；保留内部的换行与空白，只去掉首尾。 */
    val synopsis: String? get() = description?.trim()?.takeIf { it.isNotEmpty() }

    /** 标签：`tags` 与别名 `genres` 合并、去空白、去重（顺序按先 `tags` 后 `genres`）。 */
    val allTags: List<String>
        get() = (tags + genres)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/**
 * `config.json` 里的一话。
 *
 * `name` 是 `title` 的别名，只是为了让别人手写的配置也能直接用，不是两套语义。
 */
@Serializable
data class ChapterConfig(
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("number") val number: Double? = null,
    /** 章节文件夹名，形如 `No.0001`；一话的图片就在这个文件夹里。 */
    @SerialName("folder") val folder: String? = null,
) {
    val displayTitle: String?
        get() = title?.trim()?.takeIf { it.isNotEmpty() } ?: name?.trim()?.takeIf { it.isNotEmpty() }

    /** 章节文件夹名；只取最后一段，防止配置里写成 `a/b` 这种。 */
    val chapterFolder: String?
        get() = folder?.trim()?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    /**
     * 合并 config 条目时用的**身份键**。
     *
     * 优先用章节号 —— 同一话最稳定的标识（本地改名、换扫描组都不影响它），
     * 没有章节号时退回标题，再退回章节文件夹（手写的极简配置可能只有 folder）。
     * 三项都没有的条目（既是空章节名又没有位置）返回 null，合并时会被丢掉。
     */
    val identity: String?
        get() = when {
            number != null -> "n:$number"
            displayTitle != null -> "t:$displayTitle"
            chapterFolder != null -> "f:$chapterFolder"
            else -> null
        }
}

/**
 * 容错解析：未知字段直接忽略，字段类型不对也不要把整本漫画弄丢。
 */
val ConfigJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * 写出 `config.json` 用的编码器。
 *
 * 与 [ConfigJson] 字段名完全一致，区别只在输出形态：缩进排版、省略 null 字段，
 * 让生成出来的文件跟上面文档里手写的样子一样，方便用户直接改。
 * （`prettyPrint` 只影响编码，即使不共用也绝不会影响解析。）
 */
val ConfigJsonEncoder: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 库根目录与漫画文件夹里约定的配置文件名。
 */
const val CONFIG_FILE_NAME = "config.json"

/** 章节文件夹名的前缀。`No.` + 四位补零序号。 */
const val CHAPTER_FOLDER_PREFIX = "No."

/** 章节文件夹序号的位数（`No.0001`）。 */
const val CHAPTER_FOLDER_DIGITS = 4

/**
 * 「未上传」章节的地址前缀（`pending://…`）。
 *
 * `config.json` 里那种**只写了章节名 / 章节号、没有 `folder`** 的条目（见
 * [ChapterConfig]），表示「这一话在服务器上还没有内容」。读取端仍然把它列进章节列表
 * （用户要求：未上传的章节也按顺序显示出来），但不能给它一个真实路径 —— 于是给它
 * 一个以这个前缀开头的**虚拟地址**，上层据此认出「没有数据」：置灰、点开提示「无数据」。
 *
 * 为什么不用空字符串当标记：章节在同步时是**按 `url` 匹配**的
 * （`SyncChaptersWithSource` 里 `distinctBy { it.url }`、`dbChapters.find { it.url == chapter.url }`），
 * 几十个未上传章节如果 url 全是空串，会被当成同一话合并掉，只剩一条。
 * 所以虚拟地址必须**每话唯一**。
 */
const val PENDING_CHAPTER_URL_PREFIX = "pending://"

/** 这个章节地址是不是「未上传（没有数据）」的虚拟地址。 */
fun String.isPendingChapterUrl(): Boolean = startsWith(PENDING_CHAPTER_URL_PREFIX)

/** 按递增序号生成章节文件夹名：`1 -> No.0001`。 */
fun chapterFolderName(index: Int): String =
    CHAPTER_FOLDER_PREFIX + "%0${CHAPTER_FOLDER_DIGITS}d".format(Locale.ENGLISH, index.coerceAtLeast(0))

/**
 * 从章节文件夹名反解序号；不是这个格式就返回 null。
 *
 * 只认严格形态（前缀 + 全是数字），避免把用户随手放进去的其它目录当章节。
 */
fun chapterFolderIndex(name: String): Int? {
    if (!name.startsWith(CHAPTER_FOLDER_PREFIX)) return null
    val digits = name.removePrefix(CHAPTER_FOLDER_PREFIX)
    return digits.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
}
