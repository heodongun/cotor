package com.cotor.domain.condition

import com.cotor.model.AgentResult
import com.cotor.model.PipelineContext
import org.slf4j.LoggerFactory

/**
 * Evaluates simple boolean expressions against the current PipelineContext.
 *
 * Supported operators:
 *  - Comparison: ==, !=, >, >=, <, <=
 *  - Text: contains, matches (regex)
 *  - Logical: &&, ||
 *
 * Stage references use the form `stageId.property`.
 * Examples:
 *   quality-check.validationScore >= 0.8
 *   review.success == true && review.metadata.severity != "HIGH"
 */
class ConditionEvaluator {
    private val logger = LoggerFactory.getLogger(ConditionEvaluator::class.java)
    private val comparatorRegex =
        Regex("^(.+?)(==|!=|>=|<=|>|<|(?i)contains|(?i)matches)(.+)$")

    fun evaluate(expression: String, context: PipelineContext): Boolean {
        if (expression.isBlank()) return false

        val disjunctions = splitLogical(expression, "||")
        return disjunctions.any { evaluateConjunction(it.trim(), context) }
    }

    private fun evaluateConjunction(expression: String, context: PipelineContext): Boolean {
        val conjunctions = splitLogical(expression, "&&")
        return conjunctions.all { evaluateAtomic(it.trim(), context) }
    }

    private fun evaluateAtomic(expression: String, context: PipelineContext): Boolean {
        val trimmed = unwrap(expression.trim())
        if (trimmed.isEmpty()) return false

        val matcher = comparatorRegex.matchEntire(trimmed)
        if (matcher != null) {
            val left = resolveValue(matcher.groupValues[1].trim(), context)
            val operator = matcher.groupValues[2].trim()
            val right = resolveValue(matcher.groupValues[3].trim(), context)
            return compareValues(left, right, operator)
        }

        val directValue = resolveValue(trimmed, context)
        return when (directValue) {
            is Boolean -> directValue
            is Number -> directValue.toDouble() != 0.0
            is String -> directValue.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun unwrap(expression: String): String {
        if (expression.startsWith("(") && expression.endsWith(")")) {
            var depth = 0
            for (i in expression.indices) {
                val char = expression[i]
                if (char == '(') depth++
                if (char == ')') depth--
                if (depth == 0 && i != expression.lastIndex) {
                    return expression
                }
            }
            return expression.substring(1, expression.length - 1)
        }
        return expression
    }

    private fun compareValues(left: Any?, right: Any?, rawOperator: String): Boolean {
        val operator = rawOperator.lowercase()
        val leftNumber = left.asDouble()
        val rightNumber = right.asDouble()

        return when (operator) {
            ">" -> leftNumber != null && rightNumber != null && leftNumber > rightNumber
            ">=" -> leftNumber != null && rightNumber != null && leftNumber >= rightNumber
            "<" -> leftNumber != null && rightNumber != null && leftNumber < rightNumber
            "<=" -> leftNumber != null && rightNumber != null && leftNumber <= rightNumber
            "==" -> normalize(left) == normalize(right)
            "!=" -> normalize(left) != normalize(right)
            "contains" -> left.toString().contains(right.toString(), ignoreCase = true)
            "matches" -> runCatching {
                Regex(right.toString()).containsMatchIn(left.toString())
            }.getOrElse {
                logger.debug("Regex evaluation failed: ${it.message}")
                false
            }
            else -> false
        }
    }

    private fun normalize(value: Any?): String? {
        return when (value) {
            null -> null
            is Boolean -> value.toString()
            is Number -> value.toString()
            is AgentResult -> value.output
            else -> value.toString()
        }
    }

    private fun Any?.asDouble(): Double? {
        return when (this) {
            is Number -> this.toDouble()
            is String -> this.toDoubleOrNull()
            else -> null
        }
    }

    private fun resolveValue(token: String, context: PipelineContext): Any? {
        val trimmed = token.trim()
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length >= 2) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        trimmed.toLongOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { return it }
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false

        return when {
            trimmed.startsWith("context.sharedState.") -> {
                val key = trimmed.removePrefix("context.sharedState.")
                context.sharedState[key]
            }
            trimmed.startsWith("context.metadata.") -> {
                val key = trimmed.removePrefix("context.metadata.")
                context.metadata[key]
            }
            trimmed == "context.elapsedTimeMs" -> context.elapsedTime()
            else -> resolveStageReference(trimmed, context)
        }
    }

    private fun resolveStageReference(identifier: String, context: PipelineContext): Any? {
        val parts = identifier.split(".")
        if (parts.isEmpty()) return identifier

        val stageId = parts.first()
        val stageResult = context.getStageResult(stageId) ?: return identifier

        if (parts.size == 1) {
            return stageResult.output ?: stageResult.metadata
        }

        return when (val attribute = parts[1]) {
            "output" -> stageResult.output
            "error" -> stageResult.error
            "success" -> stageResult.isSuccess
            "metadata" -> {
                if (parts.size >= 3) {
                    val key = parts.subList(2, parts.size).joinToString(".")
                    stageResult.metadata[key]
                } else {
                    stageResult.metadata
                }
            }
            else -> stageResult.metadata[attribute] ?: stageResult.output
        }
    }

    private fun splitLogical(expression: String, delimiter: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inSingle = false
        var inDouble = false

        var i = 0
        while (i <= expression.length - delimiter.length) {
            val c = expression[i]
            when (c) {
                '(' -> if (!inSingle && !inDouble) depth++
                ')' -> if (!inSingle && !inDouble) depth--
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
            }

            if (depth == 0 && !inSingle && !inDouble &&
                expression.regionMatches(i, delimiter, 0, delimiter.length)
            ) {
                parts.add(expression.substring(start, i))
                start = i + delimiter.length
                i += delimiter.length
                continue
            }
            i++
        }
        parts.add(expression.substring(start))
        return parts.filter { it.isNotBlank() }
    }
}
