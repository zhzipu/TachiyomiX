package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import android.util.Log
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val database: Database = Injekt.get(),
    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(isSync),
    // SY -->
    private val savedSearchRestorer: SavedSearchRestorer = SavedSearchRestorer(),
    // SY <--
) {

    private val tag = "WebDavSync"

    private var restoreAmount = 0
    private val restoreProgress = AtomicInt(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        // Invalidate download cache to ensure UI reflects any restored downloads
        if (options.libraryEntries) {
            try {
                Injekt.get<DownloadCache>().invalidateCache()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to invalidate download cache after restore" }
            }
        }

        // Restored extension stores are written directly to the database, but the Plugin Store
        // (available extensions) is served from an in-memory cache that only refreshes when
        // findAvailableExtensions() is called. Re-trigger it here so stores synced or restored
        // from a backup show up without a manual refresh or app restart.
        if (options.extensionStores) {
            try {
                Injekt.get<ExtensionManager>().findAvailableExtensions()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to refresh extension stores after restore" }
            }
        }

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val backup = BackupDecoder(context).decode(uri)

        Log.i(
            tag,
            "restore start: manga=${backup.backupManga.size}, categories=${backup.backupCategories.size}, " +
                "savedSearches=${backup.backupSavedSearches.size}, extStores=${backup.backupExtensionStores.size}, " +
                "options=[library=${options.libraryEntries}, categories=${options.categories}, appSettings=${options.appSettings}, " +
                "sourceSettings=${options.sourceSettings}, extStores=${options.extensionStores}, savedSearches=${options.savedSearches}], " +
                "thread=${Thread.currentThread().name}",
        )

        // Store source mapping for error messages
        val backupMaps = backup.backupSources
        sourceMapping = backupMaps.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size
        }
        if (options.categories) {
            restoreAmount += 1
        }
        // SY -->
        if (options.savedSearches) {
            restoreAmount += 1
        }
        // SY <--
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += backup.backupExtensionStores.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }

        coroutineScope {
            // SY: run restorers sequentially instead of in parallel. Each restorer writes to
            // the same SQLite database; running them concurrently causes write-lock contention
            // that surfaces as SQLITE_BUSY ("database is locked") when the connection pool's
            // busy_timeout is not honored for pooled connections.
            if (options.categories) {
                restoreCategories(backup.backupCategories).join()
            }
            // SY -->
            if (options.savedSearches) {
                restoreSavedSearches(backup.backupSavedSearches).join()
            }
            // SY <--
            if (options.appSettings) {
                restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories }).join()
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(backup.backupSourcePreferences).join()
            }
            if (options.libraryEntries) {
                restoreManga(backup.backupManga, if (options.categories) backup.backupCategories else emptyList()).join()
            }
            if (options.extensionStores) {
                restoreExtensionStores(backup.backupExtensionStores).join()
            }

            // TODO: optionally trigger online library + tracker update
        }

        Log.i(tag, "restore done: ${errors.size} item errors")
    }

    private fun CoroutineScope.restoreCategories(backupCategories: List<BackupCategory>) = launch {
        Log.i(tag, "restoreCategories start: ${backupCategories.size} items, thread=${Thread.currentThread().name}")
        ensureActive()
        categoriesRestorer(backupCategories)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            progress,
            restoreAmount,
            isSync,
        )
        Log.i(tag, "restoreCategories done")
    }

    // SY -->
    private fun CoroutineScope.restoreSavedSearches(backupSavedSearches: List<BackupSavedSearch>) = launch {
        Log.i(tag, "restoreSavedSearches start: ${backupSavedSearches.size} items, thread=${Thread.currentThread().name}")
        ensureActive()
        savedSearchRestorer.restoreSavedSearches(backupSavedSearches)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(SYMR.strings.saved_searches),
            progress,
            restoreAmount,
            isSync,
        )
        Log.i(tag, "restoreSavedSearches done")
    }
    // SY <--

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        Log.i(tag, "restoreManga start: ${backupMangas.size} items, thread=${Thread.currentThread().name}")
        mangaRestorer.sortByNew(backupMangas)
            /* SY --> */.sortedBy { it.source == MERGED_SOURCE_ID } /* SY <-- */
            .chunked(100)
            .forEach { chunk ->
                database.transaction {
                    chunk.forEach {
                        ensureActive()

                        try {
                            mangaRestorer.restore(it, backupCategories)
                        } catch (e: Exception) {
                            val sourceName = sourceMapping[it.source] ?: it.source.toString()
                            errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                        }

                        restoreProgress.incrementAndFetch()
                    }
                }
                notifier.showRestoreProgress(chunk.last().title, restoreProgress.load(), restoreAmount, isSync)
            }
        Log.i(tag, "restoreManga done")
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        Log.i(tag, "restoreAppPreferences start: ${preferences.size} items, thread=${Thread.currentThread().name}")
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            progress,
            restoreAmount,
            isSync,
        )
        Log.i(tag, "restoreAppPreferences done")
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        Log.i(tag, "restoreSourcePreferences start: ${preferences.size} items, thread=${Thread.currentThread().name}")
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            progress,
            restoreAmount,
            isSync,
        )
        Log.i(tag, "restoreSourcePreferences done")
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        Log.i(tag, "restoreExtensionStores start: ${backupExtensionStores.size} items, thread=${Thread.currentThread().name}")
        backupExtensionStores
            .chunked(100)
            .forEach { chunk ->
                database.transaction {
                    chunk.forEach {
                        ensureActive()

                        try {
                            extensionStoreRestorer(it)
                        } catch (e: Exception) {
                            errors.add(Date() to "Error Adding Repo: ${it.name} : ${e.message}")
                        }

                        restoreProgress.incrementAndFetch()
                    }
                }
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionStores),
                    restoreProgress.load(),
                    restoreAmount,
                    isSync,
                )
            }
        Log.i(tag, "restoreExtensionStores done")
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("mihon_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}
