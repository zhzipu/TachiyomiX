package tachiyomi.domain.source.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.repository.SearchHistoryRepository

class GetSearchHistory(
    private val searchHistoryRepository: SearchHistoryRepository,
) {

    fun subscribe(scope: String, limit: Int = DEFAULT_LIMIT): Flow<List<String>> {
        return searchHistoryRepository.getQueries(scope, limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 25
    }
}
