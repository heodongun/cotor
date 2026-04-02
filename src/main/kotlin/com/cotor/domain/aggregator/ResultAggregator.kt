package com.cotor.domain.aggregator

/**
 * File overview for ResultAggregator.
 *
 * This file belongs to the domain layer for orchestration, planning, aggregation, and runtime policies.
 * It groups declarations around result aggregator so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.analysis.ResultAnalyzer
import com.cotor.model.AgentResult
import com.cotor.model.AggregatedResult
import java.time.Instant

/**
 * Interface for aggregating agent results
 */
interface ResultAggregator {
    /**
     * Aggregate multiple agent results
     * @param results List of agent results to aggregate
     * @return AggregatedResult containing summary and merged output
     */
    fun aggregate(results: List<AgentResult>): AggregatedResult
}

/**
 * Default implementation of result aggregator
 */
class DefaultResultAggregator(
    private val resultAnalyzer: ResultAnalyzer
) : ResultAggregator {

    override fun aggregate(results: List<AgentResult>): AggregatedResult {
        val successCount = results.count { it.isSuccess }
        val failureCount = results.count { !it.isSuccess }
        val totalDuration = results.sumOf { it.duration }
        val analysis = resultAnalyzer.analyze(results)

        return AggregatedResult(
            totalAgents = results.size,
            successCount = successCount,
            failureCount = failureCount,
            totalDuration = totalDuration,
            results = results,
            aggregatedOutput = mergeOutputs(results),
            timestamp = Instant.now(),
            analysis = analysis
        )
    }

    private fun mergeOutputs(results: List<AgentResult>): String {
        return results
            .filter { it.isSuccess && it.output != null }
            .joinToString("\n---\n") { result ->
                "[${result.agentName}]\n${result.output}"
            }
    }
}
