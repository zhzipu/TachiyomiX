package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant

class GetApplicationReleaseTest {

    lateinit var getApplicationRelease: GetApplicationRelease
    lateinit var releaseService: ReleaseService
    lateinit var preference: Preference<Long>

    @BeforeEach
    fun beforeEach() {
        val preferenceStore = mockk<PreferenceStore>()
        preference = mockk()
        every { preferenceStore.getLong(any(), any()) } returns preference
        releaseService = mockk()

        getApplicationRelease = GetApplicationRelease(releaseService, preferenceStore)
    }

    @Test
    fun `When has update but is preview expect new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val release = Release(
            "200",
            "info",
            "http://example.com/release_link",
            listOf("http://example.com/assets"),
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isPreview = true,
                commitCount = 1000,
                versionName = "",
                repository = "test",
                syDebugVersion = "100",
            ),
        )

        (result as GetApplicationRelease.Result.NewUpdate).release shouldBe GetApplicationRelease.Result.NewUpdate(
            release,
        ).release
    }

    @Test
    fun `When has update expect new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val release = Release(
            "v2.0.0",
            "info",
            "http://example.com/release_link",
            listOf("http://example.com/assets"),
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isPreview = false,
                commitCount = 0,
                versionName = "v1.0.0",
                syDebugVersion = "0",
                repository = "test",
            ),
        )

        (result as GetApplicationRelease.Result.NewUpdate).release shouldBe GetApplicationRelease.Result.NewUpdate(
            release,
        ).release
    }

    @Test
    fun `When has no update expect no new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val release = Release(
            "v1.0.0",
            "info",
            "http://example.com/release_link",
            listOf("http://example.com/assets"),
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isPreview = false,
                commitCount = 0,
                versionName = "v2.0.0",
                syDebugVersion = "0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    @Test
    fun `When now is before three days expect no new update`() = runTest {
        every { preference.get() } returns Instant.now().toEpochMilli()
        every { preference.set(any()) }.answers { }

        val release = Release(
            "v1.0.0",
            "info",
            "http://example.com/release_link",
            listOf("http://example.com/assets"),
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isPreview = false,
                commitCount = 0,
                versionName = "v2.0.0",
                syDebugVersion = "0",
                repository = "test",
            ),
        )

        coVerify(exactly = 0) { releaseService.latest(any()) }
        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    @Test
    fun `When release tag has fewer segments expect no new update instead of crash`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        // 新版 tag `1.1` 只有两段，本机 `1.0.1` 三段：补 0 后 1.1.0 > 1.0.1 才算有新版
        coEvery { releaseService.latest(any()) } returns Release(
            "v1.1",
            "info",
            "http://example.com/release_link",
            listOf("http://example.com/assets"),
        )

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isPreview = false,
                commitCount = 0,
                versionName = "v1.0.1",
                syDebugVersion = "0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NewUpdate(
            Release(
                "v1.1",
                "info",
                "http://example.com/release_link",
                listOf("http://example.com/assets"),
            ),
        )
    }

    @Test
    fun `When release tag is two segments but not newer expect no new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        coEvery { releaseService.latest(any()) } returns Release(
            "v1.0",
            "info",
            "http://example.com/release_link",
            listOf("http://example.com/assets"),
        )

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isPreview = false,
                commitCount = 0,
                versionName = "v1.0.1",
                syDebugVersion = "0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    @Test
    fun `When release major is older expect no new update even if minor is bigger`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        // 旧写法「任意一段更大就算新」会把 1.5.0 判成比 2.0.0 新，给用户推降级更新
        coEvery { releaseService.latest(any()) } returns Release(
            "v1.5.0",
            "info",
            "http://example.com/release_link",
            listOf("http://example.com/assets"),
        )

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isPreview = false,
                commitCount = 0,
                versionName = "v2.0.0",
                syDebugVersion = "0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }
}
