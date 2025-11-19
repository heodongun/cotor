package com.cotor.presentation.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

/**
 * Command to generate pipeline templates
 */
class TemplateCommand : CliktCommand(
    name = "template",
    help = "Generate pipeline templates from pre-defined patterns"
) {
    private val templateType by argument(
        name = "type",
        help = "Template type: compare, chain, review, consensus, custom"
    ).choice(
        "compare", "chain", "review", "consensus", "custom",
        ignoreCase = true
    ).optional()

    private val outputFile by argument(
        name = "output",
        help = "Output YAML file path"
    ).optional()

    private val terminal = Terminal()

    override fun run() {
        if (templateType == null) {
            showTemplateList()
            return
        }

        val template = generateTemplate(templateType!!)
        val filename = outputFile ?: "cotor-${templateType}.yaml"

        File(filename).writeText(template)

        terminal.println(green("✅ Template created: $filename"))
        terminal.println()
        terminal.println(dim("Next steps:"))
        terminal.println(dim("  1. Edit $filename to customize agents and inputs"))
        terminal.println(dim("  2. Run: cotor validate $filename"))
        terminal.println(dim("  3. Execute: cotor run <pipeline-name> --config $filename"))
    }

    private fun showTemplateList() {
        terminal.println(bold(blue("📋 Available Pipeline Templates")))
        terminal.println()

        val templates = mapOf(
            "compare" to "Multiple AIs solve the same problem in parallel for comparison",
            "chain" to "Sequential processing chain (generate → review → optimize)",
            "review" to "Parallel multi-perspective code review (security, performance, best practices)",
            "consensus" to "Multiple AIs provide opinions to reach consensus",
            "custom" to "Customizable template with common patterns"
        )

        templates.forEach { (type, description) ->
            terminal.println("  ${yellow(type.padEnd(12))} - $description")
        }

        terminal.println()
        terminal.println(dim("Usage: cotor template <type> [output-file]"))
        terminal.println(dim("Example: cotor template compare my-pipeline.yaml"))
    }

    private fun generateTemplate(type: String): String {
        return when (type) {
            "compare" -> generateCompareTemplate()
            "chain" -> generateChainTemplate()
            "review" -> generateReviewTemplate()
            "consensus" -> generateConsensusTemplate()
            "custom" -> generateCustomTemplate()
            else -> throw IllegalArgumentException("Unknown template type: $type")
        }
    }

    private fun generateCompareTemplate() = """
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: compare-solutions
    description: "Compare AI solutions for the same problem"
    executionMode: PARALLEL
    stages:
      - id: claude-solution
        agent:
          name: claude
        input: "YOUR_PROMPT_HERE"

      - id: gemini-solution
        agent:
          name: gemini
        input: "YOUR_PROMPT_HERE"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - gemini
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO

performance:
  maxConcurrentAgents: 5
""".trimIndent()

    private fun generateChainTemplate() = """
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: processing-chain
    description: "Sequential processing chain"
    executionMode: SEQUENTIAL
    stages:
      - id: generate
        agent:
          name: claude
        input: "Generate initial code/content"

      - id: review
        agent:
          name: gemini
        # Input will be output from 'generate' stage

      - id: optimize
        agent:
          name: claude
        # Input will be output from 'review' stage

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - gemini
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO

performance:
  maxConcurrentAgents: 1
""".trimIndent()

    private fun generateReviewTemplate() = """
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: comprehensive-review
    description: "Multi-perspective code review"
    executionMode: PARALLEL
    stages:
      - id: security-review
        agent:
          name: claude
        input: "Review for security vulnerabilities: [YOUR_CODE]"

      - id: performance-review
        agent:
          name: gemini
        input: "Review for performance issues: [YOUR_CODE]"

      - id: best-practices
        agent:
          name: claude
        input: "Review for best practices: [YOUR_CODE]"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - gemini
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO

performance:
  maxConcurrentAgents: 5
""".trimIndent()

    private fun generateConsensusTemplate() = """
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: consensus-building
    description: "Gather opinions from multiple AIs"
    executionMode: PARALLEL
    stages:
      - id: claude-opinion
        agent:
          name: claude
        input: "What's your recommendation for [DECISION]?"

      - id: gemini-opinion
        agent:
          name: gemini
        input: "What's your recommendation for [DECISION]?"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - gemini
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO

performance:
  maxConcurrentAgents: 5
""".trimIndent()

    private fun generateCustomTemplate() = """
version: "1.0"

agents:
  # Add your agents here
  - name: my-agent
    pluginClass: com.cotor.data.plugin.ClaudePlugin  # Change to your plugin
    timeout: 60000

pipelines:
  - name: my-pipeline
    description: "Custom pipeline description"
    executionMode: SEQUENTIAL  # or PARALLEL or DAG
    stages:
      - id: stage-1
        agent:
          name: my-agent
        input: "Your prompt here"

      # Add more stages as needed

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    # Add your executables
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO

performance:
  maxConcurrentAgents: 5
""".trimIndent()
}
