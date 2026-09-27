package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.upload.isDownloadCategory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CategoryScreenModel(
    private val getCategories: GetCategories = Injekt.get(),
    private val createCategoryWithName: CreateCategoryWithName = Injekt.get(),
    private val deleteCategory: DeleteCategory = Injekt.get(),
    private val reorderCategory: ReorderCategory = Injekt.get(),
    private val renameCategory: RenameCategory = Injekt.get(),
) : StateScreenModel<CategoryScreenState>(CategoryScreenState.Loading) {

    private val _events: Channel<CategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            getCategories.subscribe()
                .collectLatest { categories ->
                    mutableState.update {
                        CategoryScreenState.Success(
                            categories = categories
                                .filterNot(Category::isSystemCategory),
                        )
                    }
                }
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launch {
            when (createCategoryWithName.await(name)) {
                is CreateCategoryWithName.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun deleteCategory(categoryId: Long) {
        // SY -->
        // 「下载」是下载模块的固定分类（用户明确要求：不可关闭、不可排序、不可删除）。
        // UI 上已经把它那一行的操作入口去掉了，这里再兜一道，避免批量操作或以后新增的
        // 调用路径绕过界面把它删掉。
        if (isDownloadCategoryId(categoryId)) return
        // SY <--
        screenModelScope.launch {
            when (deleteCategory.await(categoryId = categoryId)) {
                is DeleteCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    /**
     * 调整分类顺序。
     *
     * 「下载」**也允许**被拖动排序（用户要求：不可删除、不可重命名，但可以拖动排序）——
     * [ReorderCategory] 会把非系统分类整体按新顺序重写成 `order = 0, 1, 2…`，
     * 那个分类的 `order` 变成几都不影响它自己（它是靠名字认自己的）。
     */
    fun changeOrder(category: Category, newIndex: Int) {
        screenModelScope.launch {
            when (reorderCategory.await(category, newIndex)) {
                is ReorderCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun renameCategory(category: Category, name: String) {
        // SY -->
        // 「下载」靠名字认自己（`Category.isDownloadCategory` 比名字），改了名就认不出，
        // 下一次下载会再建一个「下载」出来、书架多出一个页签，所以名字也锁住。
        if (category.isDownloadCategory) return
        // SY <--
        screenModelScope.launch {
            when (renameCategory.await(category, name)) {
                is RenameCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    // SY -->
    /**
     * [deleteCategory] 只拿到 id，这里按 id 判断是不是那个固定分类。
     *
     * 直接看当前已渲染的那份列表 —— 跟界面用的是同一份数据，不额外查库。
     */
    private fun isDownloadCategoryId(categoryId: Long): Boolean =
        (mutableState.value as? CategoryScreenState.Success)
            ?.categories
            ?.any { it.id == categoryId && it.isDownloadCategory }
            ?: false
    // SY <--

    fun showDialog(dialog: CategoryDialog) {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed interface CategoryDialog {
    data object Create : CategoryDialog
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

sealed interface CategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : CategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

sealed interface CategoryScreenState {

    @Immutable
    data object Loading : CategoryScreenState

    @Immutable
    data class Success(
        val categories: List<Category>,
        val dialog: CategoryDialog? = null,
    ) : CategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()
    }
}
