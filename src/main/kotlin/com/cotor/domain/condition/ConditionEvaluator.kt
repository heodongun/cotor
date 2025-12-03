package com.cotor.domain.condition

import com.cotor.model.AgentResult
import com.cotor.model.PipelineContext
import org.slf4j.LoggerFactory

class ConditionEvaluator : Expression.Visitor<Any?> {
    private val logger = LoggerFactory.getLogger(ConditionEvaluator::class.java)
    private lateinit var context: PipelineContext

    fun evaluate(expression: String, context: PipelineContext): Boolean {
        this.context = context
        if (expression.isBlank()) return false

        return try {
            val tokens = Scanner(expression).scanTokens()
            val expr = Parser(tokens).parse()
            val result = evaluate(expr)
            isTruthy(result)
        } catch (e: Exception) {
            logger.error("Failed to evaluate condition expression: '$expression'", e)
            false
        }
    }

    private fun evaluate(expr: Expression): Any? {
        return expr.accept(this)
    }

    private fun isTruthy(obj: Any?): Boolean {
        val coerced = attemptCoercion(obj)
        if (coerced == null) return false
        if (coerced is Boolean) return coerced
        if (coerced is Number) return coerced.toDouble() != 0.0
        if (coerced is String) return coerced.isNotEmpty() && !coerced.equals("false", ignoreCase = true)
        return true
    }

    override fun visitBinaryExpr(expr: Expression.Binary): Any? {
        val left = evaluate(expr.left)

        if (expr.operator.type == TokenType.OR) {
            return if (isTruthy(left)) true else isTruthy(evaluate(expr.right))
        }
        if (expr.operator.type == TokenType.AND) {
            return if (!isTruthy(left)) false else isTruthy(evaluate(expr.right))
        }

        val right = evaluate(expr.right)

        return when (expr.operator.type) {
            TokenType.GREATER -> compareNumbers(left, right) { a, b -> a > b }
            TokenType.GREATER_EQUAL -> compareNumbers(left, right) { a, b -> a >= b }
            TokenType.LESS -> compareNumbers(left, right) { a, b -> a < b }
            TokenType.LESS_EQUAL -> compareNumbers(left, right) { a, b -> a <= b }
            TokenType.BANG_EQUAL -> !isEqual(left, right)
            TokenType.EQUAL_EQUAL -> isEqual(left, right)
            TokenType.CONTAINS -> left?.toString()?.contains(right.toString(), ignoreCase = true) ?: false
            TokenType.MATCHES -> runCatching {
                right?.toString()?.let { Regex(it).containsMatchIn(left.toString()) } ?: false
            }.getOrElse {
                logger.debug("Regex evaluation failed: ${it.message}")
                false
            }
            else -> null
        }
    }

    private fun compareNumbers(left: Any?, right: Any?, op: (Double, Double) -> Boolean): Boolean {
        val leftNum = left.asDouble()
        val rightNum = right.asDouble()
        return if (leftNum != null && rightNum != null) op(leftNum, rightNum) else false
    }

    override fun visitGroupingExpr(expr: Expression.Grouping): Any? {
        return evaluate(expr.expression)
    }

    override fun visitLiteralExpr(expr: Expression.Literal): Any? {
        return expr.value
    }

    override fun visitUnaryExpr(expr: Expression.Unary): Any? {
        val right = evaluate(expr.right)
        if (expr.operator.type == TokenType.BANG) {
            return !isTruthy(right)
        }
        return null
    }

    override fun visitVariableExpr(expr: Expression.Variable): Any? {
        return resolveValue(expr.name, context)
    }

    override fun visitCallExpr(expr: Expression.Call): Any? {
        val calleeName = (expr.callee as? Expression.Variable)?.name
        if (calleeName != null && expr.arguments.size == 1) {
            val stageIdExpr = expr.arguments[0]
            if (stageIdExpr is Expression.Variable) {
                val stageId = stageIdExpr.name
                val stageResult = context.getStageResult(stageId)
                if (stageResult != null) {
                    return when (calleeName) {
                        "success" -> stageResult.isSuccess
                        "tokens" -> stageResult.metadata["tokens"]?.toDoubleOrNull()
                        "output" -> stageResult.output
                        "reason" -> stageResult.metadata["reason"]
                        else -> null
                    }
                }
            }
        }
        return null
    }

    private fun isEqual(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false

        val numA = a.asDouble()
        val numB = b.asDouble()

        if (numA != null && numB != null) {
            return numA == numB
        }

        return a.toString().equals(b.toString(), ignoreCase = true)
    }

    private fun Any?.asDouble(): Double? {
        if (this == null) return null
        if (this is Number) return this.toDouble()
        if (this is Boolean) return if (this) 1.0 else 0.0
        return this.toString().toDoubleOrNull()
    }

    private fun attemptCoercion(value: Any?): Any? {
        if (value !is String) return value
        return value.toDoubleOrNull()
            ?: if (value.equals("true", ignoreCase = true)) true
            else if (value.equals("false", ignoreCase = true)) false
            else value
    }

    private fun resolveValue(token: String, context: PipelineContext): Any? {
        val resolved = when {
            token.startsWith("context.sharedState.") -> {
                val key = token.removePrefix("context.sharedState.")
                context.sharedState[key]
            }
            token.startsWith("context.metadata.") -> {
                val key = token.removePrefix("context.metadata.")
                context.metadata[key]
            }
            token == "context.elapsedTimeMs" -> context.elapsedTime()
            else -> resolveStageReference(token, context)
        }
        return attemptCoercion(resolved)
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
            else -> stageResult.metadata[attribute]
        }
    }
}
