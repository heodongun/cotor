package com.cotor.domain.aggregator

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
class DefaultResultAggregator : ResultAggregator {

    override fun aggregate(results: List<AgentResult>): AggregatedResult {
        val successCount = results.count { it.isSuccess }
        val failureCount = results.count { !it.isSuccess }
        val totalDuration = results.sumOf { it.duration }

        return AggregatedResult(
            totalAgents = results.size,
            successCount = successCount,
            failureCount = failureCount,
            totalDuration = totalDuration,
            results = results,
            aggregatedOutput = mergeOutputs(results),
            timestamp = Instant.now()
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
