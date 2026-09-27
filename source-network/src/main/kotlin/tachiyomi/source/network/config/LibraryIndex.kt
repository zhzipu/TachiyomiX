package tachiyomi.source.network.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 库根目录 `TachiyomiX manga/config.json` —— **漫画索引**。
 *
 * ```
 * TachiyomiX manga/
 *   ├── config.json      ← 本文件
 *   ├── kR7pQ2/          ← 漫画文件夹（随机字符串），里面有它自己的 config.json
 *   └── x9Lm3T/
 * ```
 *
 * ```json
 * {
 *   "mangas": [
 *     { "name": "漫画名", "folder": "kR7pQ2" }
 *   ]
 * }
 * ```
 *
 * ## 为什么需要它
 *
 * 漫画文件夹是随机字符串，光看目录名完全不知道是哪本漫画，也没法按漫画名找到它。
 * 所以「库里有哪些漫画」这份清单放在这里：**它是唯一的真相来源** ——
 * 不在索引里的目录，图源一律不认（这样根目录里混进别的文件夹也不会被当成漫画）。
 *
 * 上传时由 `UploadManager` 负责维护：新漫画加一条，已有漫画沿用原来的 folder。
 *
 * 用数组 + 字段而不是 `{"漫画名": "folder"}` 这种纯映射，是为了同名漫画不会互相覆盖，
 * 而且以后要加字段（封面、章节数…）不用改结构。
 */
@Serializable
data class LibraryIndex(
    @SerialName("mangas") val mangas: List<LibraryEntry> = emptyList(),
) {
    /** 按漫画名查一条；名字大小写与首尾空白都不敏感。 */
    fun find(name: String): LibraryEntry? {
        val target = name.trim()
        if (target.isEmpty()) return null
        return mangas.firstOrNull { it.displayName?.equals(target, ignoreCase = true) == true }
    }

    /** 加上（或替换）一条，返回新的索引；同名则覆盖 folder。 */
    fun upsert(name: String, folder: String): LibraryIndex {
        val target = name.trim()
        val entry = LibraryEntry(name = target, folder = folder.trim())
        val kept = mangas.filterNot { it.displayName?.equals(target, ignoreCase = true) == true }
        return copy(mangas = kept + entry)
    }
}

/** 索引里的一条：漫画名 → 漫画文件夹名。 */
@Serializable
data class LibraryEntry(
    @SerialName("name") val name: String = "",
    /** 漫画文件夹名（相对库根目录），形如 `kR7pQ2`。 */
    @SerialName("folder") val folder: String = "",
) {
    val displayName: String? get() = name.trim().takeIf { it.isNotEmpty() }

    /** 文件夹名；只取最后一段，防止写成 `a/b` 这种。 */
    val folderName: String? get() = folder.trim().trim('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
}
