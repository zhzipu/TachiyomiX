package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.upload.DownloadCategory
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GlobalSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String? = null,
    private val updateManga: UpdateManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    // 「下载=模块」的两条分类规则落在它身上，见 DownloadCategory.resolveUserSelection
    private val downloadCategory: DownloadCategory = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : SearchScreenModel(State(searchQuery = initialQuery)) {

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }
    }

    override fun getEnabledSources(): List<Source> {
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != SourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }

    private val _categoryChooserMangas = MutableStateFlow<List<Manga>?>(null)

    /** 待设置分类的作品，非空时显示设置分类对话框 */
    val categoryChooserMangas: StateFlow<List<Manga>?> = _categoryChooserMangas.asStateFlow()

    /** 分类列表（实时），编辑分类后对话框能立刻拿到新分类 */
    val categories: Flow<List<Category>> = getCategories.subscribe()
        .map { categories -> categories.filterNot { it.isSystemCategory } }

    /**
     * 把搜索结果加入书架，规则与图源列表一致：设置了默认分类就直接加入，
     * 否则返回需要用户选择分类。
     */
    suspend fun addToLibrary(manga: Manga): AddToLibraryResult {
        return addToLibrary(listOf(manga))
    }

    /** 批量把搜索结果加入书架，规则同单个作品 */
    suspend fun addToLibrary(mangas: List<Manga>): AddToLibraryResult {
        val toAdd = mangas.filterNot { it.favorite }
        if (toAdd.isEmpty()) return AddToLibraryResult.AlreadyInLibrary

        val categories = getCategories.await().filterNot { it.isSystemCategory }
        val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
        val defaultCategory = categories.firstOrNull { it.id == defaultCategoryId }

        return when {
            // 设置了默认分类
            defaultCategory != null -> {
                addToLibrary(toAdd, listOf(defaultCategory.id))
                AddToLibraryResult.Added
            }

            // 默认为“默认分类”（0）或没有任何分类
            defaultCategoryId == 0L || categories.isEmpty() -> {
                addToLibrary(toAdd, emptyList())
                AddToLibraryResult.Added
            }

            // 让用户选择分类
            else -> AddToLibraryResult.NeedsCategoryChoice
        }
    }

    /** 加入书架并归入选中的分类，[categoryIds] 为空时归入默认分类 */
    suspend fun addToLibrary(mangas: List<Manga>, categoryIds: List<Long>) {
        mangas.forEach { manga ->
            updateManga.awaitUpdateFavorite(manga.id, true)
            // 「下载=模块」的两条规则：只勾了「下载」要补「默认」；本来在「下载」里就保留
            // （见 DownloadCategory.resolveUserSelection）
            setMangaCategories.await(manga.id, downloadCategory.resolveUserSelection(manga.id, categoryIds))
        }
    }

    /** 移出书架（取消收藏） */
    suspend fun removeFromLibrary(manga: Manga) {
        updateManga.awaitUpdateFavorite(manga.id, false)
    }

    fun showCategoryChooser(mangas: List<Manga>) {
        _categoryChooserMangas.value = mangas
    }

    fun dismissCategoryChooser() {
        _categoryChooserMangas.value = null
    }
}

/** [GlobalSearchScreenModel.addToLibrary] 的结果 */
sealed interface AddToLibraryResult {
    /** 已加入书架 */
    data object Added : AddToLibraryResult

    /** 已经在书架中 */
    data object AlreadyInLibrary : AddToLibraryResult

    /** 需要用户选择要加入的分类 */
    data object NeedsCategoryChoice : AddToLibraryResult
}
