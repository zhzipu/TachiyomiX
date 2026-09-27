package eu.kanade.tachiyomi.ui.library

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapNotNull
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.upload.DownloadCategory
import eu.kanade.tachiyomi.data.upload.isDownloadCategory
import eu.kanade.tachiyomi.data.upload.LibraryMangaProgress
import eu.kanade.tachiyomi.data.upload.UploadChoice
import eu.kanade.tachiyomi.data.upload.UploadManager
import eu.kanade.tachiyomi.data.upload.UploadPendingDecision
import eu.kanade.tachiyomi.data.upload.UploadStatus
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import exh.favorites.FavoritesSyncHelper
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.recs.batch.RecommendationSearchHelper
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.SearchEngine
import exh.search.Text
import exh.source.EH_SOURCE_ID
import exh.source.ExhPreferences
import exh.source.MERGED_SOURCE_ID
import exh.source.isEhBasedManga
import exh.source.isMetadataSource
import exh.source.mangaDexSourceIds
import exh.source.nHentaiSourceIds
import exh.util.cancellable
import exh.util.isLewd
import exh.util.nullIfBlank
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import mihon.core.common.utils.mutate
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetIdsOfFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import tachiyomi.source.network.config.isPendingChapterUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracksPerManga: GetTracksPerManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    // SY -->
    private val downloadCategory: DownloadCategory = Injekt.get(),
    private val uploadManager: UploadManager = Injekt.get(),
    // SY <--
    private val trackerManager: TrackerManager = Injekt.get(),
    // SY -->
    private val exhPreferences: ExhPreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getMergedMangaById: GetMergedMangaById = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val getIdsOfFavoriteMangaWithMetadata: GetIdsOfFavoriteMangaWithMetadata = Injekt.get(),
    private val getSearchTags: GetSearchTags = Injekt.get(),
    private val getSearchTitles: GetSearchTitles = Injekt.get(),
    private val searchEngine: SearchEngine = Injekt.get(),
    private val setCustomMangaInfo: SetCustomMangaInfo = Injekt.get(),
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = Injekt.get(),

    syncPreferences: SyncPreferences = Injekt.get(),
    // SY <--
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    // SY -->
    val favoritesSync = FavoritesSyncHelper(preferences.context)
    val recommendationSearch = RecommendationSearchHelper(preferences.context)

    private var recommendationSearchJob: Job? = null
    // SY <--

    init {
        mutableState.update { state ->
            state.copy(activeCategoryIndex = libraryPreferences.lastUsedCategory.get())
        }

        // SY -->
        // 「下载」分类常驻：书架一打开就确保它存在
        // （用户在「分类管理」里把它删了也会在下次打开书架时补回来）
        screenModelScope.launchIO { downloadCategory.ensureId() }

        // 书架「下载」分类下进度条的数据源。
        //
        // 下载进度是 `Download.progress` 就地改的，没有会发射的 Flow 可以订阅，
        // 所以这里用固定节拍采样；只有结果真的变了才写状态，
        // 空闲时（没有下载也没有上传）几乎是零开销。
        screenModelScope.launchIO {
            while (true) {
                val next = buildLibraryProgress()
                if (next != mutableState.value.libraryProgress) {
                    mutableState.update { it.copy(libraryProgress = next) }
                }
                delay(LIBRARY_PROGRESS_TICK)
            }
        }

        // 「上传」按钮能不能点（网络图源有没有填服务器信息）。
        //
        // 这个值的来源是图源自己那份 SharedPreferences，没有可订阅的 Flow；而书架页签是
        // 常驻的，用户去图源设置里改完之后返回书架并不会让这个 ScreenModel 重建，
        // 所以同样用固定节拍采样。一次 SharedPreferences 读取 + 一次 map 查找，
        // 节拍放慢到 1s，代价可以忽略。
        // 先同步取一次，避免首帧把上传按钮画成灰的。
        mutableState.update { it.copy(isUploadAvailable = uploadManager.isUploadAvailable()) }
        screenModelScope.launchIO {
            while (true) {
                val available = uploadManager.isUploadAvailable()
                if (available != mutableState.value.isUploadAvailable) {
                    mutableState.update { it.copy(isUploadAvailable = available) }
                }
                delay(UPLOAD_AVAILABILITY_TICK)
            }
        }
        // SY <--

        screenModelScope.launchIO {
            combine(
                combine(
                    state.map { it.searchQuery }.distinctUntilChanged().debounce(0.25.seconds),
                    getCategories.subscribe(),
                    getFavoritesFlow(),
                    ::Triple,
                ),
                combine(
                    getTracksPerManga.subscribe(),
                    getTrackingFiltersFlow(),
                    ::Pair,
                ),
                // SY -->
                combine(
                    state.map { it.groupType }.distinctUntilChanged(),
                    libraryPreferences.sortingMode.changes(),
                    ::Pair,
                ),
                // SY <--
                getLibraryItemPreferencesFlow(),
            ) { (searchQuery, categories, favorites), (tracksMap, trackingFilters), /* SY --> */ (groupType, sortingMode)/* <-- SY */, itemPreferences ->
                val showSystemCategory = favorites.any { it.libraryManga.categories.contains(0) }
                val filteredFavorites = favorites
                    .applyFilters(tracksMap, trackingFilters, itemPreferences)
                    .let {
                        if (searchQuery == null) {
                            it
                        } else {
                            // SY -->
                            // it.filter { m -> m.matches(searchQuery) } }
                            filterLibrary(it, searchQuery, trackingFilters)
                            // SY <--
                        }
                    }

                LibraryData(
                    isInitialized = true,
                    showSystemCategory = showSystemCategory,
                    categories = categories,
                    favorites = filteredFavorites,
                    tracksMap = tracksMap,
                    loggedInTrackerIds = trackingFilters.keys,
                )
            }
                .distinctUntilChanged()
                .collectLatest { libraryData ->
                    mutableState.update { state ->
                        state.copy(libraryData = libraryData)
                    }
                }
        }

        screenModelScope.launchIO {
            state
                .dropWhile { !it.libraryData.isInitialized }
                .map {
                    Pair(
                        it.libraryData,
                        // SY -->
                        it.groupType,
                        // SY <--
                    )
                }
                .distinctUntilChanged()
                .map { (data, groupType) ->
                    data.favorites
                        .applyGrouping(
                            data.categories,
                            data.showSystemCategory,
                            // SY -->
                            groupType,
                            // SY <--
                        )
                        .applySort(
                            data.favoritesById,
                            data.tracksMap,
                            data.loggedInTrackerIds,
                            // SY -->
                            libraryPreferences.sortingMode.get().takeIf { groupType != LibraryGroup.BY_DEFAULT },
                            // SY <--
                        )
                        .let {
                            it.ifEmpty {
                                mapOf(
                                    Category(
                                        0,
                                        preferences.context.stringResource(MR.strings.default_category),
                                        0,
                                        0,
                                    ) to emptyList(),
                                )
                            }
                        }
                }
                .collectLatest {
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            groupedFavorites = it,
                        )
                    }
                }
        }

        combine(
            libraryPreferences.categoryTabs.changes(),
            libraryPreferences.categoryNumberOfItems.changes(),
            libraryPreferences.showContinueReadingButton.changes(),
        ) { a, b, c -> arrayOf(a, b, c) }
            .onEach { (showCategoryTabs, showMangaCount, showMangaContinueButton) ->
                mutableState.update { state ->
                    state.copy(
                        showCategoryTabs = showCategoryTabs,
                        showMangaCount = showMangaCount,
                        showMangaContinueButton = showMangaContinueButton,
                    )
                }
            }
            .launchIn(screenModelScope)

        combine(
            getLibraryItemPreferencesFlow(),
            getTrackingFiltersFlow(),
        ) { prefs, trackFilters ->
            listOf(
                prefs.filterDownloaded,
                prefs.filterUnread,
                prefs.filterStarted,
                prefs.filterBookmarked,
                prefs.filterCompleted,
                prefs.filterIntervalCustom,
                // SY -->
                prefs.filterLewd,
                // SY <--
                *trackFilters.values.toTypedArray(),
            )
                .any { it != TriState.DISABLED }
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(screenModelScope)

        // SY -->
        combine(
            exhPreferences.isHentaiEnabled.changes(),
            sourcePreferences.disabledSources.changes(),
            exhPreferences.enableExhentai.changes(),
        ) { isHentaiEnabled, disabledSources, enableExhentai ->
            isHentaiEnabled && (EH_SOURCE_ID.toString() !in disabledSources || enableExhentai)
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(showSyncExh = it)
                }
            }
            .launchIn(screenModelScope)

        libraryPreferences.groupLibraryBy.changes()
            .onEach {
                mutableState.update { state ->
                    state.copy(groupType = it)
                }
            }
            .launchIn(screenModelScope)
        syncPreferences.syncService
            .changes()
            .distinctUntilChanged()
            .onEach { syncService ->
                mutableState.update { it.copy(isSyncEnabled = syncService != 0) }
            }
            .launchIn(screenModelScope)
        // SY <--
    }

    private fun List<LibraryItem>.applyFilters(
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        preferences: ItemPreferences,
    ): List<LibraryItem> {
        val downloadedOnly = preferences.globalFilterDownloaded
        val skipOutsideReleasePeriod = preferences.skipOutsideReleasePeriod
        val filterDownloaded = if (downloadedOnly) TriState.ENABLED_IS else preferences.filterDownloaded
        val filterUnread = preferences.filterUnread
        val filterStarted = preferences.filterStarted
        val filterBookmarked = preferences.filterBookmarked
        val filterCompleted = preferences.filterCompleted
        val filterIntervalCustom = preferences.filterIntervalCustom

        val isNotLoggedInAnyTrack = trackingFilter.isEmpty()

        val excludedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_NOT) it.key else null }
        val includedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_IS) it.key else null }
        val trackFiltersIsIgnored = includedTracks.isEmpty() && excludedTracks.isEmpty()

        // SY -->
        val filterLewd = preferences.filterLewd
        // SY <--

        val filterFnDownloaded: (LibraryItem) -> Boolean = {
            applyFilter(filterDownloaded) { it.isLocal || it.downloadCount > 0 }
        }

        val filterFnUnread: (LibraryItem) -> Boolean = {
            applyFilter(filterUnread) { it.libraryManga.unreadCount > 0 }
        }

        val filterFnStarted: (LibraryItem) -> Boolean = {
            applyFilter(filterStarted) { it.libraryManga.hasStarted }
        }

        val filterFnBookmarked: (LibraryItem) -> Boolean = {
            applyFilter(filterBookmarked) { it.libraryManga.hasBookmarks }
        }

        val filterFnCompleted: (LibraryItem) -> Boolean = {
            applyFilter(filterCompleted) { it.libraryManga.manga.status.toInt() == SManga.COMPLETED }
        }

        val filterFnIntervalCustom: (LibraryItem) -> Boolean = {
            if (skipOutsideReleasePeriod) {
                applyFilter(filterIntervalCustom) { it.libraryManga.manga.fetchInterval < 0 }
            } else {
                true
            }
        }

        // SY -->
        val filterFnLewd: (LibraryItem) -> Boolean = {
            applyFilter(filterLewd) { it.libraryManga.manga.isLewd() }
        }
        // SY <--

        val filterFnTracking: (LibraryItem) -> Boolean = tracking@{ item ->
            if (isNotLoggedInAnyTrack || trackFiltersIsIgnored) return@tracking true

            val mangaTracks = trackMap[item.id].orEmpty().map { it.trackerId }

            val isExcluded = excludedTracks.isNotEmpty() && mangaTracks.fastAny { it in excludedTracks }
            val isIncluded = includedTracks.isEmpty() || mangaTracks.fastAny { it in includedTracks }

            !isExcluded && isIncluded
        }

        return fastFilter {
            filterFnDownloaded(it) &&
                filterFnUnread(it) &&
                filterFnStarted(it) &&
                filterFnBookmarked(it) &&
                filterFnCompleted(it) &&
                filterFnIntervalCustom(it) &&
                filterFnTracking(it) &&
                // SY -->
                filterFnLewd(it)
            // SY <--
        }
    }

    private fun List<LibraryItem>.applyGrouping(
        categories: List<Category>,
        showSystemCategory: Boolean,
        // SY -->
        groupType: Int,
        // <-- SY
    ): Map<Category, List</* LibraryItem */ Long>> {
        // SY -->
        when (groupType) {
            LibraryGroup.BY_DEFAULT -> {
                // SY <--
                val groupCache = mutableMapOf</* Category.id */ Long, MutableList</* LibraryItem */ Long>>()
                forEach { item ->
                    item.libraryManga.categories.forEach { categoryId ->
                        groupCache.getOrPut(categoryId) { mutableListOf() }.add(item.id)
                    }
                }

                return categories.filter { showSystemCategory || !it.isSystemCategory }
                    .associateWith { groupCache[it.id]?.toList().orEmpty() }
            }
            // SY -->
            LibraryGroup.UNGROUPED -> {
                return mapOf(
                    Category(
                        0,
                        preferences.context.stringResource(SYMR.strings.ungrouped),
                        0,
                        0,
                    ) to
                        map { it.id },
                )
            }

            else -> {
                return getGroupedMangaItems(
                    groupType = groupType,
                )
            }
        }
        // SY <--
    }

    private fun Map<Category, List</* LibraryItem */ Long>>.applySort(
        favoritesById: Map<Long, LibraryItem>,
        trackMap: Map<Long, List<Track>>,
        loggedInTrackerIds: Set<Long>,
        // SY -->
        groupSort: LibrarySort? = null,
        // SY <--
    ): Map<Category, List</* LibraryItem */ Long>> {
        // SY -->
        val listOfTags by lazy {
            libraryPreferences.sortTagsForLibrary.get()
                .asSequence()
                .mapNotNull {
                    val list = it.split("|")
                    (list.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to
                        (list.getOrNull(1) ?: return@mapNotNull null)
                }
                .sortedBy { it.first }
                .map { it.second }
                .toList()
        }
        // SY <--

        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { manga1, manga2 ->
            val title1 = manga1.libraryManga.manga.title.lowercase()
            val title2 = manga2.libraryManga.manga.title.lowercase()
            title1.compareToWithCollator(title2)
        }

        val defaultTrackerScoreSortValue = -1.0
        val trackerScores by lazy {
            val trackerMap = trackerManager.getAll(loggedInTrackerIds).associateBy { e -> e.id }
            trackMap.mapValues { entry ->
                when {
                    entry.value.isEmpty() -> null
                    else ->
                        entry.value
                            .mapNotNull { trackerMap[it.trackerId]?.get10PointScore(it) }
                            .average()
                }
            }
        }

        fun LibrarySort.comparator(): Comparator<LibraryItem> = Comparator { manga1, manga2 ->
            // SY -->
            val sort = groupSort ?: this
            // SY <--
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(manga1, manga2)
                }

                LibrarySort.Type.LastRead -> {
                    manga1.libraryManga.lastRead.compareTo(manga2.libraryManga.lastRead)
                }

                LibrarySort.Type.LastUpdate -> {
                    manga1.libraryManga.manga.lastUpdate.compareTo(manga2.libraryManga.manga.lastUpdate)
                }

                LibrarySort.Type.UnreadCount -> when {
                    // Ensure unread content comes first
                    manga1.libraryManga.unreadCount == manga2.libraryManga.unreadCount -> 0
                    manga1.libraryManga.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                    manga2.libraryManga.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                    else -> manga1.libraryManga.unreadCount.compareTo(manga2.libraryManga.unreadCount)
                }

                LibrarySort.Type.TotalChapters -> {
                    manga1.libraryManga.totalChapters.compareTo(manga2.libraryManga.totalChapters)
                }

                LibrarySort.Type.LatestChapter -> {
                    manga1.libraryManga.latestUpload.compareTo(manga2.libraryManga.latestUpload)
                }

                LibrarySort.Type.ChapterFetchDate -> {
                    manga1.libraryManga.chapterFetchedAt.compareTo(manga2.libraryManga.chapterFetchedAt)
                }

                LibrarySort.Type.DateAdded -> {
                    manga1.libraryManga.manga.dateAdded.compareTo(manga2.libraryManga.manga.dateAdded)
                }

                LibrarySort.Type.TrackerMean -> {
                    val item1Score = trackerScores[manga1.id] ?: defaultTrackerScoreSortValue
                    val item2Score = trackerScores[manga2.id] ?: defaultTrackerScoreSortValue
                    item1Score.compareTo(item2Score)
                }

                LibrarySort.Type.Random -> {
                    error("Why Are We Still Here? Just To Suffer?")
                }
                // SY -->
                LibrarySort.Type.TagList -> {
                    val manga1IndexOfTag = listOfTags.indexOfFirst {
                        manga1.libraryManga.manga.genre?.contains(it) ?: false
                    }
                    val manga2IndexOfTag = listOfTags.indexOfFirst {
                        manga2.libraryManga.manga.genre?.contains(it) ?: false
                    }
                    manga1IndexOfTag.compareTo(manga2IndexOfTag)
                }
                // SY <--
            }
        }

        return mapValues { (key, value) ->
            // SY -->
            val sort = groupSort ?: key.sort
            if (sort.type == LibrarySort.Type.Random) {
                // SY <--
                return@mapValues value.shuffled(Random(libraryPreferences.randomSortSeed.get()))
            }

            val manga = value.mapNotNull { favoritesById[it] }

            // SY -->
            val comparator = sort.comparator()
                // SY <--
                .let { if (/* SY --> */ sort.isAscending /* SY <-- */) it else it.reversed() }
                .thenComparator(sortAlphabetically)

            manga.sortedWith(comparator).map { it.id }
        }
    }

    private fun getLibraryItemPreferencesFlow(): Flow<ItemPreferences> {
        return combine(
            libraryPreferences.downloadBadge.changes(),
            libraryPreferences.unreadBadge.changes(),
            libraryPreferences.localBadge.changes(),
            libraryPreferences.languageBadge.changes(),
            libraryPreferences.autoUpdateMangaRestrictions.changes(),

            preferences.downloadedOnly.changes(),
            libraryPreferences.filterDownloaded.changes(),
            libraryPreferences.filterUnread.changes(),
            libraryPreferences.filterStarted.changes(),
            libraryPreferences.filterBookmarked.changes(),
            libraryPreferences.filterCompleted.changes(),
            libraryPreferences.filterIntervalCustom.changes(),
            // SY -->
            libraryPreferences.filterLewd.changes(),
            // SY <--
        ) {
            ItemPreferences(
                downloadBadge = it[0] as Boolean,
                unreadBadge = it[1] as Boolean,
                localBadge = it[2] as Boolean,
                languageBadge = it[3] as Boolean,
                skipOutsideReleasePeriod = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in (it[4] as Set<*>),
                globalFilterDownloaded = it[5] as Boolean,
                filterDownloaded = it[6] as TriState,
                filterUnread = it[7] as TriState,
                filterStarted = it[8] as TriState,
                filterBookmarked = it[9] as TriState,
                filterCompleted = it[10] as TriState,
                filterIntervalCustom = it[11] as TriState,
                // SY -->
                filterLewd = it[12] as TriState,
                // SY <--
            )
        }
    }

    private fun getFavoritesFlow(): Flow<List<LibraryItem>> {
        return combine(
            getLibraryManga.subscribe(),
            getLibraryItemPreferencesFlow(),
            downloadCache.changes,
        ) { libraryManga, preferences, _ ->
            libraryManga.map { manga ->
                // SY -->
                val downloadCount = if (manga.manga.source == MERGED_SOURCE_ID) {
                    getMergedMangaById.await(manga.manga.id).sumOf { downloadManager.getDownloadCount(it) }
                } else {
                    downloadManager.getDownloadCount(manga.manga)
                }
                // SY <--
                LibraryItem(
                    libraryManga = manga,
                    // SY -->
                    downloadCount = downloadCount,
                    // SY <--
                    unreadCount = manga.unreadCount,
                    isLocal = manga.manga.isLocal(),
                    badges = LibraryItem.Badges(
                        downloadCount = if (preferences.downloadBadge) {
                            // SY -->
                            downloadCount
                            // SY <--
                        } else {
                            0
                        },
                        unreadCount = if (preferences.unreadBadge) {
                            manga.unreadCount
                        } else {
                            0
                        },
                        isLocal = if (preferences.localBadge) {
                            manga.manga.isLocal()
                        } else {
                            false
                        },
                        sourceLanguage = if (preferences.languageBadge) {
                            sourceManager.getOrStub(manga.manga.source).lang
                        } else {
                            ""
                        },
                    ),
                )
            }
        }
    }

    /**
     * Flow of tracking filter preferences
     *
     * @return map of track id with the filter value
     */
    private fun getTrackingFiltersFlow(): Flow<Map<Long, TriState>> {
        return trackerManager.loggedInTrackersFlow().flatMapLatest { loggedInTrackers ->
            if (loggedInTrackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val filterFlows = loggedInTrackers.map { tracker ->
                    libraryPreferences.filterTracking(tracker.id.toInt()).changes().map { tracker.id to it }
                }
                combine(filterFlows) { it.toMap() }
            }
        }
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        // SY -->
        val mergedManga = getMergedMangaById.await(manga.id).associateBy { it.id }
        return if (manga.id == MERGED_SOURCE_ID) {
            getMergedChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
        } else {
            getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
        }
            // SY --> 「未上传」的章节没有内容，不该成为「继续阅读」的目标
            .filterNot { it.url.isPendingChapterUrl() }
            // SY <--
            .getNextUnread(manga, downloadManager, mergedManga)
        // SY <--
    }

    /**
     * Returns the mix (non-common) categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getMixCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        val mangaCategories = mangas.map { getCategories.await(it.id).toSet() }
        val common = mangaCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return mangaCategories.flatten().distinct().subtract(common)
    }

    /**
     * Queues the amount specified of unread chapters from the list of selected manga
     */
    fun performDownloadAction(action: DownloadAction) {
        when (action) {
            DownloadAction.NEXT_1_CHAPTER -> downloadNextChapters(1)
            DownloadAction.NEXT_5_CHAPTERS -> downloadNextChapters(5)
            DownloadAction.NEXT_10_CHAPTERS -> downloadNextChapters(10)
            DownloadAction.NEXT_25_CHAPTERS -> downloadNextChapters(25)
            DownloadAction.ALL_CHAPTERS -> downloadAllChapters()
            DownloadAction.UNREAD_CHAPTERS -> downloadNextChapters(null)
            DownloadAction.BOOKMARKED_CHAPTERS -> downloadBookmarkedChapters()
        }
        clearSelection()
    }

    /**
     * 书架多选状态下点「上传」：把所选漫画已下载的章节打包上传到网络图源的 WebDAV 库。
     *
     * 服务器上已经有同名漫画时会弹窗问「合并还是新建」（`UploadManager.pendingDecision`），
     * 界面上由 `LibraryTab` 里那个对话框负责回答。
     */
    fun performUploadAction() {
        val mangas = state.value.selectedManga
        if (mangas.isNotEmpty()) {
            uploadManager.enqueue(mangas.map { it.id }, askOnConflict = true)
        }
        clearSelection()
    }

    /**
     * 这本漫画有没有**至少一章已经下载完成**。
     *
     * 「下载」分类下的「上传」按钮据此置灰：一章都没下完就没什么可传的
     * （`UploadManager.runUpload` 里也有一道同样的守卫，这里只是提前拦住，不让点）。
     *
     * `DownloadCache.getDownloadCount` 数的是**落盘好的**章节目录，`_tmp`（下到一半的）
     * 已经被它排除掉了，所以这个判断等价于「有下载完成的章节」。
     * 它内部只是一次内存查表（`renewCache` 自带节流），非挂起、可在组合期间调用。
     */
    fun hasDownloadedChapters(manga: Manga): Boolean = downloadManager.getDownloadCount(manga) > 0

    // SY -->
    /** 非空表示有上传正挂在服务器同名冲突上等用户拍板，界面据此弹窗。 */
    val pendingUploadDecision: StateFlow<UploadPendingDecision?> = uploadManager.pendingDecision

    /** 用户在冲突弹窗里选了之后交回给 [UploadManager]。 */
    fun resolveUploadDecision(choice: UploadChoice) {
        uploadManager.resolveDecision(choice)
    }

    /**
     * 「下载」分类右下角悬浮「继续」按钮的动作：把该分类**所有**漫画的
     * 「未完成章节 + 未下载章节」一次性加入下载队列。
     *
     * 「该分类里有哪些漫画」直接取当前界面所见的那批（受搜索 / 过滤影响），
     * 这样按钮行为和眼睛看到的一致 —— 用户过滤出 3 本，就只给这 3 本续上。
     *
     * 「未完成」和「未下载」在这里是**同一个判据**，因为入队前只做三件事：
     * 1. 「未上传」的章节跳过（网络图源里只有元数据、没有图可下，
     *    判据与 `getNextUnreadChapter` 一致）
     * 2. 已经在队列里的跳过（否则会重复排队；部分下载 / 失败重试本来就还在队列里）
     * 3. 本地已经下载完成的跳过（`isChapterDownloaded`）
     * 剩下的无论「从没下过」还是「下了一半被中断」，交给
     * [DownloadManager.downloadChapters] 后都会从该章的第一页重跑，
     * 对下到一半的章节是覆盖续下，不会产生半截文件。
     *
     * 只取「未读」章节（`getNextChapters` 的默认口径），与书架多选里的
     * 「未读章节」一致 —— 已读的老章节不在这里回填。
     *
     * 合并图源要按子漫画拆开入队（章节归属于各自的子漫画），写法与
     * [downloadNextChapters] 保持一致。
     *
     * 返回实际入队的章节数；为 0 表示没有可下载的章节，界面据此给个提示。
     */
    suspend fun enqueueAllUnfinishedDownloads(): Int {
        val items = mutableState.value.downloadCategoryItems
        if (items.isEmpty()) return 0

        // 队列整体查一次，去重判断就不用每章扫一遍队列表（书架可能上千本）。
        val queuedChapterIds: Set<Long> = downloadManager.queueState.value
            .map { it.chapter.id }
            .toHashSet()

        var queued = 0
        items.fastForEach { item ->
            val manga = item.libraryManga.manga

            if (manga.source == MERGED_SOURCE_ID) {
                val mergedMangas = getMergedMangaById.await(manga.id).associateBy { it.id }
                getNextChapters.await(manga.id)
                    .groupBy { it.mangaId }
                    .forEach ab@{ (mangaId, chapters) ->
                        val mergedManga = mergedMangas[mangaId] ?: return@ab
                        val toQueue: List<Chapter> = chapters.fastFilterNot { chapter ->
                            chapter.id in queuedChapterIds ||
                                chapter.url.isPendingChapterUrl() ||
                                downloadManager.isChapterDownloaded(
                                    chapter.name,
                                    chapter.scanlator,
                                    chapter.url,
                                    mergedManga.ogTitle,
                                    mergedManga.source,
                                )
                        }
                        if (toQueue.isNotEmpty()) {
                            downloadManager.downloadChapters(mergedManga, toQueue)
                            queued += toQueue.size
                        }
                    }
                return@fastForEach
            }

            val toQueue: List<Chapter> = getNextChapters.await(manga.id)
                .fastFilterNot { chapter ->
                    chapter.id in queuedChapterIds ||
                        chapter.url.isPendingChapterUrl() ||
                        downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.ogTitle,
                            manga.source,
                        )
                }
            if (toQueue.isNotEmpty()) {
                downloadManager.downloadChapters(manga, toQueue)
                queued += toQueue.size
            }
        }
        return queued
    }

    /**
     * 「下载」分类右下角悬浮「继续」→ 弹窗里选「上传」/「下载和上传」时的动作：
     * 把该分类里**已经下载完成**的漫画一次性排进上传队列。
     *
     * 与 [enqueueAllUnfinishedDownloads] 共用「当前界面所见的那批」口径（受搜索 / 过滤影响），
     * 这样按钮行为和眼睛看到的一致。
     *
     * 只需筛「有没有下载完的章节」：`UploadManager.enqueue` 内部会自己展开成
     * 「一话一个任务」并跳过本地没内容的那些，所以这里不用把章节列出来 ——
     * 反而不能自己先展开，否则会绕开 `UploadManager` 里那套批次记账
     *（见 `MangaBatchState`：收尾写 config.json 靠的就是它）。
     *
     * `askOnConflict = true`：这是用户主动点的，服务器上若已有同名漫画应当让他自己选
     * 「合并」还是「新建」。（自动上传那条路才用 false。）
     *
     * 返回实际排队的漫画本数；为 0 表示这本都没有可传的。
     */
    fun enqueueAllDownloadedForUpload(): Int {
        val ids = mutableState.value.downloadCategoryItems
            .map { it.libraryManga.manga }
            .filter { downloadManager.getDownloadCount(it) > 0 }
            .map { it.id }

        if (ids.isEmpty()) return 0
        uploadManager.enqueue(ids.distinct(), askOnConflict = true)
        return ids.size
    }

    /** 网络图源配置好了没有，供「继续」弹窗把「上传」两项置灰。 */
    fun isUploadAvailable(): Boolean = uploadManager.isUploadAvailable()

    /**
     * 「继续」弹窗 → 上传 → 「继续之前的任务」。
     *
     * 与 [enqueueAllDownloadedForUpload] 的区别是**不重新扫描本地**：直接把上次退出时
     * 留下的上传队列接着跑。队列本身在 `UploadManager` 恢复时就已经重建好了
     *（`restoreQueue`），只是被 `restoredQueuePendingResume` 闸门挡着等用户点继续；
     * `startUploads()` 正好放开它（见 `UploadManager.restoredQueuePendingResume`）。
     *
     * 队列为空时这也是个安全的空操作 —— 只是把「上传正在跑」这个开关打开。
     */
    fun resumeUploads() {
        uploadManager.startUploads()
    }

    /** 「暂停」弹窗 → 下载。只停下载，正在传的上传不受影响。 */
    fun pauseDownloads() {
        downloadManager.pauseDownloads()
    }

    /** 「暂停」弹窗 → 上传。只停止开始新的一话，正在传的那一话会传完。 */
    fun pauseUploads() {
        uploadManager.pauseUploads()
    }

    /** 「暂停」弹窗 → 下载和上传。 */
    fun pauseDownloadsAndUploads() {
        downloadManager.pauseDownloads()
        uploadManager.pauseUploads()
    }
    // SY <--

    /**
     * 汇总书架「下载」分类下每个项目当前的下载 / 上传活动情况。
     *
     * 下载侧取「正在跑（或排在队首）那一章」的页数：书架那条进度条就是
     * 「已下载页数 / 下载总页数」。
     *
     * 同时算出**章节口径**那一条（画在页数条下面）：已下载章节数 /
     * （已下载 + 队列中未下载）章节数。用户明确要求**不在队列中的章节不算**，
     * 所以这里分子分母都只看「本地已下好的」和「已经在队列里的」两类，
     * 没入队的章节既不进分子也不进分母。
     *
     * 下载与上传都空闲时返回空表，所以书架上绝大多数项目连进度数据都不会分配。
     */
    private fun buildLibraryProgress(): Map<Long, LibraryMangaProgress> {
        val queue = downloadManager.queueState.value
        val downloads = queue
            .filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.QUEUE }
            .groupBy { it.manga.id }
        val uploads = uploadManager.state.value

        val ids = downloads.keys + uploads.keys
        if (ids.isEmpty()) return emptyMap()

        // SY -->
        // 「队列里还没下完的章节数」按 manga 分组数一次就够，下面的循环只是查表。
        // 注意 `downloads` 已经滤掉了 COMPLETE / ERROR 等终态，两者口径一致：
        // 刚从队列里下完的那一章会立刻转到 `DownloadCache` 里被 `getDownloadCount` 数到，
        // 所以进度不会出现「两头都不算」的空档。
        val pendingByManga = downloads.mapValues { (_, queued) -> queued.size }
        // SY <--

        return ids.associateWith { id ->
            // 优先取真正在跑的那章；都在排队时取队首，这样点下下载就能看到进度条。
            val active = downloads[id]?.let { queued ->
                queued.firstOrNull { it.status == Download.State.DOWNLOADING } ?: queued.firstOrNull()
            }
            // SY -->
            val item = mutableState.value.libraryData.favoritesById[id]
            val chaptersDone = item?.downloadCount ?: 0
            val upload = uploads[id]
            // 上传页数条取「正在上传那一话」，与下载条取「正在下载那一章」严格对齐：
            //   1) 正在传的那一话（正常情况）；
            //   2) 本批次里还没轮到传的那一话（排队中 / 等这一话下载完）—— 让刚入队就有条可看；
            //   3) 失败的那一话（上传已停，但要能把失败的进度显示出来）。
            // 顺序依赖 `UploadState.chapters` 的插入顺序，也就是本批次的章节顺序。
            val uploadChapter = upload?.chapters?.values?.let { chapters ->
                chapters.firstOrNull { it.status == UploadStatus.UPLOADING }
                    ?: chapters.firstOrNull {
                        it.status == UploadStatus.QUEUED || it.status == UploadStatus.WAITING_CONFIRM
                    }
                    ?: chapters.firstOrNull { it.status == UploadStatus.ERROR }
            }
            // SY <--
            LibraryMangaProgress(
                downloading = active != null,
                // `downloadedImages` 是 pages 里状态为 Ready 的页数，正好是「已下载页数」
                pagesDone = active?.downloadedImages ?: 0,
                // 页表要等 getPageList 回来才有，取不到就是 0（这时进度条显示 0%）
                pagesTotal = active?.pages?.size ?: 0,
                // SY -->
                // 上传页数条也是「页数」口径，但看的是**当前这一话**（不是整批合计）
                uploadPagesDone = uploadChapter?.pagesUploaded ?: 0,
                uploadPagesTotal = uploadChapter?.pagesTotal ?: 0,
                // 章节口径：整批已传几话 / 需传几话（`UploadState.uploaded` / `total`）
                uploadChaptersDone = upload?.uploaded ?: 0,
                uploadChaptersTotal = upload?.total ?: 0,
                uploadStatus = upload?.status,
                // SY <--
                // SY -->
                chaptersDone = chaptersDone,
                chaptersPending = pendingByManga[id] ?: 0,
                // SY <--
            )
        }
    }

    private fun downloadNextChapters(amount: Int?) {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                // SY -->
                if (manga.source == MERGED_SOURCE_ID) {
                    val mergedMangas = getMergedMangaById.await(manga.id)
                        .associateBy { it.id }
                    getNextChapters.await(manga.id)
                        .let { if (amount != null) it.take(amount) else it }
                        .groupBy { it.mangaId }
                        .forEach ab@{ (mangaId, chapters) ->
                            val mergedManga = mergedMangas[mangaId] ?: return@ab
                            val downloadChapters = chapters.fastFilterNot { chapter ->
                                downloadManager.queueState.value.fastAny { chapter.id == it.chapter.id } ||
                                    downloadManager.isChapterDownloaded(
                                        chapter.name,
                                        chapter.scanlator,
                                        chapter.url,
                                        mergedManga.ogTitle,
                                        mergedManga.source,
                                    )
                            }

                            downloadManager.downloadChapters(mergedManga, downloadChapters)
                        }

                    return@forEach
                }
                // SY <--

                val chapters = getNextChapters.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                // SY -->
                                manga.ogTitle,
                                // SY <--
                                manga.source,
                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    private fun downloadBookmarkedChapters() {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                // SY -->
                if (manga.source == MERGED_SOURCE_ID) {
                    val mergedMangas = getMergedMangaById.await(manga.id)
                        .associateBy { it.id }
                    getBookmarkedChaptersByMangaId.await(manga.id)
                        .groupBy { it.mangaId }
                        .forEach ab@{ (mangaId, chapters) ->
                            val mergedManga = mergedMangas[mangaId] ?: return@ab
                            val downloadChapters = chapters.fastFilterNot { chapter ->
                                downloadManager.queueState.value.fastAny { chapter.id == it.chapter.id } ||
                                    downloadManager.isChapterDownloaded(
                                        chapter.name,
                                        chapter.scanlator,
                                        chapter.url,
                                        mergedManga.ogTitle,
                                        mergedManga.source,
                                    )
                            }

                            downloadManager.downloadChapters(mergedManga, downloadChapters)
                        }

                    return@forEach
                }
                // SY <--

                val chapters = getBookmarkedChaptersByMangaId.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                // SY -->
                                manga.ogTitle,
                                // SY <--
                                manga.source,
                            )
                    }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * 排队下载所选漫画的全部章节（未下载的），供「所有章节」菜单项使用。
     */
    private fun downloadAllChapters() {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                // SY -->
                if (manga.source == MERGED_SOURCE_ID) {
                    val mergedMangas = getMergedMangaById.await(manga.id)
                        .associateBy { it.id }
                    getMergedChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
                        .groupBy { it.mangaId }
                        .forEach ab@{ (mangaId, chapters) ->
                            val mergedManga = mergedMangas[mangaId] ?: return@ab
                            val downloadChapters = chapters.fastFilterNot { chapter ->
                                downloadManager.queueState.value.fastAny { chapter.id == it.chapter.id } ||
                                    downloadManager.isChapterDownloaded(
                                        chapter.name,
                                        chapter.scanlator,
                                        chapter.url,
                                        mergedManga.ogTitle,
                                        mergedManga.source,
                                    )
                            }

                            downloadManager.downloadChapters(mergedManga, downloadChapters)
                        }

                    return@forEach
                }
                // SY <--

                val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                // SY -->
                                manga.ogTitle,
                                // SY <--
                                manga.source,
                            )
                    }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    // SY -->
    fun cleanTitles() {
        state.value.selectedManga.fastFilter {
            it.isEhBasedManga() ||
                it.source in nHentaiSourceIds
        }.fastForEach { manga ->
            val editedTitle =
                manga.title.replace("\\[.*?]".toRegex(), "").trim().replace("\\(.*?\\)".toRegex(), "").trim()
                    .replace("\\{.*?\\}".toRegex(), "").trim().let {
                        if (it.contains("|")) {
                            it.replace(".*\\|".toRegex(), "").trim()
                        } else {
                            it
                        }
                    }
            if (manga.title == editedTitle) return@fastForEach
            val mangaInfo = CustomMangaInfo(
                id = manga.id,
                title = editedTitle.nullIfBlank(),
                author = manga.author.takeUnless { it == manga.ogAuthor },
                artist = manga.artist.takeUnless { it == manga.ogArtist },
                thumbnailUrl = manga.thumbnailUrl.takeUnless { it == manga.ogThumbnailUrl },
                description = manga.description.takeUnless { it == manga.ogDescription },
                genre = manga.genre.takeUnless { it == manga.ogGenre },
                status = manga.status.takeUnless { it == manga.ogStatus },
            )

            setCustomMangaInfo.set(mangaInfo)
        }
        clearSelection()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun syncMangaToDex() {
        launchIO {
            MdUtil.getEnabledMangaDex(sourcePreferences, sourceManager)?.let { mdex ->
                state.value.selectedManga.fastFilter { it.source in mangaDexSourceIds }.fastForEach { manga ->
                    mdex.updateFollowStatus(MdUtil.getMangaId(manga.url), FollowStatus.READING)
                }
            }
            clearSelection()
        }
    }

    fun resetInfo() {
        state.value.selectedManga.fastForEach { manga ->
            val mangaInfo = CustomMangaInfo(
                id = manga.id,
                title = null,
                author = null,
                artist = null,
                thumbnailUrl = null,
                description = null,
                genre = null,
                status = null,
            )

            setCustomMangaInfo.set(mangaInfo)
        }
        clearSelection()
    }
// SY <--

    /**
     * Marks mangas' chapters read status.
     */
    fun markReadSelection(read: Boolean) {
        val selection = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            selection.forEach { manga ->
                setReadStatus.await(
                    manga = manga,
                    read = read,
                )
            }
        }
        clearSelection()
    }

    /**
     * Remove the selected manga.
     *
     * @param mangas the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    fun removeMangas(mangas: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        screenModelScope.launchNonCancellable {
            if (deleteFromLibrary) {
                val toDelete = mangas.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                deleteDownloadedChapters(mangas)
            }
        }
    }

    // SY -->
    /**
     * 「下载」分类里的删除：**只清本地下载**，并把这本漫画移出「下载」分类，其余一概不动。
     *
     * 与 [removeMangas] 的区别有两点，都是需求指定的：
     *
     * - **不碰书架归属**（`favorite` 保持不动）。漫画仍然留在它原有的其他分类里，
     *   包括隐式的「默认」，只是不再出现在「下载」页；也就是这个删除**只作用于「下载」页**。
     * - **不问那两个勾选项**（从书架删除 / 删除已下载章节）。下载分类本身就是「我在管下载」
     *   的语境，再问一遍是多余的，二次确认一次就够。
     *
     * 移出分类这里**显式做一次**：`DownloadManager.deleteManga` 内部其实已经会调
     * `DownloadCategory.removeManga`，但那是跟着「本地下载目录」走的 ——
     * 万一分类里留了一本没有下载的漫画（下载失败、目录被手动删过之类），
     * 不显式清一次它就永远卡在「下载」里出不去：用户在界面上手动改分类是**去不掉**它的
     * （见 `DownloadCategory.resolveUserSelection`），删下载才是唯一出口。
     */
    fun removeDownloadedMangas(mangas: List<Manga>) {
        screenModelScope.launchNonCancellable {
            deleteDownloadedChapters(mangas)
            mangas.forEach { downloadCategory.removeManga(it.id) }
        }
    }

    /** 删掉这些漫画的本地下载。合并图源要连同它下面的各个子图源一起删。 */
    private suspend fun deleteDownloadedChapters(mangas: List<Manga>) {
        mangas.forEach { manga ->
            val source = sourceManager.get(manga.source) as? HttpSource
            if (source != null) {
                if (source is MergedSource) {
                    val mergedMangas = getMergedMangaById.await(manga.id)
                    val sources = mergedMangas.distinctBy {
                        it.source
                    }.map { sourceManager.getOrStub(it.source) }
                    mergedMangas.forEach merge@{ mergedManga ->
                        val mergedSource =
                            sources.firstOrNull { mergedManga.source == it.id } as? HttpSource ?: return@merge
                        downloadManager.deleteManga(mergedManga, mergedSource)
                    }
                } else {
                    downloadManager.deleteManga(manga, source)
                }
            }
        }
    }
    // SY <--

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchNonCancellable {
            mangaList.forEach { manga ->
                val categoryIds = getCategories.await(manga.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                // 「下载」是模块自己的成员关系：用户在这里把它勾掉也踢不出去
                // （唯一出口是删掉本地下载），只勾它则补上「默认」。
                // 见 DownloadCategory.resolveUserSelection。
                setMangaCategories.await(manga.id, downloadCategory.resolveUserSelection(manga.id, categoryIds))
            }
        }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode.asState(screenModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(screenModelScope)
    }

    fun getRandomLibraryItem(): LibraryItem? {
        return state.value.libraryData.favorites.randomOrNull()
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet) }
    }

    // SY -->
    fun showRecommendationSearchDialog() {
        val mangaList = state.value.selectedManga
        mutableState.update { it.copy(dialog = Dialog.RecommendationSearchSheet(mangaList)) }
    }

    private suspend fun filterLibrary(
        unfiltered: List<LibraryItem>,
        query: String?,
        loggedInTrackServices: Map<Long, TriState>,
    ): List<LibraryItem> {
        return if (unfiltered.isNotEmpty() && !query.isNullOrBlank()) {
            // Prepare filter object
            val parsedQuery = searchEngine.parseQuery(query)
            val mangaWithMetaIds = getIdsOfFavoriteMangaWithMetadata.await()
            val tracks = if (loggedInTrackServices.isNotEmpty()) {
                getTracks.await().groupBy { it.mangaId }
            } else {
                emptyMap()
            }
            val sources = unfiltered
                .distinctBy { it.libraryManga.manga.source }
                .fastMapNotNull { sourceManager.get(it.libraryManga.manga.source) }
                .associateBy { it.id }
            unfiltered.asFlow().cancellable().filter { item ->
                val mangaId = item.libraryManga.manga.id
                if (query.startsWith("id:", true)) {
                    val id = query.substringAfter("id:").toLongOrNull()
                    return@filter mangaId == id
                }
                val sourceId = item.libraryManga.manga.source
                if (isMetadataSource(sourceId)) {
                    if (mangaWithMetaIds.binarySearch(mangaId) < 0) {
                        // No meta? Filter using title
                        filterManga(
                            queries = parsedQuery,
                            libraryManga = item.libraryManga,
                            tracks = tracks[mangaId],
                            source = sources[sourceId],
                            loggedInTrackServices = loggedInTrackServices,
                        )
                    } else {
                        val tags = getSearchTags.await(mangaId)
                        val titles = getSearchTitles.await(mangaId)
                        filterManga(
                            queries = parsedQuery,
                            libraryManga = item.libraryManga,
                            tracks = tracks[mangaId],
                            source = sources[sourceId],
                            checkGenre = false,
                            searchTags = tags,
                            searchTitles = titles,
                            loggedInTrackServices = loggedInTrackServices,
                        )
                    }
                } else {
                    filterManga(
                        queries = parsedQuery,
                        libraryManga = item.libraryManga,
                        tracks = tracks[mangaId],
                        source = sources[sourceId],
                        loggedInTrackServices = loggedInTrackServices,
                    )
                }
            }.toList()
        } else {
            unfiltered
        }
    }

    private fun filterManga(
        queries: List<QueryComponent>,
        libraryManga: LibraryManga,
        tracks: List<Track>?,
        source: Source?,
        checkGenre: Boolean = true,
        searchTags: List<SearchTag>? = null,
        searchTitles: List<SearchTitle>? = null,
        loggedInTrackServices: Map<Long, TriState>,
    ): Boolean {
        val manga = libraryManga.manga
        val sourceIdString = manga.source.takeUnless { it == LocalSource.ID }?.toString()
        val genre = if (checkGenre) manga.genre.orEmpty() else emptyList()
        val context = Injekt.get<Application>()
        return queries.all { queryComponent ->
            when (queryComponent.excluded) {
                false -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        manga.title.contains(query, true) ||
                            (manga.author?.contains(query, true) == true) ||
                            (manga.artist?.contains(query, true) == true) ||
                            (manga.description?.contains(query, true) == true) ||
                            (source?.name?.contains(query, true) == true) ||
                            (sourceIdString != null && sourceIdString == query) ||
                            (
                                loggedInTrackServices.isNotEmpty() &&
                                    tracks != null &&
                                    filterTracks(query, tracks, context)
                                ) ||
                            (genre.fastAny { it.contains(query, true) }) ||
                            (searchTags?.fastAny { it.name.contains(query, true) } == true) ||
                            (searchTitles?.fastAny { it.title.contains(query, true) } == true)
                    }

                    is Namespace -> {
                        searchTags != null &&
                            searchTags.fastAny {
                                val tag = queryComponent.tag
                                (
                                    it.namespace.equals(queryComponent.namespace, true) &&
                                        tag?.run { it.name.contains(tag.asQuery(), true) } == true
                                    ) ||
                                    (tag == null && it.namespace.equals(queryComponent.namespace, true))
                            }
                    }

                    else -> true
                }

                true -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        query.isBlank() ||
                            (
                                (!manga.title.contains(query, true)) &&
                                    (manga.author?.contains(query, true) != true) &&
                                    (manga.artist?.contains(query, true) != true) &&
                                    (manga.description?.contains(query, true) != true) &&
                                    (source?.name?.contains(query, true) != true) &&
                                    (sourceIdString != null && sourceIdString != query) &&
                                    (
                                        loggedInTrackServices.isEmpty() ||
                                            tracks == null ||
                                            !filterTracks(query, tracks, context)
                                        ) &&
                                    (!genre.fastAny { it.contains(query, true) }) &&
                                    (searchTags?.fastAny { it.name.contains(query, true) } != true) &&
                                    (searchTitles?.fastAny { it.title.contains(query, true) } != true)
                                )
                    }

                    is Namespace -> {
                        val searchedTag = queryComponent.tag?.asQuery()
                        searchTags == null ||
                            (queryComponent.namespace.isBlank() && searchedTag.isNullOrBlank()) ||
                            searchTags.fastAll { mangaTag ->
                                if (queryComponent.namespace.isBlank() && !searchedTag.isNullOrBlank()) {
                                    !mangaTag.name.contains(searchedTag, true)
                                } else if (searchedTag.isNullOrBlank()) {
                                    mangaTag.namespace == null ||
                                        !mangaTag.namespace.equals(queryComponent.namespace, true)
                                } else if (mangaTag.namespace.isNullOrBlank()) {
                                    true
                                } else {
                                    !mangaTag.name.contains(searchedTag, true) ||
                                        !mangaTag.namespace.equals(queryComponent.namespace, true)
                                }
                            }
                    }

                    else -> true
                }
            }
        }
    }

    private fun filterTracks(constraint: String, tracks: List<Track>, context: Context): Boolean {
        return tracks.fastAny { track ->
            val trackService = trackerManager.get(track.trackerId)
            if (trackService != null) {
                val status = trackService.getStatus(track.status)?.let {
                    context.stringResource(it)
                }
                val name = trackerManager.get(track.trackerId)?.name
                status?.contains(constraint, true) == true || name?.contains(constraint, true) == true
            } else {
                false
            }
        }
    }
// SY <--

    private var lastSelectionCategory: Long? = null

    fun clearSelection() {
        lastSelectionCategory = null
        mutableState.update { it.copy(selection = setOf()) }
    }

    fun toggleSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { set ->
                if (!set.remove(manga.id)) set.add(manga.id)
            }
            lastSelectionCategory = category.id.takeIf { newSelection.isNotEmpty() }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Selects all mangas between and including the given manga and the last pressed manga from the
     * same category as the given manga
     */
    fun toggleRangeSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val lastSelected = list.lastOrNull()
                if (lastSelectionCategory != category.id) {
                    list.add(manga.id)
                    return@mutate
                }

                val items = state.getItemsForCategoryId(category.id).fastMap { it.id }
                val lastMangaIndex = items.indexOf(lastSelected)
                val curMangaIndex = items.indexOf(manga.id)

                val selectionRange = when {
                    lastMangaIndex < curMangaIndex -> lastMangaIndex..curMangaIndex
                    curMangaIndex < lastMangaIndex -> curMangaIndex..lastMangaIndex
                    // We shouldn't reach this point
                    else -> return@mutate
                }
                selectionRange.mapNotNull { items[it] }.let(list::addAll)
            }
            lastSelectionCategory = category.id
            state.copy(selection = newSelection)
        }
    }

    fun selectAll() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                state.getItemsForCategoryId(state.activeCategory?.id).map { it.id }.let(list::addAll)
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val itemIds = state.getItemsForCategoryId(state.activeCategory?.id).fastMap { it.id }
                val (toRemove, toAdd) = itemIds.partition { it in list }
                list.removeAll(toRemove.toSet())
                list.addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun updateActiveCategoryIndex(index: Int) {
        val newIndex = mutableState.updateAndGet { state ->
            state.copy(activeCategoryIndex = index)
        }
            .coercedActiveCategoryIndex

        libraryPreferences.lastUsedCategory.set(newIndex)
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            // Create a copy of selected manga
            val mangaList = state.value.selectedManga

            // Hide the default category because it has a different behavior than the ones from db.
            // SY -->
            val categories = state.value.libraryData.categories.filter { it.id != 0L }
            // SY <--

            // Get indexes of the common categories to preselect.
            val common = getCommonCategories(mangaList)
            // Get indexes of the mix categories to preselect.
            val mix = getMixCategories(mangaList)
            val preselected = categories
                .map {
                    when (it) {
                        in common -> CheckboxState.State.Checked(it)
                        in mix -> CheckboxState.TriState.Exclude(it)
                        else -> CheckboxState.State.None(it)
                    }
                }

            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(mangaList, preselected)) }
        }
    }

    fun openDeleteMangaDialog() {
        // SY -->
        // 「下载」分类里的删除是另一回事（只清下载、移出该分类，不动书架归属），
        // 所以在这里就把模式定下来 —— 在弹出那一刻决定，而不是等用户点确认时再去读
        // 当前分类（那时状态可能已经变了）。
        val downloadsOnly = state.value.activeCategory?.isDownloadCategory == true
        // SY <--
        mutableState.update { it.copy(dialog = Dialog.DeleteManga(state.value.selectedManga, downloadsOnly)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Dialog {
        data object SettingsSheet : Dialog
        data class ChangeCategory(
            val manga: List<Manga>,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog

        // SY -->
        /**
         * @param downloadsOnly 来自「下载」分类：只清本地下载并移出该分类，不问勾选项、
         *   也不动书架归属（见 [removeDownloadedMangas]）。其余分类里是常规的 [removeMangas] 流程。
         */
        data class DeleteManga(val manga: List<Manga>, val downloadsOnly: Boolean = false) : Dialog
        // SY <--

        // SY -->
        data object SyncFavoritesWarning : Dialog
        data object SyncFavoritesConfirm : Dialog
        data class RecommendationSearchSheet(val manga: List<Manga>) : Dialog
        // SY <--
    }

// SY -->

    /** Returns first unread chapter of a manga */
    suspend fun getFirstUnread(manga: Manga): Chapter? {
        return getNextChapters.await(manga.id).firstOrNull()
    }

    private fun List<LibraryItem>.getGroupedMangaItems(
        groupType: Int,
    ): Map<Category, List</* LibraryItem */ Long>> {
        val context = preferences.context
        return when (groupType) {
            LibraryGroup.BY_TRACK_STATUS -> {
                val tracks = runBlocking { getTracks.await() }.groupBy { it.mangaId }
                groupBy { item ->
                    val status = tracks[item.libraryManga.manga.id]?.firstNotNullOfOrNull { track ->
                        TrackStatus.parseTrackerStatus(trackerManager, track.trackerId, track.status)
                    } ?: TrackStatus.OTHER

                    status.int
                }.mapKeys { (id) ->
                    Category(
                        id = id.toLong(),
                        name = TrackStatus.entries
                            .find { it.int == id }
                            .let { it ?: TrackStatus.OTHER }
                            .let { context.stringResource(it.res) },
                        order = TrackStatus.entries.indexOfFirst {
                            it.int == id
                        }.takeUnless { it == -1 }?.toLong() ?: TrackStatus.OTHER.ordinal.toLong(),
                        flags = 0,
                    )
                }
            }

            LibraryGroup.BY_SOURCE -> {
                val sources: List<Long>
                groupBy { item ->
                    item.libraryManga.manga.source
                }.also {
                    sources = it.keys
                        .map {
                            sourceManager.getOrStub(it)
                        }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id.toString() } })
                        .map { it.id }
                }.mapKeys {
                    Category(
                        id = it.key,
                        name = if (it.key == LocalSource.ID) {
                            context.stringResource(MR.strings.local_source)
                        } else {
                            val source = sourceManager.getOrStub(it.key)
                            source.name.ifBlank { source.id.toString() }
                        },
                        order = sources.indexOf(it.key).takeUnless { it == -1 }?.toLong() ?: Long.MAX_VALUE,
                        flags = 0,
                    )
                }
            }

            LibraryGroup.BY_STATUS -> {
                groupBy { item ->
                    item.libraryManga.manga.status
                }.mapKeys {
                    Category(
                        id = it.key + 1,
                        name = when (it.key) {
                            SManga.ONGOING.toLong() -> context.stringResource(MR.strings.ongoing)
                            SManga.LICENSED.toLong() -> context.stringResource(MR.strings.licensed)
                            SManga.CANCELLED.toLong() -> context.stringResource(MR.strings.cancelled)
                            SManga.ON_HIATUS.toLong() -> context.stringResource(MR.strings.on_hiatus)
                            SManga.PUBLISHING_FINISHED.toLong() -> context.stringResource(MR.strings.publishing_finished)
                            SManga.COMPLETED.toLong() -> context.stringResource(MR.strings.completed)
                            else -> context.stringResource(MR.strings.unknown)
                        },
                        order = when (it.key) {
                            SManga.ONGOING.toLong() -> 1
                            SManga.LICENSED.toLong() -> 2
                            SManga.CANCELLED.toLong() -> 3
                            SManga.ON_HIATUS.toLong() -> 4
                            SManga.PUBLISHING_FINISHED.toLong() -> 5
                            SManga.COMPLETED.toLong() -> 6
                            else -> 7
                        },
                        flags = 0,
                    )
                }
            }

            else -> emptyMap()
        }.toSortedMap(compareBy { it.order })
            .mapValues { (_, libraryItem) -> libraryItem.fastMap { it.id } }
    }

    fun runRecommendationSearch(selection: List<Manga>) {
        recommendationSearch.runSearch(screenModelScope, selection)?.let {
            recommendationSearchJob = it
        }
    }

    fun cancelRecommendationSearch() {
        recommendationSearchJob?.cancel()
    }

    fun runSync() {
        favoritesSync.runSync(screenModelScope)
    }

    fun onAcceptSyncWarning() {
        exhPreferences.exhShowSyncIntro.set(false)
    }

    fun openFavoritesSyncDialog() {
        mutableState.update {
            it.copy(
                dialog = if (exhPreferences.exhShowSyncIntro.get()) {
                    Dialog.SyncFavoritesWarning
                } else {
                    Dialog.SyncFavoritesConfirm
                },
            )
        }
    }
// SY <--

    @Immutable
    private data class ItemPreferences(
        val downloadBadge: Boolean,
        val unreadBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,
        val skipOutsideReleasePeriod: Boolean,

        val globalFilterDownloaded: Boolean,
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
        // SY -->
        val filterLewd: TriState,
        // SY <--
    )

    @Immutable
    data class LibraryData(
        val isInitialized: Boolean = false,
        val showSystemCategory: Boolean = false,
        val categories: List<Category> = emptyList(),
        val favorites: List<LibraryItem> = emptyList(),
        val tracksMap: Map</* Manga */ Long, List<Track>> = emptyMap(),
        val loggedInTrackerIds: Set<Long> = emptySet(),
    ) {
        val favoritesById by lazy { favorites.associateBy { it.id } }
    }

    @Immutable
    data class State(
        val isInitialized: Boolean = false,
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val selection: Set</* Manga */ Long> = setOf(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showMangaCount: Boolean = false,
        val showMangaContinueButton: Boolean = false,
        val dialog: Dialog? = null,
        val libraryData: LibraryData = LibraryData(),
        // SY -->
        /** 「下载」分类下进度条的数据源，键是 manga id（见 [buildLibraryProgress]）。 */
        val libraryProgress: Map<Long, LibraryMangaProgress> = emptyMap(),
        /**
         * 「下载」分类多选时「上传」按钮能不能点（网络图源已填好服务器信息）。
         * 见 [UploadManager.isUploadAvailable]。
         */
        val isUploadAvailable: Boolean = false,
        // SY <--
        private val activeCategoryIndex: Int = 0,
        private val groupedFavorites: Map<Category, List</* LibraryItem */ Long>> = emptyMap(),
        // SY -->
        val showSyncExh: Boolean = false,
        val isSyncEnabled: Boolean = false,
        val groupType: Int = LibraryGroup.BY_DEFAULT,
        // SY <--
    ) {
        val displayedCategories: List<Category> = groupedFavorites.keys.toList()

        val coercedActiveCategoryIndex = activeCategoryIndex.coerceIn(
            minimumValue = 0,
            maximumValue = displayedCategories.lastIndex.coerceAtLeast(0),
        )

        val activeCategory: Category? = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        val isLibraryEmpty = libraryData.favorites.isEmpty()

        val selectionMode = selection.isNotEmpty()

        val selectedManga by lazy { selection.mapNotNull { libraryData.favoritesById[it]?.libraryManga?.manga } }

        // SY -->
        val showCleanTitles: Boolean by lazy {
            selectedManga.fastAny {
                it.isEhBasedManga() ||
                    it.source in nHentaiSourceIds
            }
        }

        val showAddToMangadex: Boolean by lazy {
            selectedManga.any { it.source in mangaDexSourceIds }
        }

        val showResetInfo: Boolean by lazy {
            selectedManga.fastAny { manga ->
                manga.title != manga.ogTitle ||
                    manga.author != manga.ogAuthor ||
                    manga.artist != manga.ogArtist ||
                    manga.thumbnailUrl != manga.ogThumbnailUrl ||
                    manga.description != manga.ogDescription ||
                    manga.genre != manga.ogGenre ||
                    manga.status != manga.ogStatus
            }
        }
        // SY <--

        fun getItemsForCategoryId(categoryId: Long?): List<LibraryItem> {
            if (categoryId == null) return emptyList()
            val category = displayedCategories.find { it.id == categoryId } ?: return emptyList()
            return getItemsForCategory(category)
        }

        fun getItemsForCategory(category: Category): List<LibraryItem> {
            return groupedFavorites[category].orEmpty().mapNotNull { libraryData.favoritesById[it] }
        }

        // SY -->
        /**
         * 「下载」分类当前列出的全部漫画（受搜索 / 过滤影响，与界面所见一致）。
         *
         * 右下角悬浮「继续」按钮据此决定给哪些漫画续下 —— 用户过滤出 3 本，
         * 按钮就只给这 3 本续，行为和眼睛看到的保持一致。
         */
        val downloadCategoryItems: List<LibraryItem>
            get() = displayedCategories
                .firstOrNull { it.isDownloadCategory }
                ?.let { getItemsForCategory(it) }
                .orEmpty()
        // SY <--

        // SY -->
        /**
         * 「下载」分类下某个项目的进度条数据。
         *
         * 查不到就返回 [LibraryMangaProgress.EMPTY]（= 没有任何下载/上传活动），
         * 界面据此**一条进度条都不画**。有任务的项才由 [buildLibraryProgress] 填出数据。
         */
        fun progressFor(item: LibraryItem): LibraryMangaProgress =
            libraryProgress[item.id] ?: LibraryMangaProgress.EMPTY
        // SY <--

        fun getItemCountForCategory(category: Category): Int? {
            return if (showMangaCount || !searchQuery.isNullOrEmpty()) groupedFavorites[category]?.size else null
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val category = displayedCategories.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            val categoryName = category.let {
                if (it.isSystemCategory) defaultCategoryTitle else it.name
            }
            val title = if (showCategoryTabs) defaultTitle else categoryName
            val count = when {
                !showMangaCount -> null
                !showCategoryTabs -> getItemCountForCategory(category)
                // Whole library count
                else -> libraryData.favorites.size
            }
            return LibraryToolbarTitle(title, count)
        }
    }

    // SY -->
    private companion object {
        /**
         * 书架进度条的采样间隔。
         *
         * 400ms 足够让进度条看起来是连续的，又不会让大型书架频繁重组。
         * 只在书架页可见时跑（作用域是 `screenModelScope`）。
         */
        private val LIBRARY_PROGRESS_TICK = 400.milliseconds

        /**
         * 「上传」按钮可用性的采样间隔。
         *
         * 这个值只在用户改图源设置时才会变，不需要进度条那样的密度，1 秒足够及时。
         */
        private val UPLOAD_AVAILABILITY_TICK = 1.seconds
    }
    // SY <--
}
