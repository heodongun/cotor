package com.cotor.presentation.cli

import com.cotor.app.*
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val companyCliJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

class CompanyCommand : CliktCommand(
    name = "company",
    help = "Manage company workflows from the CLI. See `cotor company --help` for goals, issues, review, runtime, backend, and TUI commands."
) {
    init {
        subcommands(
            CompanyListCommand(),
            CompanyCreateCommand(),
            CompanyShowCommand(),
            CompanyUpdateCommand(),
            CompanyDeleteCommand(),
            CompanyDashboardCommand(),
            CompanyAgentCommand(),
            CompanyGoalCommand(),
            CompanyIssueCommand(),
            CompanyReviewCommand(),
            CompanyRuntimeCommand(),
            CompanyBackendCommand(),
            CompanyLinearCommand(),
            CompanyContextCommand(),
            CompanyMessageCommand(),
            CompanyTopologyCommand(),
            CompanyDecisionsCommand(),
            CompanyIssueGraphCommand(),
            CompanyExecutionLogCommand(),
            CompanyTuiCommand()
        )
    }

    override fun run() = Unit
}

private abstract class CompanyServiceCommand(
    name: String? = null,
    help: String = ""
) : CliktCommand(name = name, help = help), KoinComponent {
    protected val desktopService: DesktopAppService by inject()

    protected fun printJsonElement(element: JsonElement) {
        echo(companyCliJson.encodeToString(JsonElement.serializer(), element))
    }

    protected fun <T> printJson(value: T, serializer: KSerializer<T>) {
        echo(companyCliJson.encodeToString(serializer, value))
    }

    protected fun <T> printJsonList(values: List<T>, serializer: KSerializer<T>) {
        echo(companyCliJson.encodeToString(ListSerializer(serializer), values))
    }

    protected fun jsonAny(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, item) ->
                put(key.toString(), jsonAny(item))
            }
        }
        is Iterable<*> -> buildJsonArray {
            value.forEach { add(jsonAny(it)) }
        }
        else -> JsonPrimitive(value.toString())
    }
}

private class CompanyListCommand : CompanyServiceCommand(name = "list") {
    override fun run() = runBlocking {
        printJsonList(desktopService.listCompanies(), Company.serializer())
    }
}

private class CompanyCreateCommand : CompanyServiceCommand(name = "create") {
    private val name by option("--name").required()
    private val rootPath by option("--root").required()
    private val baseBranch by option("--base-branch")
    private val autonomyEnabled by option("--autonomy-enabled").flag(default = true)
    private val dailyBudgetCents by option("--daily-budget-cents").int()
    private val monthlyBudgetCents by option("--monthly-budget-cents").int()

    override fun run() = runBlocking {
        printJson(
            desktopService.createCompany(
                name = name,
                rootPath = rootPath,
                defaultBaseBranch = baseBranch,
                autonomyEnabled = autonomyEnabled,
                dailyBudgetCents = dailyBudgetCents,
                monthlyBudgetCents = monthlyBudgetCents
            ),
            Company.serializer()
        )
    }
}

private class CompanyShowCommand : CompanyServiceCommand(name = "show") {
    private val companyId by argument("companyId")
    override fun run() = runBlocking {
        val company = desktopService.getCompany(companyId)
            ?: error("Company not found: $companyId")
        printJson(company, Company.serializer())
    }
}

private class CompanyUpdateCommand : CompanyServiceCommand(name = "update") {
    private val companyId by argument("companyId")
    private val name by option("--name")
    private val baseBranch by option("--base-branch")
    private val autonomyEnabled by option("--autonomy-enabled").choice("true", "false")
    private val backendKind by option("--backend-kind").choice("LOCAL_COTOR", "CODEX_APP_SERVER")
    private val dailyBudgetCents by option("--daily-budget-cents").int()
    private val monthlyBudgetCents by option("--monthly-budget-cents").int()

    override fun run() = runBlocking {
        printJson(
            desktopService.updateCompany(
                companyId = companyId,
                name = name,
                defaultBaseBranch = baseBranch,
                autonomyEnabled = autonomyEnabled?.toBooleanStrict(),
                backendKind = backendKind?.let(ExecutionBackendKind::valueOf),
                dailyBudgetCents = dailyBudgetCents,
                monthlyBudgetCents = monthlyBudgetCents
            ),
            Company.serializer()
        )
    }
}

private class CompanyDeleteCommand : CompanyServiceCommand(name = "delete") {
    private val companyId by argument("companyId")
    override fun run() = runBlocking {
        printJson(desktopService.deleteCompany(companyId), Company.serializer())
    }
}

private class CompanyDashboardCommand : CompanyServiceCommand(name = "dashboard") {
    private val companyId by option("--company-id")
    override fun run() = runBlocking {
        printJson(desktopService.companyDashboardReadOnly(companyId), CompanyDashboardResponse.serializer())
    }
}

private class CompanyAgentCommand : CliktCommand(name = "agent", help = "Manage company agents") {
    init {
        subcommands(
            CompanyAgentListCommand(),
            CompanyAgentAddCommand(),
            CompanyAgentUpdateCommand(),
            CompanyAgentBatchUpdateCommand()
        )
    }
    override fun run() = Unit
}

private class CompanyAgentListCommand : CompanyServiceCommand(name = "list") {
    private val companyId by option("--company-id")
    override fun run() = runBlocking {
        printJsonList(desktopService.listCompanyAgentDefinitions(companyId), CompanyAgentDefinition.serializer())
    }
}

private class CompanyAgentAddCommand : CompanyServiceCommand(name = "add") {
    private val companyId by option("--company-id").required()
    private val title by option("--title").required()
    private val agentCli by option("--agent-cli").required()
    private val model by option("--model")
    private val roleSummary by option("--role-summary").required()
    private val specialties by option("--specialty").multiple()
    private val collaborationInstructions by option("--collaboration-instructions")
    private val preferredCollaboratorIds by option("--preferred-collaborator-id").multiple()
    private val memoryNotes by option("--memory-notes")
    private val enabled by option("--enabled").choice("true", "false").default("true")

    override fun run() = runBlocking {
        printJson(
            desktopService.createCompanyAgentDefinition(
                companyId = companyId,
                title = title,
                agentCli = agentCli,
                model = model,
                roleSummary = roleSummary,
                specialties = specialties,
                collaborationInstructions = collaborationInstructions,
                preferredCollaboratorIds = preferredCollaboratorIds,
                memoryNotes = memoryNotes,
                enabled = enabled.toBooleanStrict()
            ),
            CompanyAgentDefinition.serializer()
        )
    }
}

private class CompanyAgentUpdateCommand : CompanyServiceCommand(name = "update") {
    private val companyId by option("--company-id").required()
    private val agentId by option("--agent-id").required()
    private val title by option("--title")
    private val agentCli by option("--agent-cli")
    private val model by option("--model")
    private val roleSummary by option("--role-summary")
    private val specialties by option("--specialty").multiple()
    private val collaborationInstructions by option("--collaboration-instructions")
    private val preferredCollaboratorIds by option("--preferred-collaborator-id").multiple()
    private val memoryNotes by option("--memory-notes")
    private val enabled by option("--enabled").choice("true", "false")
    private val displayOrder by option("--display-order").int()

    override fun run() = runBlocking {
        printJson(
            desktopService.updateCompanyAgentDefinition(
                companyId = companyId,
                agentId = agentId,
                title = title,
                agentCli = agentCli,
                model = model,
                roleSummary = roleSummary,
                specialties = specialties.takeIf { it.isNotEmpty() },
                collaborationInstructions = collaborationInstructions,
                preferredCollaboratorIds = preferredCollaboratorIds.takeIf { it.isNotEmpty() },
                memoryNotes = memoryNotes,
                enabled = enabled?.toBooleanStrict(),
                displayOrder = displayOrder
            ),
            CompanyAgentDefinition.serializer()
        )
    }
}

private class CompanyAgentBatchUpdateCommand : CompanyServiceCommand(name = "batch-update") {
    private val companyId by option("--company-id").required()
    private val agentIds by option("--agent-id").multiple(required = true)
    private val agentCli by option("--agent-cli")
    private val model by option("--model")
    private val specialties by option("--specialty").multiple()
    private val enabled by option("--enabled").choice("true", "false")

    override fun run() = runBlocking {
        printJsonList(
            desktopService.batchUpdateCompanyAgentDefinitions(
                companyId = companyId,
                agentIds = agentIds,
                agentCli = agentCli,
                model = model,
                specialties = specialties.takeIf { it.isNotEmpty() },
                enabled = enabled?.toBooleanStrict()
            ),
            CompanyAgentDefinition.serializer()
        )
    }
}

private class CompanyGoalCommand : CliktCommand(name = "goal", help = "Manage company goals") {
    init {
        subcommands(
            CompanyGoalListCommand(),
            CompanyGoalCreateCommand(),
            CompanyGoalUpdateCommand(),
            CompanyGoalDeleteCommand(),
            CompanyGoalDecomposeCommand(),
            CompanyGoalAutonomyCommand("enable-autonomy", true),
            CompanyGoalAutonomyCommand("disable-autonomy", false)
        )
    }
    override fun run() = Unit
}

private class CompanyGoalListCommand : CompanyServiceCommand(name = "list") {
    private val companyId by option("--company-id")
    override fun run() = runBlocking {
        val goals = desktopService.listGoals().filter { companyId == null || it.companyId == companyId }
        printJsonList(goals, CompanyGoal.serializer())
    }
}

private class CompanyGoalCreateCommand : CompanyServiceCommand(name = "create") {
    private val companyId by option("--company-id").required()
    private val title by option("--title").required()
    private val description by option("--description").required()
    private val successMetrics by option("--success-metric").multiple()
    private val autonomyEnabled by option("--autonomy-enabled").choice("true", "false").default("true")

    override fun run() = runBlocking {
        printJson(
            desktopService.createGoal(
                companyId = companyId,
                title = title,
                description = description,
                successMetrics = successMetrics,
                autonomyEnabled = autonomyEnabled.toBooleanStrict()
            ),
            CompanyGoal.serializer()
        )
    }
}

private class CompanyGoalUpdateCommand : CompanyServiceCommand(name = "update") {
    private val goalId by argument("goalId")
    private val title by option("--title")
    private val description by option("--description")
    private val successMetrics by option("--success-metric").multiple()
    private val autonomyEnabled by option("--autonomy-enabled").choice("true", "false")

    override fun run() = runBlocking {
        printJson(
            desktopService.updateGoal(
                goalId = goalId,
                title = title,
                description = description,
                successMetrics = successMetrics.takeIf { it.isNotEmpty() },
                autonomyEnabled = autonomyEnabled?.toBooleanStrict()
            ),
            CompanyGoal.serializer()
        )
    }
}

private class CompanyGoalDeleteCommand : CompanyServiceCommand(name = "delete") {
    private val goalId by argument("goalId")
    override fun run() = runBlocking {
        printJson(desktopService.deleteGoal(goalId), CompanyGoal.serializer())
    }
}

private class CompanyGoalDecomposeCommand : CompanyServiceCommand(name = "decompose") {
    private val goalId by argument("goalId")
    override fun run() = runBlocking {
        printJsonList(desktopService.decomposeGoal(goalId), CompanyIssue.serializer())
    }
}

private class CompanyGoalAutonomyCommand(
    name: String,
    private val enabled: Boolean
) : CompanyServiceCommand(name = name) {
    private val goalId by argument("goalId")
    override fun run() = runBlocking {
        printJson(desktopService.updateGoalAutonomy(goalId, enabled), CompanyGoal.serializer())
    }
}

private class CompanyIssueCommand : CliktCommand(name = "issue", help = "Manage company issues") {
    init {
        subcommands(
            CompanyIssueListCommand(),
            CompanyIssueCreateCommand(),
            CompanyIssueShowCommand(),
            CompanyIssueDeleteCommand(),
            CompanyIssueDelegateCommand(),
            CompanyIssueRunCommand()
        )
    }
    override fun run() = Unit
}

private class CompanyIssueListCommand : CompanyServiceCommand(name = "list") {
    private val companyId by option("--company-id")
    private val goalId by option("--goal-id")
    override fun run() = runBlocking {
        printJsonList(desktopService.listIssues(goalId = goalId, companyId = companyId), CompanyIssue.serializer())
    }
}

private class CompanyIssueCreateCommand : CompanyServiceCommand(name = "create") {
    private val companyId by option("--company-id").required()
    private val goalId by option("--goal-id").required()
    private val title by option("--title").required()
    private val description by option("--description").required()
    private val priority by option("--priority").int().default(3)
    private val kind by option("--kind").default("manual")

    override fun run() = runBlocking {
        printJson(
            desktopService.createIssue(
                companyId = companyId,
                goalId = goalId,
                title = title,
                description = description,
                priority = priority,
                kind = kind
            ),
            CompanyIssue.serializer()
        )
    }
}

private class CompanyIssueShowCommand : CompanyServiceCommand(name = "show") {
    private val issueId by argument("issueId")
    override fun run() = runBlocking {
        val issue = desktopService.getIssueProjected(issueId)
            ?: error("Issue not found: $issueId")
        printJson(issue, CompanyIssue.serializer())
    }
}

private class CompanyIssueDeleteCommand : CompanyServiceCommand(name = "delete") {
    private val issueId by argument("issueId")
    override fun run() = runBlocking {
        printJson(desktopService.deleteIssue(issueId), CompanyIssue.serializer())
    }
}

private class CompanyIssueDelegateCommand : CompanyServiceCommand(name = "delegate") {
    private val issueId by argument("issueId")
    override fun run() = runBlocking {
        printJson(desktopService.delegateIssue(issueId), CompanyIssue.serializer())
    }
}

private class CompanyIssueRunCommand : CompanyServiceCommand(name = "run") {
    private val issueId by argument("issueId")
    private val async by option(
        "--async",
        help = "Start the issue run and return immediately. By default the CLI waits for a terminal issue state so in-process agent execution is not orphaned."
    ).flag(default = false)
    private val waitTimeoutSeconds by option(
        "--wait-timeout-seconds",
        help = "Maximum seconds to wait for the issue to settle before returning the latest state."
    ).int()

    override fun run() = runBlocking {
        val issue = if (async) {
            desktopService.runIssue(issueId)
        } else {
            val timeoutMs = waitTimeoutSeconds?.let { it.coerceAtLeast(1) * 1_000L }
            if (timeoutMs == null) {
                desktopService.runIssueAndAwaitSettlement(issueId)
            } else {
                desktopService.runIssueAndAwaitSettlement(issueId, timeoutMs)
            }
        }
        printJson(issue, CompanyIssue.serializer())
    }
}

private class CompanyReviewCommand : CliktCommand(name = "review", help = "Manage review queue") {
    init {
        subcommands(CompanyReviewListCommand(), CompanyReviewQaCommand(), CompanyReviewCeoCommand(), CompanyReviewMergeCommand())
    }
    override fun run() = Unit
}

private class CompanyReviewListCommand : CompanyServiceCommand(name = "list") {
    private val companyId by option("--company-id")
    override fun run() = runBlocking {
        printJsonList(desktopService.listReviewQueue(companyId), ReviewQueueItem.serializer())
    }
}

private class CompanyReviewMergeCommand : CompanyServiceCommand(name = "merge") {
    private val itemId by argument("itemId")
    override fun run() = runBlocking {
        printJson(desktopService.mergeReviewQueueItem(itemId), ReviewQueueItem.serializer())
    }
}

private class CompanyReviewQaCommand : CompanyServiceCommand(name = "qa") {
    private val itemId by argument("itemId")
    private val verdict by option("--verdict").choice("PASS", "CHANGES_REQUESTED").required()
    private val feedback by option("--feedback")
    override fun run() = runBlocking {
        printJson(desktopService.submitQaReviewVerdict(itemId, verdict, feedback), ReviewQueueItem.serializer())
    }
}

private class CompanyReviewCeoCommand : CompanyServiceCommand(name = "ceo") {
    private val itemId by argument("itemId")
    private val verdict by option("--verdict").choice("APPROVE", "CHANGES_REQUESTED").required()
    private val feedback by option("--feedback")
    override fun run() = runBlocking {
        printJson(desktopService.submitCeoReviewVerdict(itemId, verdict, feedback), ReviewQueueItem.serializer())
    }
}

private class CompanyRuntimeCommand : CliktCommand(name = "runtime", help = "Control company runtime") {
    init {
        subcommands(CompanyRuntimeStatusCommand(), CompanyRuntimeStartCommand(), CompanyRuntimeStopCommand())
    }
    override fun run() = Unit
}

private class CompanyRuntimeStatusCommand : CompanyServiceCommand(name = "status") {
    private val companyId by option("--company-id")
    override fun run() = runBlocking {
        printJson(desktopService.runtimeStatus(companyId), CompanyRuntimeSnapshot.serializer())
    }
}

private class CompanyRuntimeStartCommand : CompanyServiceCommand(name = "start") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJson(desktopService.startCompanyRuntime(companyId), CompanyRuntimeSnapshot.serializer())
    }
}

private class CompanyRuntimeStopCommand : CompanyServiceCommand(name = "stop") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJson(desktopService.stopCompanyRuntime(companyId), CompanyRuntimeSnapshot.serializer())
    }
}

private class CompanyBackendCommand : CliktCommand(name = "backend", help = "Manage company backends") {
    init {
        subcommands(
            CompanyBackendStatusCommand(),
            CompanyBackendUpdateCommand(),
            CompanyBackendStartCommand(),
            CompanyBackendStopCommand(),
            CompanyBackendRestartCommand(),
            CompanyBackendTestCommand()
        )
    }
    override fun run() = Unit
}

private class CompanyBackendStatusCommand : CompanyServiceCommand(name = "status") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJson(desktopService.companyBackendStatus(companyId), ExecutionBackendStatus.serializer())
    }
}

private class CompanyBackendUpdateCommand : CompanyServiceCommand(name = "update") {
    private val companyId by option("--company-id").required()
    private val backendKind by option("--backend-kind").choice("LOCAL_COTOR", "CODEX_APP_SERVER").required()
    private val launchMode by option("--launch-mode").choice("MANAGED", "ATTACHED")
    private val command by option("--command")
    private val args by option("--arg").multiple()
    private val workingDirectory by option("--working-directory")
    private val port by option("--port").int()
    private val startupTimeoutSeconds by option("--startup-timeout-seconds").int()
    private val baseUrl by option("--base-url")
    private val authMode by option("--auth-mode")
    private val token by option("--token")
    private val timeoutSeconds by option("--timeout-seconds").int()
    private val useGlobalDefault by option("--use-global-default").flag(default = false)

    override fun run() = runBlocking {
        printJson(
            desktopService.updateCompanyBackend(
                companyId = companyId,
                backendKind = ExecutionBackendKind.valueOf(backendKind),
                launchMode = launchMode?.let(BackendLaunchMode::valueOf),
                command = command,
                args = args.takeIf { it.isNotEmpty() },
                workingDirectory = workingDirectory,
                port = port,
                startupTimeoutSeconds = startupTimeoutSeconds,
                baseUrl = baseUrl,
                authMode = authMode,
                token = token,
                timeoutSeconds = timeoutSeconds,
                useGlobalDefault = useGlobalDefault
            ),
            Company.serializer()
        )
    }
}

private class CompanyBackendStartCommand : CompanyServiceCommand(name = "start") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJson(desktopService.startCompanyBackend(companyId), ExecutionBackendStatus.serializer())
    }
}

private class CompanyBackendStopCommand : CompanyServiceCommand(name = "stop") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJson(desktopService.stopCompanyBackend(companyId), ExecutionBackendStatus.serializer())
    }
}

private class CompanyBackendRestartCommand : CompanyServiceCommand(name = "restart") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJson(desktopService.restartCompanyBackend(companyId), ExecutionBackendStatus.serializer())
    }
}

private class CompanyBackendTestCommand : CompanyServiceCommand(name = "test") {
    private val backendKind by option("--backend-kind").choice("LOCAL_COTOR", "CODEX_APP_SERVER").required()
    private val launchMode by option("--launch-mode").choice("MANAGED", "ATTACHED")
    private val command by option("--command")
    private val args by option("--arg").multiple()
    private val workingDirectory by option("--working-directory")
    private val port by option("--port").int()
    private val startupTimeoutSeconds by option("--startup-timeout-seconds").int()
    private val baseUrl by option("--base-url")
    private val authMode by option("--auth-mode")
    private val token by option("--token")
    private val timeoutSeconds by option("--timeout-seconds").int()

    override fun run() = runBlocking {
        printJson(
            desktopService.testBackend(
                kind = ExecutionBackendKind.valueOf(backendKind),
                launchMode = launchMode?.let(BackendLaunchMode::valueOf),
                command = command,
                args = args.takeIf { it.isNotEmpty() },
                workingDirectory = workingDirectory,
                port = port,
                startupTimeoutSeconds = startupTimeoutSeconds,
                baseUrl = baseUrl,
                authMode = authMode,
                token = token,
                timeoutSeconds = timeoutSeconds
            ),
            ExecutionBackendStatus.serializer()
        )
    }
}

private class CompanyLinearCommand : CliktCommand(name = "linear", help = "Manage company Linear sync") {
    init {
        subcommands(CompanyLinearConfigCommand(), CompanyLinearResyncCommand())
    }
    override fun run() = Unit
}

private class CompanyLinearConfigCommand : CompanyServiceCommand(name = "config") {
    private val companyId by option("--company-id").required()
    private val enabled by option("--enabled").choice("true", "false").required()
    private val endpoint by option("--endpoint")
    private val apiToken by option("--api-token")
    private val teamId by option("--team-id")
    private val projectId by option("--project-id")
    private val useGlobalDefault by option("--use-global-default").flag(default = false)

    override fun run() = runBlocking {
        printJson(
            desktopService.updateCompanyLinear(
                companyId = companyId,
                enabled = enabled.toBooleanStrict(),
                endpoint = endpoint,
                apiToken = apiToken,
                teamId = teamId,
                projectId = projectId,
                useGlobalDefault = useGlobalDefault
            ),
            Company.serializer()
        )
    }
}

private class CompanyLinearResyncCommand : CompanyServiceCommand(name = "resync") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJson(desktopService.syncCompanyLinear(companyId), LinearSyncResponse.serializer())
    }
}

private class CompanyContextCommand : CliktCommand(name = "context", help = "Manage company context entries") {
    init {
        subcommands(CompanyContextListCommand(), CompanyContextAddCommand(), CompanyContextDeleteCommand())
    }
    override fun run() = Unit
}

private class CompanyContextListCommand : CompanyServiceCommand(name = "list") {
    private val companyId by option("--company-id").required()
    private val goalId by option("--goal-id")
    private val issueId by option("--issue-id")
    override fun run() = runBlocking {
        printJsonList(desktopService.listContextEntries(companyId, goalId, issueId), AgentContextEntry.serializer())
    }
}

private class CompanyContextAddCommand : CompanyServiceCommand(name = "add") {
    private val companyId by option("--company-id").required()
    private val agentName by option("--agent-name").required()
    private val kind by option("--kind").required()
    private val title by option("--title").required()
    private val content by option("--content").required()
    private val issueId by option("--issue-id")
    private val goalId by option("--goal-id")
    private val visibility by option("--visibility").default("company")
    override fun run() = runBlocking {
        printJson(
            desktopService.addContextEntry(companyId, agentName, kind, title, content, issueId, goalId, visibility),
            AgentContextEntry.serializer()
        )
    }
}

private class CompanyContextDeleteCommand : CompanyServiceCommand(name = "delete") {
    private val entryId by argument("entryId")
    override fun run() = runBlocking {
        desktopService.deleteContextEntry(entryId)
        printJsonElement(buildJsonObject { put("deleted", JsonPrimitive(entryId)) })
    }
}

private class CompanyMessageCommand : CliktCommand(name = "message", help = "Manage company agent messages") {
    init {
        subcommands(CompanyMessageListCommand(), CompanyMessageSendCommand())
    }
    override fun run() = Unit
}

private class CompanyMessageListCommand : CompanyServiceCommand(name = "list") {
    private val companyId by option("--company-id").required()
    private val goalId by option("--goal-id")
    private val issueId by option("--issue-id")
    override fun run() = runBlocking {
        printJsonList(desktopService.listMessages(companyId, goalId, issueId), AgentMessage.serializer())
    }
}

private class CompanyMessageSendCommand : CompanyServiceCommand(name = "send") {
    private val companyId by option("--company-id").required()
    private val fromAgentName by option("--from-agent-name").required()
    private val toAgentName by option("--to-agent-name")
    private val kind by option("--kind").required()
    private val subject by option("--subject").required()
    private val body by option("--body").required()
    private val issueId by option("--issue-id")
    private val goalId by option("--goal-id")
    override fun run() = runBlocking {
        printJson(
            desktopService.sendMessage(companyId, fromAgentName, toAgentName, kind, subject, body, issueId, goalId),
            AgentMessage.serializer()
        )
    }
}

private class CompanyTopologyCommand : CompanyServiceCommand(name = "topology") {
    private val companyId by option("--company-id")
    override fun run() = runBlocking {
        printJsonList(desktopService.listWorkflowTopologies(companyId), WorkflowTopologySnapshot.serializer())
    }
}

private class CompanyDecisionsCommand : CompanyServiceCommand(name = "decisions") {
    private val companyId by option("--company-id")
    override fun run() = runBlocking {
        printJsonList(desktopService.listGoalDecisions(companyId), GoalOrchestrationDecision.serializer())
    }
}

private class CompanyIssueGraphCommand : CompanyServiceCommand(name = "issue-graph") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJsonElement(jsonAny(desktopService.issueGraph(companyId)))
    }
}

private class CompanyExecutionLogCommand : CompanyServiceCommand(name = "execution-log") {
    private val companyId by option("--company-id").required()
    override fun run() = runBlocking {
        printJsonElement(jsonAny(desktopService.executionLog(companyId)))
    }
}

private class CompanyTuiCommand : CliktCommand(
    name = "tui",
    help = "Run a company-specific terminal surface without changing the default interactive TUI"
) {
    override fun run() {
        echo("Cotor Company TUI")
        echo("Type company subcommands without the leading `company`.")
        echo("Examples: list | dashboard --company-id <id> | runtime start --company-id <id> | issue list --company-id <id>")
        echo("Type `help` to print this message again, or `exit` to quit.")

        while (true) {
            print("company> ")
            val line = readlnOrNull()?.trim() ?: break
            if (line.isBlank()) continue
            when (line.lowercase()) {
                "exit", "quit" -> return
                "help", "?" -> {
                    echo("Examples: list | show <companyId> | goal list --company-id <id> | issue run <issueId> | review merge <itemId>")
                    continue
                }
            }

            val args = line.split(Regex("\\s+")).filter { it.isNotBlank() }.toTypedArray()
            try {
                CompanyCommand().main(args)
            } catch (_: NoSuchOption) {
                echo("Unknown company TUI command: $line")
            } catch (error: Exception) {
                echo(error.message ?: error::class.simpleName.orEmpty(), err = true)
            }
        }
    }
}
