package tachiyomi.data.release

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProxyScope
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
            // 走「版本检测」作用域，默认直连：内置 Clash 的出口节点会把 api.github.com 的 TLS 握手重置
            // （SSLHandshakeException: connection closed），且多个用户共用同一个出口 IP，GitHub 未认证接口
            // 60/h 的额度极易被刷满（实测该出口 remaining=0，直连却是 200）。用户勾选该作用域后才走代理。
            networkService.clientFor(ProxyScope.VERSION_CHECK)
                .newCall(GET("https://api.github.com/repos/$repository/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
                .let(releaseMapper)
        }
    }
}
