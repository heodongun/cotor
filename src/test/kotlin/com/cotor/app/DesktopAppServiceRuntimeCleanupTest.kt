package com.cotor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.nio.file.Files

class DesktopAppServiceRuntimeCleanupTest : FunSpec({
    test("shutdown clears retained runtime cache state") {
        val appHome = Files.createTempDirectory("desktop-app-service-runtime-cleanup")
        val service = DesktopAppService(
            stateStore = DesktopStateStore { appHome },
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        service.primeRuntimeCachesForTesting(companyId = "company-cache", taskId = "task-cache")
        service.runtimeCacheSizesForTesting().values.any { it > 0 } shouldBe true

        service.shutdown()

        service.runtimeCacheSizesForTesting().values.forEach { it shouldBe 0 }
    }
})
