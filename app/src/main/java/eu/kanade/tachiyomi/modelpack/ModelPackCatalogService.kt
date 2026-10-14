package eu.kanade.tachiyomi.modelpack

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProxyScope
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * 远端插件目录（[REPO_URL] 的 `index.min.json`）里的一条模型包插件。
 *
 * 字段与目录仓库 `generate.py` 生成的精简索引一一对应；`code`/`sha256` 用于判断更新与校验下载。
 */
@Serializable
data class CatalogModelPack(
    val name: String = "",
    val id: String = "",
    val pkg: String = "",
    val version: String = "",
    /** versionCode，与已安装模型包的 versionCode 比较即可判断有没有更新。 */
    val code: Long = 0L,
    val apk: String? = null,
    val size: Long? = null,
    val sha256: String? = null,
    val repo: String? = null,
)

/**
 * 读取模型包插件目录（`设置 → 高级 → 模型包` 上半部分的数据源）。
 *
 * 目录就是一个普通 GitHub 仓库，`index.min.json` 是 JSON 数组，没有鉴权/分页。
 * [SOURCES] 里有多个源时按顺序尝试：jsDelivr CDN 在前（国内通常可达），
 * 失败再退回 raw.githubusercontent —— 用户网络对哪个源更友好就自动用哪个。
 *
 * 走 [ProxyScope.MODEL_PACK_REPO] 作用域：没勾「走代理」时一律直连（与扩展仓库一致）。
 */
class ModelPackCatalogService(
    private val network: NetworkHelper = Injekt.get(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetch(): Result<List<CatalogModelPack>> {
        var lastError: Exception? = null
        SOURCES.forEach { url ->
            runCatching { fetchFrom(url) }
                .onSuccess { return Result.success(it) }
                .onFailure {
                    lastError = it as? Exception
                    logcat(LogPriority.WARN, it) { "读取模型包目录失败：$url" }
                }
        }
        return Result.failure(lastError ?: IllegalStateException("没有可用的模型包目录源"))
    }

    private suspend fun fetchFrom(url: String): List<CatalogModelPack> {
        val client = network.clientFor(ProxyScope.MODEL_PACK_REPO)
            .newBuilder()
            // 目录很小，20s 足够；超时短一点，两个源切换才不会让用户干等
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        val body = client.newCall(GET(url)).awaitSuccess().body.string()
        return json.decodeFromString(SERIALIZER, body)
    }

    companion object {
        /** 目录仓库地址（人可读，设置页里展示）。 */
        const val REPO_URL = "https://github.com/zhzipu/TachiyomiX-ModelPack-Catalog"

        /** 目录仓库显示名。 */
        const val REPO_NAME = "TachiyomiX-ModelPack-Catalog"

        /** 索引源，按顺序尝试。 */
        val SOURCES = listOf(
            "https://cdn.jsdelivr.net/gh/zhzipu/TachiyomiX-ModelPack-Catalog@main/index.min.json",
            "https://raw.githubusercontent.com/zhzipu/TachiyomiX-ModelPack-Catalog/main/index.min.json",
        )

        private val SERIALIZER = ListSerializer(CatalogModelPack.serializer())
    }
}
