package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import app.cash.sqldelight.async.coroutines.awaitAsList
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SavedSearchBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
// SY -->
import eu.kanade.tachiyomi.data.upload.DownloadCategory
import eu.kanade.tachiyomi.data.upload.isDownloadCategory
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
// SY <--
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    // SY -->
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    // SY <--

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator = ExtensionStoresBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
    // SY -->
    private val savedSearchBackupCreator: SavedSearchBackupCreator = SavedSearchBackupCreator(),
    private val getMergedManga: GetMergedManga = Injekt.get(),
    // SY <--
) {

    suspend fun backup(uri: Uri, options: BackupOptions): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                dir?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                    .orEmpty()
                    .sortedByDescending { it.name }
                    .drop(MAX_AUTO_BACKUPS - 1)
                    .forEach { it.delete() }

                // Create new file to place backup
                dir?.createFile(getFilename())
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            val nonFavoriteManga = if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
            // SY -->
            val mergedManga = getMergedManga.await()
            // SY <--
            val backupManga =
                backupMangas(getFavorites.await() + nonFavoriteManga /* SY --> */ + mergedManga /* SY <-- */, options)

            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga),
                backupPreferences = backupAppPreferences(options),
                backupExtensionStores = backupExtensionStores(options),
                backupSourcePreferences = backupSourcePreferences(options),
                // SY -->
                backupSavedSearches = backupSavedSearches(options),
                // SY <--
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            file.openOutputStream()
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink().gzip().buffer().use {
                    it.write(byteArray)
                }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator(context).validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp.set(Instant.now().toEpochMilli())
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        // SY -->
        // 「下载」不进备份：它是本机的下载状态，同步到别的设备只会多出一个空壳页签。
        // 名字比较等同于 `Category.isDownloadCategory`（BackupCategory 不是 Category）。
        return categoriesBackupCreator().filterNot { it.name == DownloadCategory.NAME }
        // SY <--
    }

    suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        // SY -->
        // 「下载」里的漫画整本排除：收藏状态、其它分类、阅读进度都不发出去。
        // 必须和上面 backupCategories 的排除**成对**，否则远端会拿到一个不存在的分类 order。
        // 恢复侧的对偶逻辑：`MangaRestorer.restoreCategories` 保住本机的「下载」成员关系，
        // `SyncManager` 里删「远端没有的本地分类」时也会跳过「下载」。
        val downloadMangaIds = downloadCategoryMangaIds()
        return mangaBackupCreator(mangas.filterNot { it.id in downloadMangaIds }, options)
        // SY <--
    }

    // SY -->
    /** 「下载」分类的成员（漫画 id）。分类不存在时是空集。 */
    private suspend fun downloadCategoryMangaIds(): Set<Long> {
        val categoryId = getCategories.await().firstOrNull { it.isDownloadCategory }?.id ?: return emptySet()
        return database.mangas_categoriesQueries
            .getMangaIdsByCategoryId(categoryId)
            .awaitAsList()
            .toSet()
    }
    // SY <--

    fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    // SY -->
    suspend fun backupSavedSearches(options: BackupOptions): List<BackupSavedSearch> {
        if (!options.savedSearches) return emptyList()

        return savedSearchBackupCreator()
    }
    // SY <--

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
