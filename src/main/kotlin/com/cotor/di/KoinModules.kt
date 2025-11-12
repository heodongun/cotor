package com.cotor.di

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
import com.cotor.monitoring.MetricsCollector
import com.cotor.monitoring.ResourceMonitor
import com.cotor.monitoring.StructuredLogger
import com.cotor.presentation.formatter.*
import com.cotor.security.DefaultSecurityValidator
import com.cotor.security.SecurityValidator
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

    // Security
    single<SecurityConfig> {
        SecurityConfig(
            useWhitelist = true,
            allowedExecutables = setOf("python3", "node", "java"),
            allowedDirectories = listOf(Path("/usr/local/bin"), Path("/opt/cotor"))
        )
    }
    single<SecurityValidator> { DefaultSecurityValidator(get(), get()) }

    // Domain Layer
    single<AgentExecutor> { DefaultAgentExecutor(get(), get(), get(), get()) }
    single<ResultAggregator> { DefaultResultAggregator() }
    single<PipelineOrchestrator> { DefaultPipelineOrchestrator(get(), get(), get(), get()) }

    // Event System
    single<EventBus> { CoroutineEventBus() }

    // Monitoring
    single<StructuredLogger> { StructuredLogger(get()) }
    single<MetricsCollector> { MetricsCollector() }
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
