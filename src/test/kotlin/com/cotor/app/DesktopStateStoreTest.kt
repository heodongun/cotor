package com.cotor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlin.io.path.readText

class DesktopStateStoreTest : FunSpec({
    test("load recovers a state file with one extra trailing brace") {
        val appHome = Files.createTempDirectory("desktop-state-store-home")
        val store = DesktopStateStore { appHome }
        val validState = DesktopAppState(
            companies = listOf(
                Company(
                    id = "company-1",
                    name = "Recovered Company",
                    rootPath = "/tmp/recovered-company",
                    repositoryId = "repo-1",
                    defaultBaseBranch = "master",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )
        val payload = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }.encodeToString(DesktopAppState.serializer(), validState) + "\n}"
        Files.createDirectories(appHome)
        Files.writeString(appHome.resolve("state.json"), payload)

        val recovered = store.load()

        recovered.companies.map { it.name } shouldBe listOf("Recovered Company")
        Files.readString(appHome.resolve("state.json")).trimEnd().endsWith("}}") shouldBe false
    }

    test("load restores the last good backup when the primary state file is corrupted") {
        val appHome = Files.createTempDirectory("desktop-state-store-backup-home")
        val store = DesktopStateStore { appHome }
        val validState = DesktopAppState(
            companies = listOf(
                Company(
                    id = "company-1",
                    name = "Recovered From Backup",
                    rootPath = "/tmp/recovered-company",
                    repositoryId = "repo-1",
                    defaultBaseBranch = "master",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )

        store.save(validState)
        Files.writeString(
            appHome.resolve("state.json"),
            "{\n  \"companies\": [\n    {\n      \"id\": \"broken\"\n"
        )

        val recovered = store.load()

        recovered.companies.map { it.name } shouldBe listOf("Recovered From Backup")
        Files.readString(appHome.resolve("state.json.bak")).contains("Recovered From Backup") shouldBe true
    }

    test("load restores the backup when the primary state file is corrupted in the middle") {
        val appHome = Files.createTempDirectory("desktop-state-store-mid-corruption-home")
        val store = DesktopStateStore { appHome }
        val validState = DesktopAppState(
            companies = listOf(
                Company(
                    id = "company-1",
                    name = "Recovered Mid-File Backup",
                    rootPath = "/tmp/recovered-company",
                    repositoryId = "repo-1",
                    defaultBaseBranch = "master",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )

        store.save(validState)
        val validPayload = appHome.resolve("state.json").readText()
        val insertionPoint = validPayload.indexOf("\"name\":")
        val corruptedPayload = buildString {
            append(validPayload.substring(0, insertionPoint))
            append("{\n  ssage\": \"broken\"\n")
            append(validPayload.substring(insertionPoint))
        }
        Files.writeString(appHome.resolve("state.json"), corruptedPayload)

        val recovered = store.load()

        recovered.companies.map { it.name } shouldBe listOf("Recovered Mid-File Backup")
        Files.readString(appHome.resolve("state.json.bak")).contains("Recovered Mid-File Backup") shouldBe true
    }

    test("load recovers companies and goals even when one persisted task entry is invalid") {
        val appHome = Files.createTempDirectory("desktop-state-store-lenient-home")
        val store = DesktopStateStore { appHome }
        val validState = DesktopAppState(
            companies = listOf(
                Company(
                    id = "company-1",
                    name = "Lenient Company",
                    rootPath = "/tmp/lenient-company",
                    repositoryId = "repo-1",
                    defaultBaseBranch = "master",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            ),
            goals = listOf(
                CompanyGoal(
                    id = "goal-1",
                    companyId = "company-1",
                    projectContextId = "project-1",
                    title = "Keep working",
                    description = "Continue autonomous work.",
                    status = GoalStatus.ACTIVE,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            ),
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    workspaceId = "workspace-1",
                    title = "Valid task",
                    prompt = "prompt",
                    agents = listOf("codex"),
                    status = DesktopTaskStatus.COMPLETED,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )

        store.save(validState)
        val statePath = appHome.resolve("state.json")
        val corruptedPayload = statePath.readText().replaceFirst(
            "\"status\": \"COMPLETED\"",
            "\"status\": \"NOT_A_REAL_STATUS\""
        )
        Files.writeString(statePath, corruptedPayload)

        val recovered = store.load()

        recovered.companies.map { it.name } shouldBe listOf("Lenient Company")
        recovered.goals.map { it.title } shouldBe listOf("Keep working")
        recovered.tasks shouldBe emptyList()
        Files.readString(appHome.resolve("runtime").resolve("backend").resolve("state-load.log")) shouldContain "Recovered state with lenient decode"
    }

    test("save compacts terminal task prompts and run outputs for faster future loads") {
        val appHome = Files.createTempDirectory("desktop-state-store-compact-home")
        val store = DesktopStateStore { appHome }
        val longPrompt = "prompt-".repeat(800)
        val longOutput = "output-".repeat(800)
        val state = DesktopAppState(
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    workspaceId = "workspace-1",
                    title = "Compacted task",
                    prompt = longPrompt,
                    agents = listOf("codex"),
                    plan = TaskExecutionPlan(
                        goalSummary = "Goal",
                        decompositionSource = "test",
                        assignments = listOf(
                            AgentAssignmentPlan(
                                agentName = "codex",
                                role = "Builder",
                                focus = "execution",
                                assignedPrompt = "assigned-prompt"
                            )
                        )
                    ),
                    status = DesktopTaskStatus.COMPLETED,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            ),
            runs = listOf(
                AgentRun(
                    id = "run-1",
                    taskId = "task-1",
                    workspaceId = "workspace-1",
                    repositoryId = "repo-1",
                    agentName = "codex",
                    branchName = "codex/cotor/test",
                    worktreePath = "/tmp/worktree",
                    status = AgentRunStatus.COMPLETED,
                    output = longOutput,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )

        store.save(state)
        val persisted = appHome.resolve("state.json").readText()

        persisted.shouldContain("[compacted ")
        persisted.shouldNotContain(longPrompt)
        persisted.shouldNotContain(longOutput)
        persisted.shouldNotContain("\"plan\": {")
    }
})
