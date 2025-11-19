package com.cotor.analysis

import com.cotor.model.AgentResult
import com.cotor.model.ResultAnalysis

/**
 * Analyzes a collection of agent outputs to highlight consensus, best candidates,
 * and notable disagreements.
 */
interface ResultAnalyzer {
    fun analyze(results: List<AgentResult>): ResultAnalysis?
}
