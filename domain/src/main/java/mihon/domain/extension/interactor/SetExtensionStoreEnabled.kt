package mihon.domain.extension.interactor

import mihon.domain.extension.repository.ExtensionStoreRepository

class SetExtensionStoreEnabled(
    private val repository: ExtensionStoreRepository,
) {
    suspend operator fun invoke(indexUrl: String, isEnabled: Boolean) {
        repository.setEnabled(indexUrl, isEnabled)
    }
}
