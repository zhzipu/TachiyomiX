package tachiyomi.domain.source.repository

import kotlinx.coroutines.flow.Flow

interface SearchHistoryRepository {

    fun getQueries(scope: String, limit: Int): Flow<List<String>>

    suspend fun insert(scope: String, query: String)

    suspend fun deleteByScope(scope: String)
}
