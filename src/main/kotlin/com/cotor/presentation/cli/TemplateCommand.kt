package com.cotor.presentation.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

/**
 * Command to generate pipeline templates
 */
class TemplateCommand(
    private val terminal: Terminal = Terminal()
) : CliktCommand(
    name = "template",
    help = "Generate pipeline templates from pre-defined patterns"
) {
    private val templateType by argument(
        name = "type",
        help = "Template type: compare, chain, review, consensus, fanout, selfheal, verified, blocked-escalation, custom"
    ).choice(
        "compare", "chain", "review", "consensus", "fanout", "selfheal", "verified", "blocked-escalation", "custom",
        ignoreCase = true
    ).optional()

    private val outputFile by argument(
        name = "output",
        help = "Output YAML file path"
    ).optional()

    private val interactive by option(
        "--interactive",
        "-i",
        help = "Interactive mode with prompts for customization"
    ).flag(default = false)

    private val preview by option("--preview", help = "Preview a template without writing a file")
        .choice("compare", "chain", "review", "consensus", "fanout", "selfheal", "verified", "blocked-escalation", "custom")

    private val list by option("--list", help = "List available templates").flag(default = false)

    private val fills by option("--fill", "-F", help = "Replace placeholders: key=value").multiple()

    override fun run() {
        if (list && templateType == null && preview == null) {
            showTemplateList()
            return
        }

        val targetType = preview ?: templateType
        if (targetType == null) {
            showTemplateList()
            return
        }

        val template = if (interactive) {
            generateInteractiveTemplate(targetType)
        } else {
            generateTemplate(targetType)
        }.let { applyFills(it) }

        if (preview != null && outputFile == null) {
            terminal.println(bold(blue("📄 Template preview ($preview)")))
            terminal.println()
            terminal.println(template)
            return
        }

        val filename = outputFile ?: "cotor-$targetType.yaml"

        File(filename).writeText(template)

        terminal.println(green("✅ Template created: $filename"))
        terminal.println()
        terminal.println(dim("Next steps:"))
        terminal.println(dim("  1. Edit $filename to customize agents and inputs"))
        terminal.println(dim("  2. Run: cotor validate <pipeline> -c $filename"))
        terminal.println(dim("  3. Execute: cotor run <pipeline> -c $filename --output-format text"))
    }

    /**
     * Generate template with interactive prompts
     */
    private fun generateInteractiveTemplate(type: String): String {
        terminal.println(bold(blue("🎨 Interactive Template Generation")))
        terminal.println()

        // Gather user inputs
        terminal.print(yellow("Pipeline name: "))
        val pipelineName = readLine()?.trim() ?: type

        terminal.print(yellow("Pipeline description: "))
        val description = readLine()?.trim() ?: "Custom $type pipeline"

        terminal.print(yellow("Number of agents (1-5): "))
        val numAgents = readLine()?.trim()?.toIntOrNull()?.coerceIn(1, 5) ?: 2

        val agents = mutableListOf<String>()
        repeat(numAgents) { i ->
            terminal.print(yellow("Agent ${i + 1} name (claude/gemini/codex/openai): "))
            val agent = readLine()?.trim() ?: "claude"
            agents.add(agent)
        }

        terminal.print(yellow("Execution mode (SEQUENTIAL/PARALLEL/DAG): "))
        val mode = readLine()?.trim()?.uppercase() ?: "SEQUENTIAL"

        terminal.print(yellow("Timeout per agent (ms, default 60000): "))
        val timeout = readLine()?.trim()?.toLongOrNull() ?: 60000L

        terminal.println()
        terminal.println(green("✨ Generating customized template..."))

        return buildCustomTemplate(
            pipelineName = pipelineName,
            description = description,
            agents = agents,
            executionMode = mode,
            timeout = timeout,
            templateType = type
        )
    }

    /**
     * Build custom template from user inputs
     */
    private fun buildCustomTemplate(
        pipelineName: String,
        description: String,
        agents: List<String>,
        executionMode: String,
        timeout: Long,
        templateType: String
    ): String {
        val agentDefs = agents.distinct().joinToString("\n") { agent ->
            val pluginClass = when (agent) {
                "claude" -> "com.cotor.data.plugin.ClaudePlugin"
                "gemini" -> "com.cotor.data.plugin.GeminiPlugin"
                "codex" -> "com.cotor.data.plugin.CodexPlugin"
                "copilot" -> "com.cotor.data.plugin.CopilotPlugin"
                "openai" -> "com.cotor.data.plugin.OpenAIPlugin"
                else -> "com.cotor.data.plugin.ClaudePlugin"
            }
            """  - name: $agent
    pluginClass: $pluginClass
    timeout: $timeout"""
        }

        val stages = agents.mapIndexed { index, agent ->
            """      - id: $agent-stage-${index + 1}
        agent:
          name: $agent
        input: "YOUR_PROMPT_HERE"""
        }.joinToString("\n\n")

        val allowedExecs = agents.distinct().joinToString("\n") { "    - $it" }

        return """version: "1.0"

agents:
$agentDefs

pipelines:
  - name: $pipelineName
    description: "$description"
    executionMode: $executionMode
    stages:
$stages

security:
  useWhitelist: true
  allowedExecutables:
$allowedExecs
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO

performance:
  maxConcurrentAgents: ${agents.size}
        """.trimIndent()
    }

    private fun showTemplateList() {
        terminal.println(bold(blue("📋 Available Pipeline Templates")))
        terminal.println()

        val templates = mapOf(
            "compare" to "Multiple AIs solve the same problem in parallel for comparison",
            "chain" to "Sequential processing chain (generate → review → optimize)",
            "review" to "Parallel multi-perspective code review (security, performance, best practices)",
            "consensus" to "Multiple AIs provide opinions to reach consensus",
            "fanout" to "DAG fan-out/fan-in merge pattern",
            "selfheal" to "Loop-based self-healing/retry pattern",
            "verified" to "Generate → test/verify → fix loop (uses reusable QA verification agent)",
            "blocked-escalation" to "Detect blocked items and auto-escalate to CTO/EM when stale",
            "custom" to "Customizable template with common patterns"
        )

        templates.forEach { (type, description) ->
            terminal.println("  ${type.padEnd(12)} - $description")
        }

        terminal.println()
        terminal.println(dim("Usage: cotor template <type> [output-file] [--preview] [--fill key=value]"))
        terminal.println(dim("Example: cotor template compare my-pipeline.yaml --fill prompt=\"Write tests\""))
        terminal.println(dim("Preview: cotor template --preview chain"))
        terminal.println(dim("List:    cotor template --list"))
    }

    private fun applyFills(template: String): String {
        if (fills.isEmpty()) return template
        var output = template
        fills.forEach { pair ->
            val (key, value) = pair.split("=", limit = 2).let {
                if (it.size == 2) it[0].trim() to it[1].trim() else return@forEach
            }
            output = output.replace("{{$key}}", value, ignoreCase = false)
            if (key.equals("prompt", true)) {
                output = output.replace("YOUR_PROMPT_HERE", value)
            }
        }
        return output
    }

    private fun generateTemplate(type: String): String {
        return when (type.lowercase()) {
            "compare" -> generateCompareTemplate()
            "chain" -> generateChainTemplate()
            "review" -> generateReviewTemplate()
            "consensus" -> generateConsensusTemplate()
            "fanout" -> generateFanoutTemplate()
            "selfheal" -> generateSelfHealTemplate()
            "verified" -> generateVerifiedTemplate()
            "blocked-escalation" -> generateBlockedEscalationTemplate()
            "custom" -> generateCustomTemplate()
            else -> throw IllegalArgumentException("Unknown template type: $type")
        }
    }

    private fun generateBlockedEscalationTemplate() = """
version: "1.0"

agents:
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000

  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

pipelines:
  - name: blocked-reassignment-escalation
    description: "Auto reassignment for blocked work items after N-minute stagnation"
    executionMode: SEQUENTIAL
    stages:
      - id: classify-blocked-item
        agent:
          name: codex
        input: |
          You are an issue routing engine.
          Analyze the blocked work item and output exactly one label:
          - REASSIGN_TEAM
          - ESCALATE_EM
          - ESCALATE_CTO

          Rule:
          1) If blocked duration is under {{stall_minutes}} minutes -> REASSIGN_TEAM
          2) If blocked duration is >= {{stall_minutes}} and < {{cto_minutes}} -> ESCALATE_EM
          3) If blocked duration is >= {{cto_minutes}} -> ESCALATE_CTO

          Context:
          {{blocked_item_context}}

      - id: route-escalation
        type: DECISION
        condition:
          expression: "classify-blocked-item.output.contains(\"ESCALATE_CTO\")"
          onTrue:
            action: GOTO
            targetStageId: notify-cto
          onFalse:
            action: CONTINUE

      - id: route-em
        type: DECISION
        condition:
          expression: "classify-blocked-item.output.contains(\"ESCALATE_EM\")"
          onTrue:
            action: GOTO
            targetStageId: notify-em
          onFalse:
            action: GOTO
            targetStageId: reassign-team

      - id: reassign-team
        agent:
          name: codex
        input: |
          Reassign this blocked work item to the best available engineer in the same team.
          Include rationale and SLA reminder.

          Item context:
          {{blocked_item_context}}

      - id: notify-em
        agent:
          name: claude
        input: |
          Draft an escalation notice to EM for a blocked item that exceeded {{stall_minutes}} minutes.
          Include: issue, blocker summary, attempted actions, and requested decision.

          Item context:
          {{blocked_item_context}}

      - id: notify-cto
        agent:
          name: claude
        input: |
          Draft an urgent escalation notice to CTO for a blocked item that exceeded {{cto_minutes}} minutes.
          Include: business impact, blocker owner, mitigation options, and immediate ask.

          Item context:
          {{blocked_item_context}}

security:
  useWhitelist: true
  allowedExecutables:
    - codex
    - claude
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO

performance:
  maxConcurrentAgents: 1
    """.trimIndent()

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

    private fun generateFanoutTemplate() = """
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: fanout-merge
    description: "DAG fan-out/fan-in merge"
    executionMode: DAG
    stages:
      - id: seed
        agent:
          name: claude
        input: "공통 입력을 분석"

      - id: branch-a
        agent:
          name: claude
        input: "접근 A 제안"
        dependencies: [seed]

      - id: branch-b
        agent:
          name: gemini
        input: "접근 B 제안"
        dependencies: [seed]

      - id: merge
        agent:
          name: claude
        input: "두 접근을 합쳐 최종안을 작성"
        dependencies: [branch-a, branch-b]

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

    private fun generateSelfHealTemplate() = """
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

pipelines:
  - name: self-heal-loop
    description: "Loop with retry/repair"
    executionMode: SEQUENTIAL
    stages:
      - id: attempt-1
        agent:
          name: claude
        input: "문제를 해결하고 결과를 보고해줘"

      - id: attempt-2
        agent:
          name: claude
        input: "이전 결과를 개선하거나 오류를 고쳐줘"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO

performance:
  maxConcurrentAgents: 2
    """.trimIndent()

    private fun generateVerifiedTemplate() = """
version: "1.0"

agents:
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 600000

  - name: qa
    pluginClass: com.cotor.data.plugin.QaVerificationPlugin
    timeout: 600000
    parameters:
      # Optional override. Remove this to auto-detect by repository files.
      argvJson: '["./gradlew","test","--console=plain"]'

  - name: echo
    pluginClass: com.cotor.data.plugin.EchoPlugin
    timeout: 30000

pipelines:
  - name: verified-implementation
    description: "Generate code, verify via command, and auto-fix until it passes"
    executionMode: SEQUENTIAL
    stages:
      - id: implement
        agent:
          name: codex
        input: |
          YOUR_PROMPT_HERE
          - Make the required code changes in the repo (edit files).
          - Keep changes minimal and focused.

      - id: test
        agent:
          name: qa
        input: ""
        failureStrategy: CONTINUE
        optional: true

      - id: decide
        type: DECISION
        condition:
          expression: "!test.success"
          onTrue:
            action: CONTINUE
            message: "Tests failed, attempting fix"
          onFalse:
            action: GOTO
            targetStageId: done
            message: "Tests passed"

      - id: fix
        agent:
          name: codex
        input: |
          Tests failed. Fix the code until tests pass.
          
          Test output:
          ${'$'}{stages.test.output}
          
          Test error:
          ${'$'}{stages.test.error}

      - id: loop
        type: LOOP
        loop:
          targetStageId: test
          maxIterations: 5
          untilExpression: "test.success == true"

      - id: done
        agent:
          name: echo
        input: "✅ Verified: tests passed"

security:
  useWhitelist: false
  allowedExecutables: []
  allowedDirectories: []

logging:
  level: INFO

performance:
  maxConcurrentAgents: 3
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
