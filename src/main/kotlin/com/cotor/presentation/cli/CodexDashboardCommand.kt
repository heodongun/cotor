package com.cotor.presentation.cli

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.error.ErrorMessages
import com.cotor.monitoring.TimelineCollector
import com.cotor.presentation.timeline.StageTimelineEntry
import com.cotor.presentation.timeline.StageTimelineState
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Simple Codex-style dashboard to run pipelines interactively
 */
class CodexDashboardCommand : CliktCommand(
    name = "dash",
    help = "Run pipelines via an interactive Codex-style dashboard"
), KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val orchestrator: PipelineOrchestrator by inject()
    private val eventBus: com.cotor.event.EventBus by inject()
    private val terminal = Terminal()
    private val timelineCollector by lazy { TimelineCollector(eventBus) }

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    override fun run() = runBlocking {
        if (!configPath.exists()) {
            echo(ErrorMessages.configNotFound(configPath.toString()).message)
            return@runBlocking
        }

        val config = configRepository.loadConfig(configPath)
        if (config.pipelines.isEmpty()) {
            echo("등록된 파이프라인이 없습니다. 먼저 cotor init 으로 생성하세요.")
            return@runBlocking
        }
        config.agents.forEach { agentRegistry.registerAgent(it) }

        while (true) {
            terminal.println()
            terminal.println(bold("◎ Codex Dashboard"))
            config.pipelines.forEachIndexed { index, pipeline ->
                terminal.println("${index + 1}. ${cyan(pipeline.name)}  ${dim(pipeline.description)}")
            }
            terminal.println(dim("q를 입력하면 종료합니다."))

            val input = promptLine("실행할 파이프라인 번호") ?: break
            if (input.equals("q", true)) break

            val index = input.toIntOrNull()
            val selected = if (index != null && index in 1..config.pipelines.size) {
                config.pipelines[index - 1]
            } else {
                echo("잘못된 번호입니다.")
                continue
            }

            terminal.println()
            terminal.println(bold("▶ ${selected.name} 실행"))
            val timelineResult = timelineCollector.runWithTimeline(selected.name) {
                orchestrator.executePipeline(selected)
            }
            val runResult = timelineResult.result.copy(
                totalDuration = timelineResult.totalDurationMs ?: timelineResult.result.totalDuration
            )

            renderTimeline(timelineResult.timeline)
            terminal.println()
            terminal.println(bold("결과: ${runResult.successCount}/${runResult.totalAgents} 단계 성공"))
            terminal.println("총 소요 시간: ${runResult.totalDuration}ms")

            val again = promptLine("다른 파이프라인을 실행할까요? (Y/n)", "y")
            if (again.equals("n", true)) break
        }
    }

    private fun renderTimeline(entries: List<StageTimelineEntry>) {
        if (entries.isEmpty()) {
            terminal.println(dim("타임라인 없음"))
            return
        }
        terminal.println()
        entries.forEach { entry ->
            val icon = when (entry.state) {
                StageTimelineState.STARTED -> yellow("●")
                StageTimelineState.COMPLETED -> green("●")
                StageTimelineState.FAILED -> red("●")
            }
            terminal.println("$icon ${entry.stageId} - ${entry.message}")
            entry.outputPreview?.let {
                terminal.println(dim("   ${it.replace("\n", " ").take(120)}"))
            }
        }
    }

    private fun promptLine(message: String, default: String? = null): String? {
        val suffix = if (default != null) " [$default]" else ""
        terminal.print("${message}$suffix: ")
        val input = readLine()
        if (input.isNullOrBlank()) {
            return default
        }
        return input.trim()
    }
}
