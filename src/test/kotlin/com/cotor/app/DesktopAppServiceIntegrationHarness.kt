package com.cotor.app

import com.cotor.data.config.FileConfigRepository
import com.cotor.data.config.JsonParser
import com.cotor.data.config.YamlParser
import com.cotor.data.process.CoroutineProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.knowledge.KnowledgeService
import com.cotor.knowledge.KnowledgeStore
import com.cotor.model.AgentExecutionMetadata
import com.cotor.policy.PolicyEngine
import com.cotor.policy.PolicyStore
import com.cotor.policy.RiskApprovalInterceptor
import com.cotor.provenance.ProvenanceService
import com.cotor.provenance.ProvenanceStore
import com.cotor.providers.github.GitHubControlPlaneService
import com.cotor.providers.github.GitHubControlPlaneStore
import com.cotor.runtime.actions.ActionExecutionService
import com.cotor.runtime.actions.ActionStore
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.runtime.durable.DurableRuntimeStore
import com.cotor.verification.VerificationBundleService
import com.cotor.verification.VerificationStore
import io.mockk.coEvery
import io.mockk.mockk
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

data class DesktopAppServiceIntegrationHarness(
    val appHome: Path,
    val repositoryRoot: Path,
    val stateStore: DesktopStateStore,
    val gitWorkspaceService: GitWorkspaceService,
    val agentExecutor: AgentExecutor,
    val service: DesktopAppService,
    val durableRuntimeService: DurableRuntimeService
)

fun createDesktopAppServiceIntegrationHarness(
    agentResultFactory: (AgentExecutionMetadata?) -> com.cotor.model.AgentResult,
    commandAvailability: (String) -> Boolean = { true },
    gitWorkspaceServiceTransform: (GitWorkspaceService) -> GitWorkspaceService = { it },
    appHome: Path? = null,
    repositoryRoot: Path? = null
): DesktopAppServiceIntegrationHarness {
    val logger = LoggerFactory.getLogger("DesktopAppServiceIntegrationHarness")
    val resolvedAppHome = appHome ?: Files.createTempDirectory("cotor-integration-home")
    val resolvedRepositoryRoot = repositoryRoot ?: Files.createTempDirectory("cotor-integration-repo")
    val stateStore = DesktopStateStore { resolvedAppHome }
    val processManager = CoroutineProcessManager(logger)
    val checkpointManager = com.cotor.checkpoint.CheckpointManager(resolvedAppHome.resolve("checkpoints").toString())
    val durableRuntimeService = DurableRuntimeService(
        checkpointManager = checkpointManager,
        runtimeStore = DurableRuntimeStore(resolvedAppHome.resolve("runtime"))
    )
    val actionStore = ActionStore { resolvedAppHome }
    val provenanceService = ProvenanceService(ProvenanceStore { resolvedAppHome })
    val knowledgeService = KnowledgeService(KnowledgeStore { resolvedAppHome })
    val policyEngine = PolicyEngine(PolicyStore { resolvedAppHome })
    val actionExecutionService = ActionExecutionService(
        actionStore = actionStore,
        durableRuntimeService = durableRuntimeService,
        provenanceService = provenanceService,
        interceptors = listOf(policyEngine, RiskApprovalInterceptor()),
        logger = logger
    )
    val baseGitWorkspaceService = GitWorkspaceService(
        processManager = processManager,
        stateStore = stateStore,
        logger = logger,
        durableRuntimeService = durableRuntimeService,
        actionExecutionService = actionExecutionService
    )
    val gitWorkspaceService = gitWorkspaceServiceTransform(baseGitWorkspaceService)
    val agentExecutor = mockk<AgentExecutor>()
    coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers { agentResultFactory(thirdArg()) }
    coEvery { agentExecutor.executeWithRetry(any(), any(), any(), any()) } answers { agentResultFactory(thirdArg()) }
    val verificationBundleService = VerificationBundleService(
        provenanceService = provenanceService,
        knowledgeService = knowledgeService,
        store = VerificationStore { resolvedAppHome }
    )
    val gitHubControlPlaneService = GitHubControlPlaneService(
        store = GitHubControlPlaneStore { resolvedAppHome },
        provenanceService = provenanceService,
        knowledgeService = knowledgeService
    )
    val service = DesktopAppService(
        stateStore = stateStore,
        gitWorkspaceService = gitWorkspaceService,
        configRepository = FileConfigRepository(YamlParser(), JsonParser()),
        agentExecutor = agentExecutor,
        runtimeBindingService = com.cotor.app.runtime.CompanyRuntimeBindingService(
            durableRuntimeService = durableRuntimeService,
            actionStore = actionStore,
            policyEngine = policyEngine,
            gitHubControlPlaneService = gitHubControlPlaneService
        ),
        policyEngine = policyEngine,
        provenanceService = provenanceService,
        gitHubControlPlaneService = gitHubControlPlaneService,
        knowledgeService = knowledgeService,
        durableRuntimeService = durableRuntimeService,
        verificationBundleService = verificationBundleService,
        commandAvailability = commandAvailability
    )

    return DesktopAppServiceIntegrationHarness(
        appHome = resolvedAppHome,
        repositoryRoot = resolvedRepositoryRoot,
        stateStore = stateStore,
        gitWorkspaceService = gitWorkspaceService,
        agentExecutor = agentExecutor,
        service = service,
        durableRuntimeService = durableRuntimeService
    )
}
