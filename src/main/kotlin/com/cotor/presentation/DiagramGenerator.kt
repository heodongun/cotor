package com.cotor.presentation

import com.cotor.model.ExecutionMode
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage

object DiagramGenerator {
    fun generate(pipeline: Pipeline): String {
        val sb = StringBuilder()
        sb.appendLine("Execution Plan for Pipeline: ${pipeline.name} (Mode: ${pipeline.executionMode})")
        sb.appendLine("===================================================================")

        when (pipeline.executionMode) {
            ExecutionMode.SEQUENTIAL -> generateSequential(sb, pipeline.stages)
            ExecutionMode.PARALLEL -> generateParallel(sb, pipeline.stages)
            ExecutionMode.DAG -> generateDag(sb, pipeline.stages)
            ExecutionMode.MAP -> generateMap(sb, pipeline.stages)
        }

        sb.appendLine("===================================================================")
        return sb.toString()
    }

    private fun generateSequential(sb: StringBuilder, stages: List<PipelineStage>) {
        stages.forEachIndexed { index, stage ->
            sb.appendLine("➔ ${stage.id}")
            appendStageDetails(sb, stage, "  ")
            if (index < stages.size - 1) {
                sb.appendLine("  ↓")
            }
        }
    }

    private fun generateParallel(sb: StringBuilder, stages: List<PipelineStage>) {
        sb.appendLine("┌─ Parallel Execution")
        stages.forEach { stage ->
            sb.appendLine("├─ Parallel Task: ${stage.id}")
            appendStageDetails(sb, stage, "│  ")
        }
        sb.appendLine("└─")
    }

    private fun generateDag(sb: StringBuilder, stages: List<PipelineStage>) {
        val stageMap = stages.associateBy { it.id }
        val dependencies = stages.flatMap { stage -> stage.dependencies.map { dep -> dep to stage.id } }
        val entryPoints = stages.filter { it.dependencies.isEmpty() }

        val visited = mutableSetOf<String>()

        fun printStage(stageId: String, prefix: String) {
            if (stageId in visited && entryPoints.none { it.id == stageId }) {
                sb.appendLine("$prefix- ${stageId} (already rendered)")
                return
            }
            visited.add(stageId)

            val stage = stageMap[stageId]
            if (stage == null) {
                sb.appendLine("$prefix- ${stageId} (stage not found!)")
                return
            }

            sb.appendLine("$prefix- ${stage.id}")
            val newPrefix = prefix.replace("├─", "│ ").replace("└─", "  ")
            appendStageDetails(sb, stage, newPrefix)

            val children = dependencies.filter { it.first == stageId }.map { it.second }
            children.forEachIndexed { index, childId ->
                val isLastChild = index == children.size - 1
                val connector = if (isLastChild) "└─" else "├─"
                printStage(childId, "$newPrefix$connector")
            }
        }

        entryPoints.forEachIndexed { index, stage ->
            printStage(stage.id, "➔ ")
        }
    }

    private fun generateMap(sb: StringBuilder, stages: List<PipelineStage>) {
        val fanoutStage = stages.firstOrNull { it.fanout != null }
        if (fanoutStage != null) {
            sb.appendLine("➔ MAP: Each item in '${fanoutStage.fanout?.source}' triggers a parallel run of this plan:")
            generateSequential(sb, stages)
        } else {
            sb.appendLine("  MAP mode is configured, but no fanout stage was found. Showing sequential plan instead:")
            generateSequential(sb, stages)
        }
    }

    private fun appendStageDetails(sb: StringBuilder, stage: PipelineStage, prefix: String) {
        stage.recovery?.let {
            sb.appendLine("$prefix  ${"checkpoint".padEnd(12)}: Retry ${it.maxRetries} times, Fallback: ${it.fallbackAgents.joinToString(", ")}")
        }
        stage.condition?.let {
            sb.appendLine("$prefix  ${"condition".padEnd(12)}: ${it.expression}")
            sb.appendLine("$prefix    ${"onTrue".padEnd(10)}: ${it.onTrue.action} -> ${it.onTrue.targetStageId ?: "next"}")
            sb.appendLine("$prefix    ${"onFalse".padEnd(10)}: ${it.onFalse.action} -> ${it.onFalse.targetStageId ?: "next"}")
        }
        if (stage.dependencies.isNotEmpty()) {
            sb.appendLine("$prefix  ${"dependsOn".padEnd(12)}: ${stage.dependencies.joinToString(", ")}")
        }
    }
}
