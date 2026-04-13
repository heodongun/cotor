package com.cotor.di

/**
 * File overview for KoinModules.
 *
 * This file belongs to the dependency wiring layer that assembles runtime services and integrations.
 * It groups declarations around koin modules so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.analysis.DefaultResultAnalyzer
import com.cotor.analysis.ResultAnalyzer
import com.cotor.app.DesktopAppService
import com.cotor.app.DesktopStateStore
import com.cotor.app.DesktopTuiSessionService
import com.cotor.app.GitWorkspaceService
import com.cotor.app.runtime.CompanyRuntimeBindingService
import com.cotor.data.config.ConfigRepository
import com.cotor.data.config.FileConfigRepository
import com.cotor.data.config.JsonParser
import com.cotor.data.config.YamlParser
import com.cotor.data.plugin.PluginLoader
import com.cotor.data.plugin.ReflectionPluginLoader
import com.cotor.data.process.CoroutineProcessManager
import com.cotor.data.process.ProcessManager
import com.cotor.data.registry.AgentRegistry
import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.domain.aggregator.DefaultResultAggregator
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.domain.executor.DefaultAgentExecutor
import com.cotor.domain.orchestrator.DefaultPipelineOrchestrator
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.event.CoroutineEventBus
import com.cotor.event.EventBus
import com.cotor.model.PerformanceConfig
import com.cotor.model.SecurityConfig
import com.cotor.monitoring.DefaultObservabilityService
import com.cotor.monitoring.MetricsCollector
import com.cotor.monitoring.ObservabilityService
import com.cotor.monitoring.PipelineRunTracker
import com.cotor.monitoring.ResourceMonitor
import com.cotor.monitoring.StructuredLogger
import com.cotor.presentation.formatter.*
import com.cotor.policy.PolicyEngine
import com.cotor.providers.github.GitHubControlPlaneStore
import com.cotor.providers.github.GitHubControlPlaneService
import com.cotor.provenance.ProvenanceService
import com.cotor.knowledge.KnowledgeService
import com.cotor.runtime.actions.ActionInterceptor
import com.cotor.runtime.actions.ActionExecutionService
import com.cotor.runtime.durable.DurableResumeCoordinator
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.security.DefaultSecurityValidator
import com.cotor.security.SecurityValidator
import com.cotor.stats.StatsManager
import com.cotor.validation.output.DefaultOutputValidator
import com.cotor.validation.output.OutputValidator
import com.cotor.validation.output.SyntaxValidator
import org.koin.dsl.module
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Main Koin module for Cotor system
 */
val cotorModule = module {

    // Logging
    single<Logger> { LoggerFactory.getLogger("Cotor") }

    // Data Layer
    single<YamlParser> { YamlParser() }
    single<JsonParser> { JsonParser() }
    single<ConfigRepository> { FileConfigRepository(get(), get()) }
    single<AgentRegistry> { InMemoryAgentRegistry() }
    single<ProcessManager> { CoroutineProcessManager(get()) }
    single<PluginLoader> { ReflectionPluginLoader(get()) }
    single { DurableRuntimeService() }
    single { DurableResumeCoordinator(get(), get(), get(), get()) }
    single { ProvenanceService() }
    single { KnowledgeService() }
    single { PolicyEngine() }
    single<ActionInterceptor> { get<PolicyEngine>() }
    single { GitHubControlPlaneStore() }
    single { GitHubControlPlaneService(get(), get(), get()) }
    single {
        ActionExecutionService(
            actionStore = com.cotor.runtime.actions.ActionStore(),
            durableRuntimeService = get(),
            provenanceService = get(),
            interceptors = listOf(get<ActionInterceptor>()),
            logger = get()
        )
    }
    single { CompanyRuntimeBindingService(get(), com.cotor.runtime.actions.ActionStore(), get(), get()) }
    // Desktop-only services are registered here so the app-server can reuse the
    // same process manager, config loading, and executor pipeline as the CLI.
    single { DesktopStateStore() }
    single { GitWorkspaceService(get(), get(), get(), get(), get()) }
    single { DesktopAppService(get(), get(), get(), get(), runtimeBindingService = get(), gitHubControlPlaneService = get(), knowledgeService = get(), durableRuntimeService = get()) }
    single { DesktopTuiSessionService(get(), get(), get(), get()) }

    // Security
    single<SecurityConfig> {
        SecurityConfig(
            useWhitelist = true,
            allowedExecutables = setOf(
                "python3", "node", "java", "git", "gh", "lsof",
                "claude", "codex", "copilot", "gemini", "cursor-cli", "opencode", "qwen"
            ),
            allowedDirectories = listOf(
                Path("/usr/local/bin"),
                Path("/opt/homebrew/bin"),
                Path("/opt/cotor"),
                Path(System.getProperty("user.home")).resolve("Library").resolve("Application Support").resolve("CotorDesktop")
            )
        )
    }
    single<SecurityValidator> { DefaultSecurityValidator(get(), get()) }

    // Domain Layer
    single<AgentExecutor> { DefaultAgentExecutor(get(), get(), get(), get(), get(), get(), get()) }
    single<ResultAnalyzer> { DefaultResultAnalyzer() }
    single<ResultAggregator> { DefaultResultAggregator(get()) }
    single<SyntaxValidator> { SyntaxValidator() }
    single<OutputValidator> { DefaultOutputValidator(get()) }
    single<StatsManager> { StatsManager() }
    single<PipelineOrchestrator> { DefaultPipelineOrchestrator(get(), get(), get(), get(), get(), get(), get(), observability = get(), durableRuntimeService = get()) }
    single(createdAtStart = true) { PipelineRunTracker(get()) }

    // Event System
    single<EventBus> { CoroutineEventBus() }

    // Monitoring
    single<StructuredLogger> { StructuredLogger(get()) }
    single<MetricsCollector> { MetricsCollector() }
    single<ObservabilityService> { DefaultObservabilityService(get(), get()) }
    single<PerformanceConfig> { PerformanceConfig() }
    single<ResourceMonitor> { ResourceMonitor(get(), get()) }

    // Presentation Layer - Output Formatters
    single<Map<String, OutputFormatter>> {
        mapOf(
            "json" to JsonOutputFormatter(),
            "csv" to CsvOutputFormatter(),
            "text" to TextOutputFormatter()
        )
    }
}

/**
 * Initialize Koin dependency injection
 */
fun initializeCotor() {
    org.koin.core.context.startKoin {
        modules(cotorModule)
    }
}
