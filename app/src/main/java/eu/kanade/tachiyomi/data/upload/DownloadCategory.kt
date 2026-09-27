package eu.kanade.tachiyomi.data.upload

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 书架上常驻的「下载」分类。
 *
 * ## 为什么是个真实的数据库分类
 *
 * `CategoryRepository.getAllAsFlow()` 返回的是 `categories` 表里的全部行
 * （系统分类 `_id = 0` 是建表时就 `INSERT OR IGNORE` 进去的，也在里面），
 * 而 `LibraryScreenModel` 是拿这份列表当书架页签的 —— 所以只要表里有这一行，
 * 哪怕它一个项目都没有，页签也会一直显示。这就是「常驻」。
 *
 * ## 为什么名字是写死的「下载」
 *
 * 这个分类是**靠名字认自己**的（`Category.isDownloadCategory`）。名字一旦本地化，
 * 换个系统语言就找不到自己，于是会再建一个出来 —— 所以这里刻意不做本地化，
 * 就用需求里指定的「下载」两个字。
 *
 * 位置：系统分类的 `sort` 是 `-1`（见 `categories.sq`），所以新建时把自己设成 `0`，
 * 再把其余非系统分类整体后移一位，就能稳定地落在「默认」右侧。
 * 之后用户如果手动拖动排序，就尊重用户的选择，不再干预。
 *
 * ## 「下载」是模块，不是普通分类（用户明确要求）
 *
 * 它只是**以固定分类的形式出现**，运行逻辑跟别的分类不一样：
 *
 * - **成员关系由模块维护**：[addManga]（开始下载）进、[removeManga]（本地下载被删光）出。
 *   用户在界面上改分类**既进不来也出不去** —— 见 [resolveUserSelection]。
 * - **它是标记而不是归属**：所以收藏/加入书架时「只勾了下载」还会补上「默认」，
 *   否则这本漫画会从「默认」里消失。
 * - **名字写死不本地化**，靠名字认自己（删掉会自愈重建）。
 *
 * 布局上它仍然是一行 `categories` 记录，因为书架页签是拿这张表渲染的（见上面那段）。
 */
class DownloadCategory(
    private val categoryRepository: CategoryRepository = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    private val mutex = Mutex()

    @Volatile
    private var cachedId: Long? = null

    /**
     * 确保「下载」分类存在，返回它的 id。
     *
     * 分类被用户在「分类管理」里删掉之后，下一次调用会重新建出来（自愈）。
     * 建不出来（数据库异常）时返回 null，调用方按「没有分类」处理即可。
     */
    suspend fun ensureId(): Long? = mutex.withLock {
        cachedId?.let { return@withLock it }

        val categories = categoryRepository.getAll()
        categories.firstOrNull { it.isDownloadCategory }?.let { existing ->
            cachedId = existing.id
            return@withLock existing.id
        }

        create(categories).also { created -> cachedId = created }
    }

    /**
     * 把一本漫画**追加**进「下载」分类，**保留它原来所属的分类**。
     *
     * 幂等：已经在里面就直接返回。下载入口（详情页、书架多选、章节行）都最终走到
     * `DownloadManager.downloadChapters`，所以只要挂在那里，所有路径都会被覆盖。
     *
     * ### 为什么要补一个「默认」分类
     *
     * `setMangaCategories` 是**整体覆盖**（先 `deleteMangaCategoryByMangaId` 再逐条 insert），
     * 所以要自己把原有分类带上。而「默认」是**隐式**的：`libraryView.sq` 里写着
     * `coalesce(MC.categories, '0') AS categories` —— 一本没有**任何** `mangas_categories`
     * 行的漫画，其 `categories` 会被算成 `[0]`，也就是「只属于默认」。
     *
     * `categoryRepository.getCategoriesByMangaId` 读的是真实的行，这种情况会返回空列表。
     * 若直接拿它当「原有分类」，写法就变成 `[] + 下载` = `[下载]`，这本漫画的
     * `categories` 从「空」变成「非空」——**它会从「默认」里消失**，看起来就像被「移动」了。
     * 空列表时补上 [Category.UNCATEGORIZED_ID] 即可，语义正好是「它原本在默认里，留在那儿」。
     */
    suspend fun addManga(mangaId: Long) {
        try {
            val categoryId = ensureId() ?: return
            val current = categoryRepository.getCategoriesByMangaId(mangaId).map { it.id }
            if (categoryId in current) return
            val preserved = current.ifEmpty { listOf(Category.UNCATEGORIZED_ID) }
            setMangaCategories.await(mangaId, preserved + categoryId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to add manga $mangaId to the download category" }
        }
    }

    /** 让缓存失效（用户在分类管理里改过东西之后）。 */
    fun invalidate() {
        cachedId = null
    }

    /**
     * 只查不建：返回「下载」分类的 id，不存在时返回 null。
     *
     * 和 [ensureId] 的区别是**没有副作用**。用在「用户勾选了哪些分类」这种只读判定上 ——
     * 不该因为一次收藏动作就把这个分类凭空建出来。
     */
    suspend fun findId(): Long? = mutex.withLock {
        cachedId ?: categoryRepository.getAll()
            .firstOrNull { it.isDownloadCategory }
            ?.id
            ?.also { cachedId = it }
    }

    /**
     * 把用户在「设置分类 / 加入书架 / 收藏」里勾选的分类，规范化成最终要写库的集合。
     *
     * 两条规则，**都来自「下载」是独立模块这件事**：
     *
     * 1. **只勾了「下载」时补上「默认」**。「下载」只是「这本漫画有下载」的标记，
     *    不该成为一本漫画**唯一**的归属；不补的话这本漫画的 `mangas_categories`
     *    就只剩「下载」一行，于是它从「默认」里消失，看起来像被丢进了孤立分类。
     * 2. **用户没勾「下载」、但这本漫画本来就是模块成员时，保留它**。
     *    成员关系由下载模块维护（见 [addManga]），不由用户的分类操作决定 ——
     *    否则用户在书架上整理一次分类，就会把所有下载过的漫画踢出「下载」。
     *    想让它离开「下载」，唯一的出口是**删掉本地下载**（见 [removeManga]）。
     *
     * 勾了「下载 + 别的分类」时不用补「默认」——它已经有正常归属了。
     * 空集合也不动（那是「不指定分类」，语义上本来就是「只在默认里」）——
     * 除非它同时是模块成员，那时才需要显式带上「默认」，免得被「下载」挤出「默认」。
     *
     * @param mangaId 要改分类的漫画
     * @param userSelection 用户在界面上勾选的分类 id（原样，不做去重以外的加工）
     */
    suspend fun resolveUserSelection(mangaId: Long, userSelection: List<Long>): List<Long> {
        val downloadId = findId()
        val isMember = downloadId != null &&
            categoryRepository.getCategoriesByMangaId(mangaId).any { it.id == downloadId }

        // 规则 1：只勾了「下载」→ 补「默认」
        if (downloadId != null && userSelection.size == 1 && userSelection.first() == downloadId) {
            return listOf(Category.UNCATEGORIZED_ID, downloadId)
        }

        // 规则 2：本来是模块成员却被勾掉了 → 保留
        if (downloadId != null && isMember && downloadId !in userSelection) {
            return if (userSelection.isEmpty()) {
                listOf(Category.UNCATEGORIZED_ID, downloadId)
            } else {
                userSelection + downloadId
            }
        }

        return userSelection
    }

    /**
     * 把一本漫画移出「下载」分类，保留它其余的归属。
     *
     * 这是模块成员关系的**唯一出口**：本地下载被删光之后由 `DownloadManager` 调用。
     * 用户手动改分类是去不掉它的（见 [resolveUserSelection]）。
     */
    suspend fun removeManga(mangaId: Long) {
        try {
            val categoryId = findId() ?: return
            val current = categoryRepository.getCategoriesByMangaId(mangaId).map { it.id }
            if (categoryId !in current) return
            setMangaCategories.await(mangaId, current - categoryId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to remove manga $mangaId from the download category" }
        }
    }

    private suspend fun create(existing: List<Category>): Long? = try {
        val others = existing.filterNot { it.isSystemCategory }

        val newId = categoryRepository.insert(
            Category(id = 0L, name = NAME, order = 0L, flags = currentSortFlags()),
        )

        if (newId != null && others.isNotEmpty()) {
            // 把自己放在 sort = 0（紧挨着 sort = -1 的系统分类），
            // 其余分类整体后移一位腾位置。走 updatePartial 的批量重载，内部是一个事务。
            categoryRepository.updatePartial(
                others.map { CategoryUpdate(id = it.id, order = it.order + 1) },
            )
        }

        newId
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to create the download category" }
        null
    }

    /**
     * 新分类的排序标记，跟用户在「书架设置」里选的默认排序保持一致
     * —— 和 `CreateCategoryWithName` 的做法一样，避免这个分类的排序行为跟手动建出来的不一样。
     */
    private fun currentSortFlags(): Long {
        val sort = libraryPreferences.sortingMode.get()
        return sort.type.flag or sort.direction.flag
    }

    companion object {
        /**
         * 分类名。**刻意不本地化**，理由见类注释。
         */
        const val NAME = "下载"
    }
}

/**
 * 判断一个分类是不是「下载」分类。
 *
 * 系统分类（`_id = 0`）永远不算。
 */
val Category.isDownloadCategory: Boolean
    get() = !isSystemCategory && name == DownloadCategory.NAME
