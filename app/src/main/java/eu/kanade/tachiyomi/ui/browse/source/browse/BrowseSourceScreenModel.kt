package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.core.preference.asState
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.upload.DownloadCategory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.util.removeCovers
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.ExhPreferences
import exh.source.getMainSource
import exh.source.mangaDexSourceIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.interactor.ClearSearchHistory
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.interactor.GetSearchHistory
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.interactor.InsertSearchHistory
import tachiyomi.domain.source.model.EXHSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import java.time.Instant
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

open class BrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    // SY -->
    private val filtersJson: String? = null,
    private val savedSearch: Long? = null,
    // SY <--
    private val sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    getIncognitoState: GetIncognitoState = Injekt.get(),

    // SY -->
    exhPreferences: ExhPreferences = Injekt.get(),
    uiPreferences: UiPreferences = Injekt.get(),
    // 「下载=模块」的分类规则落在它身上，见 DownloadCategory.resolveUserSelection
    private val downloadCategory: DownloadCategory = Injekt.get(),
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    private val deleteSavedSearchById: DeleteSavedSearchById = Injekt.get(),
    private val insertSavedSearch: InsertSavedSearch = Injekt.get(),
    private val getExhSavedSearch: GetExhSavedSearch = Injekt.get(),
    private val getSearchHistory: GetSearchHistory = Injekt.get(),
    private val insertSearchHistory: InsertSearchHistory = Injekt.get(),
    private val clearSearchHistoryInteractor: ClearSearchHistory = Injekt.get(),
    // SY <--
) : StateScreenModel<BrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    // SY -->
    var browseSourceTags by sourcePreferences.browseSourceTags.asState(screenModelScope)

    var browseSwipeToPage by sourcePreferences.browseSwipeToPage.asState(screenModelScope)
    // SY <--

    val source = sourceManager.getOrStub(sourceId)

    // SY -->
    val ehentaiBrowseDisplayMode by exhPreferences.enhancedEHentaiView.asState(screenModelScope)

    val startExpanded by uiPreferences.expandFilters.asState(screenModelScope)

    private val filterSerializer = FilterSerializer()

    val sourceIsMangaDex = sourceId in mangaDexSourceIds
    // SY <--

    private val searchHistoryScope = "source_$sourceId"

    init {
        mutableState.update {
            var query: String? = null
            var listing = it.listing

            if (listing is Listing.Search) {
                query = listing.query
                listing = Listing.Search(query, source.getFilterList())
            }

            it.copy(
                listing = listing,
                filters = source.getFilterList(),
                toolbarQuery = query,
            )
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }

        // SY -->
        val savedSearchFilters = savedSearch
        val jsonFilters = filtersJson
        val filters = state.value.filters
        if (savedSearchFilters != null) {
            val savedSearch = runBlocking { getExhSavedSearch.awaitOne(savedSearchFilters) { filters } }
            if (savedSearch != null) {
                search(query = savedSearch.query, filters = savedSearch.filterList)
            }
        } else if (jsonFilters != null) {
            runCatching {
                val filtersJson = Json.decodeFromString<JsonArray>(jsonFilters)
                filterSerializer.deserialize(filters, filtersJson)
                search(filters = filters)
            }
        }

        getExhSavedSearch.subscribe(source.id, source::getFilterList)
            .map { it.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EXHSavedSearch::name)) }
            .onEach { savedSearches ->
                mutableState.update { it.copy(savedSearches = savedSearches) }
            }
            .launchIn(screenModelScope)

        getSearchHistory.subscribe(searchHistoryScope)
            .onEach { queries ->
                mutableState.update { it.copy(searchHistory = queries) }
            }
            .launchIn(screenModelScope)
        // SY <--
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()

    // SY -->
    private val swipeModeFlow = sourcePreferences.browseSwipeToPage.changes()

    // Cache of pages prefetched in swipe-to-page mode (key = page number). The current page is
    // always shown alone (vertical scrolling is locked), while the two following pages are
    // fetched ahead of time so swiping to them shows results without waiting for the network.
    private val swipePageCache = mutableMapOf<Long, List<Pair<Manga, RaisedSearchMetadata?>>>()

    // Tracks how many manga items each website page returned, keyed by page number. Sources return
    // a different number of items per page, so a fixed page size can't translate a scroll index back
    // into the website page shown in the bottom bar.
    private val _pageSizes = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val pageSizes: StateFlow<Map<Long, Int>> = _pageSizes.asStateFlow()

    /**
     * Translates a flat list [index] (from vertical scrolling) into the website page number it
     * belongs to, using the per-page item counts recorded as pages are loaded.
     */
    fun pageForIndex(index: Int, sizes: Map<Long, Int>): Int {
        var remaining = index
        for ((page, size) in sizes.entries.sortedBy { it.key }) {
            if (remaining < size) return page.toInt()
            remaining -= size
        }
        return sizes.keys.maxOrNull()?.toInt() ?: state.value.currentPage
    }

    /**
     * Whether [page]'s content is already in the swipe cache, so the UI can skip the loading
     * spinner that would otherwise flash every time a swipe lands on a preloaded page.
     */
    fun isPageCached(page: Int): Boolean = swipePageCache.containsKey(page.toLong())

    /**
     * Builds the paging flow for a single [page]. Used for both the current page and, in
     * swipe-to-page mode, the neighbouring page that should follow the finger during a swipe.
     */
    private fun pagerFlowForPage(
        page: Int,
        listing: Listing,
        trackPageSizes: Boolean = false,
    ): Flow<PagingData<StateFlow<Pair<Manga, RaisedSearchMetadata?>>>> {
        val swipeMode = browseSwipeToPage
        // In swipe mode only the current page is loaded so vertical scrolling stays on one page.
        val config = if (swipeMode) {
            PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = 0)
        } else {
            PagingConfig(pageSize = PAGE_SIZE)
        }
        // A fresh pager rebuilds the flat list from [page], so previously recorded per-page item
        // counts no longer reflect where each website page starts.
        if (trackPageSizes) {
            _pageSizes.value = emptyMap()
        }
        return Pager(
            config = config,
            initialKey = page.toLong(),
        ) {
            val pagingSource = createSourcePagingSource(listing.query ?: "", listing.filters)
            when {
                swipeMode -> SinglePagePagingSource(pagingSource, page.toLong(), swipePageCache)
                trackPageSizes -> PageSizeTrackingSource(pagingSource, _pageSizes)
                else -> pagingSource
            }
        }.flow.map { pagingData ->
            pagingData.map { (manga, metadata) ->
                getManga.subscribe(manga.url, manga.source)
                    .map { it ?: manga }
                    // SY -->
                    .combineMetadata(metadata)
                    // SY <--
                    .stateIn(ioCoroutineScope)
            }
                .filter { !hideInLibraryItems || !it.value.first.favorite }
        }
            .cachedIn(ioCoroutineScope)
    }

    val mangaPagerFlowFlow = combine(
        state.map { it.listing to it.currentPage },
        swipeModeFlow,
    ) { listingPage, swipeMode -> listingPage to swipeMode }
        .distinctUntilChanged()
        .onEach { (listingPage, swipeMode) ->
            if (swipeMode) {
                val (listing, currentPage) = listingPage
                ioCoroutineScope.launch { preloadSwipePages(listing, currentPage) }
            }
        }
        .map { (listingPage, _) ->
            val (listing, currentPage) = listingPage
            pagerFlowForPage(currentPage, listing, trackPageSizes = true)
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    /**
     * Paging flow for an arbitrary [page], used to render the neighbouring page that follows the
     * finger during a swipe in swipe-to-page mode.
     */
    fun mangaPagerFlow(page: Int): Flow<PagingData<StateFlow<Pair<Manga, RaisedSearchMetadata?>>>> =
        pagerFlowForPage(page, state.value.listing)

    /**
     * Prefetches the page before and the two pages after [currentPage] into [swipePageCache] so
     * swiping in either direction shows the target page's content immediately. Results already
     * cached are skipped.
     */
    private suspend fun preloadSwipePages(listing: Listing, currentPage: Int) {
        for (target in (currentPage - 1)..(currentPage + SWIPE_PREFETCH_PAGES)) {
            if (target < 1) continue
            val key = target.toLong()
            if (swipePageCache.containsKey(key)) continue
            val pagingSource = createSourcePagingSource(listing.query ?: "", listing.filters)
            val result = pagingSource.load(
                PagingSource.LoadParams.Refresh(
                    key = key,
                    loadSize = PAGE_SIZE,
                    placeholdersEnabled = false,
                ),
            )
            if (result is PagingSource.LoadResult.Page) {
                swipePageCache[key] = result.data
            }
        }
    }

    /**
     * Paging source used in swipe-to-page mode. It serves the current page only (nextKey is
     * always null so vertical scrolling never advances), and prefers [cache] when the requested
     * page has already been prefetched.
     */
    private class SinglePagePagingSource(
        private val delegate: PagingSource<Long, Pair<Manga, RaisedSearchMetadata?>>,
        private val initialKey: Long,
        private val cache: MutableMap<Long, List<Pair<Manga, RaisedSearchMetadata?>>>,
    ) : PagingSource<Long, Pair<Manga, RaisedSearchMetadata?>>() {
        override fun getRefreshKey(state: PagingState<Long, Pair<Manga, RaisedSearchMetadata?>>): Long? =
            delegate.getRefreshKey(state)

        override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Pair<Manga, RaisedSearchMetadata?>> {
            val page = params.key ?: initialKey
            val cached = cache[page]
            return if (cached != null) {
                LoadResult.Page(data = cached, prevKey = null, nextKey = null)
            } else {
                val result = delegate.load(params)
                if (result is LoadResult.Page) {
                    cache[page] = result.data
                    result.copy(nextKey = null)
                } else {
                    result
                }
            }
        }
    }
    // SY <--

    /**
     * Records the number of items each website page contributes so the UI can translate a scroll
     * index back into the website page number. Wraps the real [SourcePagingSource] without
     * otherwise changing its behaviour.
     */
    private class PageSizeTrackingSource(
        private val delegate: SourcePagingSource,
        private val pageSizes: MutableStateFlow<Map<Long, Int>>,
    ) : PagingSource<Long, Pair<Manga, RaisedSearchMetadata?>>() {
        override fun getRefreshKey(state: PagingState<Long, Pair<Manga, RaisedSearchMetadata?>>): Long? =
            delegate.getRefreshKey(state)

        override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Pair<Manga, RaisedSearchMetadata?>> {
            val result = delegate.load(params)
            if (result is LoadResult.Page) {
                pageSizes.update { it + ((params.key ?: 1L) to result.data.size) }
            }
            return result
        }
    }

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    // SY -->
    open fun Flow<Manga>.combineMetadata(metadata: RaisedSearchMetadata?): Flow<Pair<Manga, RaisedSearchMetadata?>> {
        val metadataSource = source.getMainSource<MetadataSource<*, *>>()
        return flatMapLatest { manga ->
            if (metadataSource != null) {
                getFlatMetadataById.subscribe(manga.id)
                    .map { flatMetadata ->
                        manga to (flatMetadata?.raise(metadataSource.metaClass) ?: metadata)
                    }
            } else {
                flowOf(manga to null)
            }
        }
    }
    // SY <--

    fun resetFilters() {
        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        // SY -->
        swipePageCache.clear()
        _pageSizes.value = emptyMap()
        // SY <--
        mutableState.update { it.copy(listing = listing, toolbarQuery = null, currentPage = 1) }
    }

    fun goToPage(page: Int) {
        if (page < 1) return
        // SY -->
        _pageSizes.value = emptyMap()
        // SY <--
        mutableState.update { it.copy(currentPage = page) }
    }

    fun setFilters(filters: FilterList) {
        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        // SY -->
        swipePageCache.clear()
        _pageSizes.value = emptyMap()
        if (filters != null && filters !== state.value.filters) {
            mutableState.update { state -> state.copy(filters = filters) }
        }
        // SY <--
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        val resolvedQuery = query ?: input.query
        if (!resolvedQuery.isNullOrBlank()) {
            screenModelScope.launchIO {
                insertSearchHistory.await(searchHistoryScope, resolvedQuery)
            }
        }

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = resolvedQuery,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = resolvedQuery,
                currentPage = 1,
            )
        }
    }

    fun clearSearchHistory() {
        screenModelScope.launchIO {
            clearSearchHistoryInteractor.await(searchHistoryScope)
        }
    }

    fun searchGenre(genreName: String) {
        // SY -->
        swipePageCache.clear()
        _pageSizes.value = emptyMap()
        // SY <--
        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
                currentPage = 1,
            )
        }
    }

    fun addBrowseSourceTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isBlank() || trimmed in browseSourceTags) return
        browseSourceTags = browseSourceTags + trimmed
    }

    fun deleteBrowseSourceTag(tag: String) {
        browseSourceTags = browseSourceTags - tag
    }

    fun reorderBrowseSourceTags(newOrder: List<String>) {
        if (newOrder == browseSourceTags) return
        browseSourceTags = newOrder
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        screenModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, source)
            }

            updateManga.await(new.toMangaUpdate())
        }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds },
                        ),
                    )
                }
            }
        }
    }

    // --- Multi-select mode ---
    fun toggleSelectionMode() {
        mutableState.update { state ->
            if (state.selectionMode) {
                state.copy(selectionMode = false, selection = emptyList())
            } else {
                state.copy(selectionMode = true)
            }
        }
    }

    fun toggleSelection(manga: Manga) {
        mutableState.update { state ->
            val newSelection = if (state.selection.any { it.id == manga.id }) {
                state.selection.filterNot { it.id == manga.id }
            } else {
                state.selection + manga
            }
            state.copy(
                selection = newSelection,
                selectionMode = state.selectionMode && newSelection.isNotEmpty(),
            )
        }
    }

    fun selectAllVisible(mangas: List<Manga>) {
        mutableState.update { state ->
            val currentIds = state.selection.map { it.id }.toSet()
            state.copy(
                selection = state.selection + mangas.filterNot { it.id in currentIds },
                selectionMode = true,
            )
        }
    }

    fun invertSelectionVisible(mangas: List<Manga>) {
        mutableState.update { state ->
            val currentIds = state.selection.map { it.id }.toSet()
            val visibleIds = mangas.map { it.id }.toSet()
            state.copy(
                selection = state.selection.filterNot { it.id in visibleIds } +
                    mangas.filterNot { it.id in currentIds },
            )
        }
    }

    /**
     * Batch-adds the selected manga to the library, using the default category
     * when available or letting the user pick categories.
     */
    fun bulkAddToLibrary() {
        val toAdd = state.value.selection.filterNot { it.favorite }
        if (toAdd.isEmpty()) {
            toggleSelectionMode()
            return
        }
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }
            when {
                // Default category set
                defaultCategory != null -> {
                    bulkAddToLibrary(toAdd, listOf(defaultCategory.id))
                    toggleSelectionMode()
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    bulkAddToLibrary(toAdd, emptyList())
                    toggleSelectionMode()
                }

                // Choose a category for all selected manga
                else -> {
                    val initialSelection = categories.map { CheckboxState.State.None(it) }
                    mutableState.update {
                        it.copy(dialog = Dialog.BulkChangeCategory(toAdd, initialSelection))
                    }
                }
            }
        }
    }

    /**
     * Adds a list of manga to the library with the given categories.
     */
    fun bulkAddToLibrary(mangas: List<Manga>, categoryIds: List<Long>) {
        mangas.forEach { manga ->
            moveMangaToCategories(manga, categoryIds)
            changeMangaFavorite(manga)
        }
    }

    // SY -->
    open fun createSourcePagingSource(query: String, filters: FilterList): SourcePagingSource {
        return getRemoteManga(sourceId, query, filters)
    }
    // SY <--

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<MangaWithChapterCount> {
        return getDuplicateLibraryManga.invoke(manga)
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                // 两条「下载=模块」规则：只勾了「下载」要补「默认」；本来在「下载」里就保留
                // （见 DownloadCategory.resolveUserSelection）。多选收藏走 bulkAddToLibrary → 这里，也覆盖。
                categoryIds = downloadCategory.resolveUserSelection(manga.id, categoryIds),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog

        // SY -->
        data class BulkChangeCategory(
            val mangas: List<Manga>,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog

        // SY <--
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class JumpToPage(val currentPage: Int) : Dialog

        // SY -->
        data class DeleteSavedSearch(val idToDelete: Long, val name: String) : Dialog
        data class CreateSavedSearch(val currentSavedSearches: List<String>) : Dialog
        // SY <--
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        // SY -->
        val selection: List<Manga> = emptyList(),
        val selectionMode: Boolean = false,
        val savedSearches: List<EXHSavedSearch> = emptyList(),
        val filterable: Boolean = true,
        // SY <--
        val currentPage: Int = 1,
        val searchHistory: List<String> = emptyList(),
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }

    companion object {
        const val PAGE_SIZE = 25

        // SY -->
        // Number of pages prefetched ahead of the current page in swipe-to-page mode.
        const val SWIPE_PREFETCH_PAGES = 2
        // SY <--
    }

    // EXH -->
    fun onSaveSearch() {
        screenModelScope.launchIO {
            val names = state.value.savedSearches.map { it.name }
            mutableState.update { it.copy(dialog = Dialog.CreateSavedSearch(names)) }
        }
    }

    fun onSavedSearch(
        search: EXHSavedSearch,
        onToast: (StringResource) -> Unit,
    ) {
        screenModelScope.launchIO {
            if (search.filterList == null && state.value.filters.isNotEmpty()) {
                withUIContext {
                    onToast(SYMR.strings.save_search_invalid)
                }
                return@launchIO
            }

            val allDefault = search.filterList != null && search.filterList == source.getFilterList()
            setDialog(null)

            val filters = search.filterList
                ?.takeUnless { allDefault }
                ?: source.getFilterList()

            _pageSizes.value = emptyMap()
            mutableState.update {
                it.copy(
                    listing = Listing.Search(
                        query = search.query,
                        filters = filters,
                    ),
                    filters = filters,
                    toolbarQuery = search.query,
                    currentPage = 1,
                )
            }
        }
    }

    fun onSavedSearchPress(search: EXHSavedSearch) {
        mutableState.update { it.copy(dialog = Dialog.DeleteSavedSearch(search.id, search.name)) }
    }

    fun saveSearch(
        name: String,
    ) {
        screenModelScope.launchNonCancellable {
            val query = state.value.toolbarQuery?.takeUnless {
                it.isBlank() || it == GetRemoteManga.QUERY_POPULAR || it == GetRemoteManga.QUERY_LATEST
            }?.trim()
            val filterList = state.value.filters.ifEmpty { source.getFilterList() }
            insertSavedSearch.await(
                SavedSearch(
                    id = -1,
                    source = source.id,
                    name = name.trim(),
                    query = query,
                    filtersJson = runCatching {
                        filterSerializer.serialize(filterList).ifEmpty { null }?.let { Json.encodeToString(it) }
                    }.getOrNull(),
                ),
            )
        }
    }

    fun deleteSearch(savedSearchId: Long) {
        screenModelScope.launchNonCancellable {
            deleteSavedSearchById.await(savedSearchId)
        }
    }

    fun onMangaDexRandom(onRandomFound: (String) -> Unit) {
        screenModelScope.launchIO {
            val random = source.getMainSource<MangaDex>()?.fetchRandomMangaUrl()
                ?: return@launchIO
            onRandomFound(random)
        }
    }
    // EXH <--
}
