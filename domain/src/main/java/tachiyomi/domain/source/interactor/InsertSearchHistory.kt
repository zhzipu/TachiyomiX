package tachiyomi.domain.source.interactor

import tachiyomi.domain.source.repository.SearchHistoryRepository

class InsertSearchHistory(
    private val searchHistoryRepository: SearchHistoryRepository,
) {

    suspend fun await(scope: String, query: String) {
        searchHistoryRepository.insert(scope, query)
    }
}
