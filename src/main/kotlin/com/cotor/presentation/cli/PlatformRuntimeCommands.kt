package com.cotor.presentation.cli

import com.cotor.app.AppServer
import com.cotor.app.DesktopAppService
import com.cotor.policy.PolicyDocument
import com.cotor.policy.PolicyEngine
import com.cotor.policy.PolicyStore
import com.cotor.runtime.actions.ActionKind
import com.cotor.runtime.actions.ActionRequest
import com.cotor.runtime.actions.ActionScope
import com.cotor.runtime.actions.ActionSubject
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path

private val platformJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

class PolicyCommand : CliktCommand(name = "policy", help = "Validate and simulate policy decisions.") {
    init {
        subcommands(PolicyValidateCommand(), PolicySimulateCommand())
    }

    override fun run() = Unit
}

private class PolicyValidateCommand : CliktCommand(name = "validate", help = "Validate one policy document.") {
    private val path by argument("path").path(mustExist = true)
    private val store = PolicyStore()

    override fun run() {
        val document = store.loadDocument(path)
        echo(platformJson.encodeToString(PolicyDocument.serializer(), document))
    }
}

private class PolicySimulateCommand : CliktCommand(name = "simulate", help = "Simulate one action against configured policies."), KoinComponent {
    private val policyEngine: PolicyEngine by inject()
    private val companyId by option("--company")
    private val issueId by option("--issue")
    private val agentName by option("--agent")
    private val action by option("--action").required()
    private val path by option("--path")
    private val networkTarget by option("--network-target")
    private val command by option("--command")

    override fun run() {
        val kind = ActionKind.fromWireValue(action)
            ?: error("Unsupported action kind: $action")
        val decision = policyEngine.evaluate(
            ActionRequest(
                kind = kind,
                label = "simulate:$action",
                scope = when {
                    issueId != null -> ActionScope.ISSUE
                    companyId != null -> ActionScope.COMPANY
                    else -> ActionScope.GLOBAL
                },
                subject = ActionSubject(
                    companyId = companyId,
                    issueId = issueId,
                    agentName = agentName
                ),
                command = command?.let { listOf(it) }.orEmpty(),
                path = path,
                networkTarget = networkTarget
            )
        )
        echo(platformJson.encodeToString(decision))
    }
}

class EvidenceCommand : CliktCommand(name = "evidence", help = "Inspect provenance bundles.") {
    init {
        subcommands(EvidenceRunCommand(), EvidenceFileCommand())
    }

    override fun run() = Unit
}

private class EvidenceRunCommand : CliktCommand(name = "run"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val runId by argument("runId")

    override fun run() = runBlocking {
        echo(platformJson.encodeToString(desktopService.evidenceForRun(runId)))
    }
}

private class EvidenceFileCommand : CliktCommand(name = "file"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val path by argument("path")

    override fun run() = runBlocking {
        echo(platformJson.encodeToString(desktopService.evidenceForFile(path)))
    }
}

class GitHubProviderCommand : CliktCommand(name = "github", help = "Inspect GitHub provider control plane state.") {
    init {
        subcommands(GitHubSyncCommand(), GitHubInspectPrCommand(), GitHubListPrCommand(), GitHubEventsCommand())
    }

    override fun run() = Unit
}

private class GitHubSyncCommand : CliktCommand(name = "sync"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val companyId by option("--company").required()

    override fun run() = runBlocking {
        echo(platformJson.encodeToString(desktopService.syncGitHubProvider(companyId)))
    }
}

private class GitHubInspectPrCommand : CliktCommand(name = "inspect-pr"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val pullRequestNumber by option("--pr").int().required()

    override fun run() = runBlocking {
        val snapshot = desktopService.inspectGitHubPullRequest(pullRequestNumber)
            ?: error("Pull request not found: $pullRequestNumber")
        echo(platformJson.encodeToString(snapshot))
    }
}

private class GitHubListPrCommand : CliktCommand(name = "list"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val companyId by option("--company")

    override fun run() = runBlocking {
        echo(platformJson.encodeToString(desktopService.listGitHubPullRequests(companyId)))
    }
}

private class GitHubEventsCommand : CliktCommand(name = "events"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val companyId by option("--company")

    override fun run() = runBlocking {
        echo(platformJson.encodeToString(desktopService.listGitHubEvents(companyId)))
    }
}

class KnowledgeCommand : CliktCommand(name = "knowledge", help = "Inspect structured knowledge records.") {
    init {
        subcommands(KnowledgeInspectCommand())
    }

    override fun run() = Unit
}

private class KnowledgeInspectCommand : CliktCommand(name = "inspect"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val issueId by option("--issue").required()

    override fun run() = runBlocking {
        echo(platformJson.encodeToString(desktopService.issueKnowledge(issueId)))
    }
}

class VerificationCommand : CliktCommand(name = "verification", help = "Inspect verification bundles for workflow issues.") {
    init {
        subcommands(VerificationInspectCommand())
    }

    override fun run() = Unit
}

private class VerificationInspectCommand : CliktCommand(name = "inspect"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val issueId by option("--issue").required()

    override fun run() = runBlocking {
        echo(platformJson.encodeToString(desktopService.verificationBundle(issueId)))
    }
}

class RuntimeProjectionCommand : CliktCommand(name = "runtime", help = "Inspect projected runtime state for workflow issues.") {
    init {
        subcommands(RuntimeInspectCommand())
    }

    override fun run() = Unit
}

private class RuntimeInspectCommand : CliktCommand(name = "inspect"), KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val issueId by option("--issue").required()

    override fun run() = runBlocking {
        echo(platformJson.encodeToString(desktopService.issueRuntimeProjection(issueId)))
    }
}

class McpCommand : CliktCommand(name = "mcp", help = "Serve read-only MCP runtime endpoints over the app-server.") {
    init {
        subcommands(McpServeCommand())
    }

    override fun run() = Unit
}

private class McpServeCommand : CliktCommand(name = "serve") {
    private val readonly by option("--readonly").flag(default = true)
    private val port by option("--port").int().default(8787)
    private val host by option("--host").default("127.0.0.1")
    private val token by option("--token")

    override fun run() {
        if (!readonly) {
            error("Only read-only MCP exposure is supported in this build.")
        }
        AppServer().start(port = port, host = host, wait = true, token = token)
    }
}
