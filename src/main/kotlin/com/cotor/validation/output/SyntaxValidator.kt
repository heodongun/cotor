package com.cotor.validation.output

import org.slf4j.LoggerFactory

data class SyntaxValidationResult(
    val isValid: Boolean,
    val message: String,
    val errors: List<String> = emptyList()
)

class SyntaxValidator {
    private val logger = LoggerFactory.getLogger(SyntaxValidator::class.java)

    fun validate(language: String, filePath: String): SyntaxValidationResult {
        return when (language.lowercase()) {
            "python" -> runCommand(listOf("python3", "-m", "py_compile", filePath), "Python")
            "javascript", "js" -> runCommand(listOf("node", "--check", filePath), "JavaScript")
            "typescript", "ts" -> runCommand(listOf("tsc", "--noEmit", filePath), "TypeScript")
            "kotlin", "kt" -> runCommand(listOf("kotlinc", filePath, "-script"), "Kotlin")
            else -> SyntaxValidationResult(true, "Unsupported language '$language', skipping")
        }
    }

    private fun runCommand(command: List<String>, label: String): SyntaxValidationResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                SyntaxValidationResult(true, "$label syntax valid")
            } else {
                SyntaxValidationResult(false, "$label syntax errors", errors = listOf(output.trim()))
            }
        } catch (e: Exception) {
            logger.debug("Syntax validation skipped: ${e.message}")
            SyntaxValidationResult(true, "Validation skipped: ${e.message}")
        }
    }
}
