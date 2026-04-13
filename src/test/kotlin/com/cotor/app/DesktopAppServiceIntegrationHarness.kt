package com.cotor.app

import com.cotor.data.config.FileConfigRepository
import com.cotor.data.config.JsonParser
import com.cotor.data.config.YamlParser
import com.cotor.data.process.CoroutineProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.knowledge.KnowledgeService
import com.cotor.knowledge.KnowledgeStore
import com.cotor.policy.PolicyEngine
import com.cotor.policy.PolicyStore
import com.cotor.policy.RiskApprovalInterceptor
import com.cotor.providers.github.GitHubControlPlaneService
import com.cotor.providers.github.GitHubControlPlaneStore
import com.cotor.provenance.ProvenanceService
import com.cotor.provenance.ProvenanceStore
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
    agentResultFactory: () -> com.cotor.model.AgentResult,
    commandAvailability: (String) -> Boolean = { true }
): DesktopAppServiceIntegrationHarness {
    val logger = LoggerFactory.getLogger("DesktopAppServiceIntegrationHarness")
    val appHome = Files.createTempDirectory("cotor-integration-home")
    val repositoryRoot = Files.createTempDirectory("cotor-integration-repo")
    val stateStore = DesktopStateStore { appHome }
    val processManager = CoroutineProcessManager(logger)
    val checkpointManager = com.cotor.checkpoint.CheckpointManager(appHome.resolve("checkpoints").toString())
    val durableRuntimeService = DurableRuntimeService(
        checkpointManager = checkpointManager,
        runtimeStore = DurableRuntimeStore(appHome.resolve("runtime"))
    )
    val actionStore = ActionStore { appHome }
    val provenanceService = ProvenanceService(ProvenanceStore { appHome })
    val knowledgeService = KnowledgeService(KnowledgeStore { appHome })
    val policyEngine = PolicyEngine(PolicyStore { appHome })
    val actionExecutionService = ActionExecutionService(
        actionStore = actionStore,
        durableRuntimeService = durableRuntimeService,
        provenanceService = provenanceService,
        interceptors = listOf(policyEngine, RiskApprovalInterceptor()),
        logger = logger
    )
    val gitWorkspaceService = GitWorkspaceService(
        processManager = processManager,
        stateStore = stateStore,
        logger = logger,
        durableRuntimeService = durableRuntimeService,
        actionExecutionService = actionExecutionService
    )
    val agentExecutor = mockk<AgentExecutor>()
    coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers { agentResultFactory() }
    coEvery { agentExecutor.executeWithRetry(any(), any(), any(), any()) } answers { agentResultFactory() }
    val verificationBundleService = VerificationBundleService(
        provenanceService = provenanceService,
        knowledgeService = knowledgeService,
        store = VerificationStore { appHome }
    )
    val gitHubControlPlaneService = GitHubControlPlaneService(
        store = GitHubControlPlaneStore { appHome },
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
        appHome = appHome,
        repositoryRoot = repositoryRoot,
        stateStore = stateStore,
        gitWorkspaceService = gitWorkspaceService,
        agentExecutor = agentExecutor,
        service = service,
        durableRuntimeService = durableRuntimeService
    )
}
