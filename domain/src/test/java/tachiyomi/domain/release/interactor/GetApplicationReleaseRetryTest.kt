package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import logcat.LogcatLogger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.io.IOException

/**
 * 覆盖「刚启动内置代理还没监听 → 更新检查 ConnectException」那条路的重试行为：
 * 只对 [IOException] 退避重试，别的异常（4xx、解析失败）不重试。
 */
class GetApplicationReleaseRetryTest {

    private lateinit var getApplicationRelease: GetApplicationRelease
    private lateinit var releaseService: ReleaseService

    private val arguments = GetApplicationRelease.Arguments(
        isPreview = false,
        commitCount = 0,
        versionName = "1.0.1",
        repository = "test",
        syDebugVersion = "0",
        forceCheck = true,
    )

    @BeforeEach
    fun beforeEach() {
        // 单测里没有安装 logcat 后端；不清空会走到 android.util.Log（unit test 未 mock 会抛）
        LogcatLogger.loggers.clear()

        val preferenceStore = mockk<PreferenceStore>()
        val preference: Preference<Long> = mockk()
        every { preferenceStore.getLong(any(), any()) } returns preference
        every { preference.get() } returns 0
        every { preference.set(any()) } answers { }
        releaseService = mockk()

        getApplicationRelease = GetApplicationRelease(releaseService, preferenceStore)
    }

    @Test
    fun `When first attempts fail with io error expect retry until success`() = runTest {
        var calls = 0
        coEvery { releaseService.latest(any()) } coAnswers {
            calls++
            // 前两次模拟「内置代理还没起来」，第三次成功
            if (calls < 3) throw IOException("Connection refused") else release("v1.0.1")
        }

        val result = getApplicationRelease.awaitWithRetry(arguments, attempts = 4)

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
        calls shouldBe 3
    }

    @Test
    fun `When all attempts fail with io error expect give up after attempts`() = runTest {
        coEvery { releaseService.latest(any()) } throws IOException("Connection refused")

        val error = runCatching { getApplicationRelease.awaitWithRetry(arguments, attempts = 4) }
            .exceptionOrNull()

        (error is IOException) shouldBe true
        coVerify(exactly = 4) { releaseService.latest(any()) }
    }

    @Test
    fun `When failure is not io error expect no retry`() = runTest {
        coEvery { releaseService.latest(any()) } throws IllegalStateException("HTTP 403")

        val error = runCatching { getApplicationRelease.awaitWithRetry(arguments, attempts = 4) }
            .exceptionOrNull()

        (error is IllegalStateException) shouldBe true
        coVerify(exactly = 1) { releaseService.latest(any()) }
    }

    private fun release(version: String) = Release(
        version,
        "info",
        "http://example.com/release_link",
        listOf("http://example.com/assets"),
    )
}
