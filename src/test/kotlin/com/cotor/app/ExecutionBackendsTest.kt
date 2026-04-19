package com.cotor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class ExecutionBackendsTest : FunSpec({
    test("Codex app server backend only advertises capabilities it actually implements") {
        val backend = CodexAppServerBackend()

        backend.capabilities.canStreamEvents shouldBe false
        backend.capabilities.canResumeRuns shouldBe false
        backend.capabilities.canSpawnParallelAgents shouldBe false
        backend.capabilities.canPublishPullRequests shouldBe true
    }

    test("Local Cotor backend keeps richer local-process capabilities") {
        val backend = LocalCotorBackend(mockk())

        backend.capabilities.canStreamEvents shouldBe true
        backend.capabilities.canResumeRuns shouldBe true
        backend.capabilities.canSpawnParallelAgents shouldBe true
        backend.capabilities.canPublishPullRequests shouldBe true
    }
})
