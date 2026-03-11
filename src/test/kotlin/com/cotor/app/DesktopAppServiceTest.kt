package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

class DesktopAppServiceTest : FunSpec({
    fun service(stateStore: DesktopStateStore, gitWorkspaceService: GitWorkspaceService): DesktopAppService {
        val configRepository = mockk<ConfigRepository>()
        val agentExecutor = mockk<AgentExecutor>()
        return DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = configRepository,
            agentExecutor = agentExecutor
        )
    }

    test("openLocalRepository persists remote URL and default branch") {
        val appHome = Files.createTempDirectory("desktop-state-open-local")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val sut = service(stateStore, gitWorkspaceService)
        val repositoryRoot = Path.of("/tmp/cotor-real-repo")

        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repositoryRoot
        coEvery { gitWorkspaceService.detectRemoteUrl(repositoryRoot) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.detectDefaultBranch(repositoryRoot) } returns "main"

        val repository = runBlocking { sut.openLocalRepository("/tmp/cotor-real-repo") }

        repository.sourceKind shouldBe RepositorySourceKind.LOCAL
        repository.localPath shouldBe repositoryRoot.toString()
        repository.remoteUrl shouldBe "https://github.com/heodongun/cotor.git"
        repository.defaultBranch shouldBe "main"

        val persisted = runBlocking { stateStore.load() }.repositories.single()
        persisted.remoteUrl shouldBe "https://github.com/heodongun/cotor.git"
        persisted.defaultBranch shouldBe "main"
    }

    test("openLocalRepository refreshes metadata for an existing repository") {
        val appHome = Files.createTempDirectory("desktop-state-refresh-local")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val sut = service(stateStore, gitWorkspaceService)
        val repositoryRoot = Path.of("/tmp/cotor-refresh-repo")
        val originalUpdatedAt = 1000L
        runBlocking {
            stateStore.save(
                DesktopAppState(
                    repositories = listOf(
                        ManagedRepository(
                            id = "repo-1",
                            name = "cotor-refresh-repo",
                            localPath = repositoryRoot.toString(),
                            sourceKind = RepositorySourceKind.LOCAL,
                            remoteUrl = null,
                            defaultBranch = "master",
                            createdAt = 500L,
                            updatedAt = originalUpdatedAt
                        )
                    )
                )
            )
        }

        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repositoryRoot
        coEvery { gitWorkspaceService.detectRemoteUrl(repositoryRoot) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.detectDefaultBranch(repositoryRoot) } returns "main"

        val refreshed = runBlocking { sut.openLocalRepository("/tmp/cotor-refresh-repo") }

        refreshed.id shouldBe "repo-1"
        refreshed.remoteUrl shouldBe "https://github.com/heodongun/cotor.git"
        refreshed.defaultBranch shouldBe "main"
        refreshed.updatedAt shouldBeGreaterThan originalUpdatedAt

        val persisted = runBlocking { stateStore.load() }.repositories.single()
        persisted.remoteUrl shouldBe "https://github.com/heodongun/cotor.git"
        persisted.defaultBranch shouldBe "main"
        persisted.updatedAt shouldBe refreshed.updatedAt
    }

    test("cloneRepository reuses already connected remote instead of cloning") {
        val appHome = Files.createTempDirectory("desktop-state-clone-dedupe")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val sut = service(stateStore, gitWorkspaceService)

        runBlocking {
            stateStore.save(
                DesktopAppState(
                    repositories = listOf(
                        ManagedRepository(
                            id = "repo-1",
                            name = "cotor",
                            localPath = "/tmp/cotor",
                            sourceKind = RepositorySourceKind.CLONED,
                            remoteUrl = "https://github.com/heodongun/cotor.git",
                            defaultBranch = "main",
                            createdAt = 500L,
                            updatedAt = 1000L
                        )
                    )
                )
            )
        }

        val existing = runBlocking { sut.cloneRepository(" https://github.com/heodongun/cotor.git ") }

        existing.id shouldBe "repo-1"
        existing.remoteUrl shouldBe "https://github.com/heodongun/cotor.git"
        existing.updatedAt shouldBeGreaterThan 1000L
        coVerify(exactly = 0) { gitWorkspaceService.cloneRepository(any()) }
    }
})
