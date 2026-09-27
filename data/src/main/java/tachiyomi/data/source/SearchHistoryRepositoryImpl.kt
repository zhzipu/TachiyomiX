package tachiyomi.data.source

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.source.repository.SearchHistoryRepository

class SearchHistoryRepositoryImpl(
    private val database: Database,
) : SearchHistoryRepository {

    override fun getQueries(scope: String, limit: Int): Flow<List<String>> {
        return database.search_historyQueries
            .selectByScope(scope, limit.toLong())
            .subscribeToList()
    }

    override suspend fun insert(scope: String, query: String) {
        database.search_historyQueries.deleteByScopeAndQuery(scope, query)
        database.search_historyQueries.insert(scope, query)
        database.search_historyQueries.deleteOutOfLimit(scope, MAX_ENTRIES.toLong())
    }

    override suspend fun deleteByScope(scope: String) {
        database.search_historyQueries.deleteByScope(scope)
    }

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
