package com.cotor.runtime

import com.cotor.app.DesktopStateStore
import com.cotor.app.GitWorkspaceService
import com.cotor.data.plugin.PluginLoader
import com.cotor.data.process.ProcessManager
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.DefaultAgentExecutor
import com.cotor.domain.orchestrator.DefaultPipelineOrchestrator
import com.cotor.runtime.actions.ActionExecutionService
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.security.SecurityValidator
import com.cotor.stats.StatsManager
import com.cotor.event.EventBus
import com.cotor.validation.output.OutputValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.slf4j.Logger
import java.nio.file.Files

class RuntimeConstructorConsistencyTest : FunSpec({
    test("DefaultAgentExecutor compatibility constructors share the same durable runtime service with action execution") {
        val executor = DefaultAgentExecutor(
            processManager = mockk<ProcessManager>(),
            pluginLoader = mockk<PluginLoader>(),
            securityValidator = mockk<SecurityValidator>(),
            logger = mockk<Logger>(relaxed = true)
        )

        val durable = executor.readPrivate<DurableRuntimeService>("durableRuntimeService")
        val actionService = executor.readPrivate<ActionExecutionService>("actionExecutionService")
        val actionDurable = actionService.readPrivate<DurableRuntimeService>("durableRuntimeService")

        actionDurable shouldBe durable
    }

    test("GitWorkspaceService compatibility constructor shares the same durable runtime service with action execution") {
        val stateStore = DesktopStateStore { Files.createTempDirectory("git-workspace-constructor") }
        val service = GitWorkspaceService(
            processManager = mockk<ProcessManager>(),
            stateStore = stateStore,
            logger = mockk<Logger>(relaxed = true)
        )

        val durable = service.readPrivate<DurableRuntimeService>("durableRuntimeService")
        val actionService = service.readPrivate<ActionExecutionService>("actionExecutionService")
        val actionDurable = actionService.readPrivate<DurableRuntimeService>("durableRuntimeService")

        actionDurable shouldBe durable
    }

    test("DefaultPipelineOrchestrator compatibility constructors no longer force a separate durable runtime service argument") {
        val orchestrator = DefaultPipelineOrchestrator(
            agentExecutor = mockk<com.cotor.domain.executor.AgentExecutor>(),
            resultAggregator = mockk<ResultAggregator>(),
            eventBus = mockk<EventBus>(),
            logger = mockk<Logger>(relaxed = true),
            agentRegistry = mockk<AgentRegistry>(),
            outputValidator = mockk<OutputValidator>(),
            statsManager = mockk<StatsManager>()
        )

        orchestrator.readPrivate<DurableRuntimeService>("durableRuntimeService")::class shouldBe DurableRuntimeService::class
    }
})

private inline fun <reified T> Any.readPrivate(name: String): T {
    val field = this::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(this) as T
}
