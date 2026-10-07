package tachiyomi.data.release

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(repository: String): Release {
        return with(json) {
            // 必须走 directClient：NetworkHelper 的注释写明「plugin marketplace / update checks /
            // APK downloads 不能走代理」，但这里一直用的是带 ClashProxySelector 的 client ——
            // 内置 Clash 的出口节点会把 api.github.com 的 TLS 握手重置（SSLHandshakeException:
            // connection closed），且多个用户共用同一个出口 IP，GitHub 未认证接口 60/h 的额度极易被刷满
            // （实测该出口 remaining=0，直连却是 200）。
            networkService.directClient
                .newCall(GET("https://api.github.com/repos/$repository/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
                .let(releaseMapper)
        }
    }
}
