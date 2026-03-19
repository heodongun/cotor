package com.cotor.analysis

/**
 * File overview for ResultAnalyzer.
 *
 * This file belongs to the analysis layer that summarizes and compares agent results.
 * It groups declarations around result analyzer so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.model.AgentResult
import com.cotor.model.ResultAnalysis

/**
 * Analyzes a collection of agent outputs to highlight consensus, best candidates,
 * and notable disagreements.
 */
interface ResultAnalyzer {
    fun analyze(results: List<AgentResult>): ResultAnalysis?
}
