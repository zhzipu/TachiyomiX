package tachiyomi.domain.source.interactor

import tachiyomi.domain.source.repository.SearchHistoryRepository

class ClearSearchHistory(
    private val searchHistoryRepository: SearchHistoryRepository,
) {

    suspend fun await(scope: String) {
        searchHistoryRepository.deleteByScope(scope)
    }
}
