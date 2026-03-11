package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path

class DesktopAppServiceTest : FunSpec({
    test("openLocalRepository stores the origin remote for an existing checkout") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val nestedPath = repoRoot.resolve("nested").also { Files.createDirectories(it) }
        val remoteUrl = "https://github.com/heodongun/cotor.git"
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = remoteUrl,
                defaultBranch = "master"
            ),
            appHome = Files.createTempDirectory("cotor-home")
        )

        val repository = service.openLocalRepository(nestedPath.toString())

        repository.localPath shouldBe repoRoot.toString()
        repository.remoteUrl shouldBe remoteUrl
        repository.defaultBranch shouldBe "master"
    }

    test("openLocalRepository backfills missing remote metadata for an existing repository record") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val appHome = Files.createTempDirectory("cotor-home")
        val stateStore = DesktopStateStore { appHome }
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = "repo-1",
                        name = "cotor",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        remoteUrl = null,
                        defaultBranch = "stale-branch",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            )
        )

        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val repository = service.openLocalRepository(repoRoot.toString())
        val persisted = stateStore.load().repositories.single()

        repository.id shouldBe "repo-1"
        repository.remoteUrl shouldBe "https://github.com/heodongun/cotor.git"
        repository.defaultBranch shouldBe "master"
        persisted shouldBe repository
    }

    test("cloneRepository reuses an already connected remote instead of cloning again") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val appHome = Files.createTempDirectory("cotor-home")
        val stateStore = DesktopStateStore { appHome }
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = "repo-1",
                        name = "cotor",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.CLONED,
                        remoteUrl = "https://github.com/heodongun/cotor.git",
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            )
        )

        val processManager = FakeGitProcessManager(
            repoRoot = repoRoot,
            remoteUrl = "https://github.com/heodongun/cotor.git",
            defaultBranch = "master"
        )
        val service = testService(
            processManager = processManager,
            stateStore = stateStore
        )

        val repository = service.cloneRepository("https://github.com/heodongun/cotor.git")

        repository.id shouldBe "repo-1"
        processManager.commands.shouldBeEmpty()
        processManager.commands.firstOrNull { it.command.getOrNull(1) == "clone" }.shouldBeNull()
    }
})

private fun testService(
    processManager: ProcessManager,
    appHome: Path? = null,
    stateStore: DesktopStateStore? = null
): DesktopAppService {
    val store = stateStore ?: DesktopStateStore { appHome ?: Files.createTempDirectory("cotor-home") }
    val gitWorkspaceService = GitWorkspaceService(
        processManager = processManager,
        stateStore = store,
        logger = mockk(relaxed = true)
    )
    return DesktopAppService(
        stateStore = store,
        gitWorkspaceService = gitWorkspaceService,
        configRepository = mockk<ConfigRepository>(relaxed = true),
        agentExecutor = mockk<AgentExecutor>(relaxed = true)
    )
}

private class FakeGitProcessManager(
    private val repoRoot: Path,
    private val remoteUrl: String?,
    private val defaultBranch: String
) : ProcessManager {
    data class CommandCall(val command: List<String>, val workingDirectory: Path?)

    val commands = mutableListOf<CommandCall>()

    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?
    ): ProcessResult {
        commands += CommandCall(command = command, workingDirectory = workingDirectory)
        val gitArgs = command.drop(1)
        return when (gitArgs) {
            listOf("rev-parse", "--show-toplevel") -> success(repoRoot.toString())
            listOf("symbolic-ref", "refs/remotes/origin/HEAD") -> success("refs/remotes/origin/$defaultBranch")
            listOf("config", "--get", "remote.origin.url") -> {
                if (remoteUrl == null) {
                    failure("missing remote")
                } else {
                    success(remoteUrl)
                }
            }
            else -> error("Unexpected git command: ${command.joinToString(" ")}")
        }
    }

    private fun success(stdout: String): ProcessResult = ProcessResult(
        exitCode = 0,
        stdout = "$stdout\n",
        stderr = "",
        isSuccess = true
    )

    private fun failure(stderr: String): ProcessResult = ProcessResult(
        exitCode = 1,
        stdout = "",
        stderr = stderr,
        isSuccess = false
    )
}
