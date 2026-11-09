package eu.kanade.tachiyomi.data.track

import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import tachiyomi.domain.track.model.Track as DomainTrack

interface Tracker {

    val id: Long

    val name: String

    val client: OkHttpClient

    // Application and remote support for reading dates
    val supportsReadingDates: Boolean

    val supportsPrivateTracking: Boolean

    @DrawableRes
    fun getLogo(): Int

    fun getStatusList(): List<Long>

    fun getStatus(status: Long): StringResource?

    fun getReadingStatus(): Long

    fun getRereadingStatus(): Long

    fun getCompletionStatus(): Long

    fun getScoreList(): List<String>

    // TODO: Store all scores as 10 point in the future maybe?
    fun get10PointScore(track: DomainTrack): Double

    fun indexToScore(index: Int): Double

    fun displayScore(track: DomainTrack): String

    suspend fun update(track: Track, didReadChapter: Boolean = false): Track

    suspend fun bind(track: Track, hasReadChapters: Boolean = false): Track

    suspend fun search(query: String): List<TrackSearch>

    suspend fun refresh(track: Track): Track

    suspend fun login(username: String, password: String)

    @CallSuper
    fun logout()

    val isLoggedIn: Boolean

    val isLoggedInFlow: Flow<Boolean>

    fun getUsername(): String

    fun getPassword(): String

    fun getDisplayUsername(): String

    fun saveDisplayUsername(displayName: String)

    fun saveCredentials(username: String, password: String)

    // TODO: move this to an interactor, and update all trackers based on common data
    suspend fun register(item: Track, mangaId: Long)

    suspend fun setRemoteStatus(track: Track, status: Long)

    // KMK --> 返回更新后的 track，供调用方继续沿用（避免本地 progress 被远端旧值覆盖）
    suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int): Track
    // KMK <--

    suspend fun setRemoteScore(track: Track, scoreString: String)

    suspend fun setRemoteStartDate(track: Track, epochMillis: Long)

    suspend fun setRemoteFinishDate(track: Track, epochMillis: Long)

    suspend fun setRemotePrivate(track: Track, private: Boolean)

    // SY -->
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata?

    suspend fun searchById(id: String): TrackSearch?
    // SY <--

    // KMK -->
    /**
     * 该状态表示「尚未开始阅读」。同步进度时，若远端还停在这个状态，
     * 不应把本地的阅读记录倒灌回去（否则会把「想看」错标成「在读/已读」）。
     */
    fun hasNotStartedReading(status: Long): Boolean
    // KMK <--
}
