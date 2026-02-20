package com.cotor.presentation.cli

import com.cotor.chat.ChatMode
import com.cotor.chat.ChatSession
import com.cotor.chat.ChatTranscriptWriter
import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.error.ErrorMessages
import com.cotor.model.AgentConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Chat with the master agent in an interactive (TUI-like) loop.
 *
 * This intentionally avoids YAML pipelines; it dispatches directly to configured agents.
 */
class InteractiveCommand : CliktCommand(
    name = "interactive",
    help = "Chat with the master agent via a terminal UI (no YAML pipeline required)"
), KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val agentExecutor: AgentExecutor by inject()
    private val resultAggregator: ResultAggregator by inject()
    private val terminal = Terminal()

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    private val mode by option("--mode", help = "Chat mode")
        .choice("auto", "single", "compare")
        .default("auto")

    private val agent by option("--agent", help = "Active agent for single mode (name)")

    private val agents by option(
        "--agents",
        help = "Comma-separated agent names to use for compare/auto (default: all)"
    )

    private val prompt by option("--prompt", help = "Run a single prompt non-interactively and exit")

    private val promptFile by option("--prompt-file", help = "Run prompts from a file (one per line) and exit")
        .path(mustExist = false)

    private val saveDir by option("--save-dir", help = "Directory to write the transcript to")
        .path(mustExist = false)

    private val noContext by option(
        "--no-context",
        help = "Do not include conversation context when building the prompt (raw prompt passthrough)"
    ).flag(default = false)

    private val maxHistory by option("--max-history", help = "Max number of previous messages to include as context")
        .int()
        .default(20)

    private val showAll by option(
        "--show-all",
        help = "In auto mode, show all agent outputs instead of only the selected best output"
    ).flag(default = false)

    private val retry by option(
        "--retry",
        help = "Enable agent-level retries using each agent's configured retryPolicy"
    ).flag(default = false)

    override fun run() = runBlocking {
        if (!configPath.exists()) {
            throw ErrorMessages.configNotFound(configPath.toString())
        }

        val config = configRepository.loadConfig(configPath)
        if (config.agents.isEmpty()) {
            terminal.println(red("No agents configured. Add agents to your config first (cotor init)."))
            return@runBlocking
        }

        config.agents.forEach { agentRegistry.registerAgent(it) }
        val allAgents = config.agents.associateBy { it.name }

        val chatMode = ChatMode.parse(mode)
        val selectedAgents = parseAgentsOption(agents, allAgents.values.toList())
        val activeAgent = resolveActiveAgent(chatMode, agent, selectedAgents, allAgents)

        val session = ChatSession(includeContext = !noContext, maxHistoryMessages = maxHistory)
        val outputDir = (saveDir ?: defaultSaveDir()).also { it.createDirectories() }
        val transcript = ChatTranscriptWriter(outputDir)

        val headerLines = buildList {
            add("Config: $configPath")
            add("Mode: $chatMode")
            add("Agents: ${selectedAgents.joinToString(", ") { it.name }}")
            if (activeAgent != null) add("ActiveAgent: ${activeAgent.name}")
        }

        when {
            prompt != null -> {
                val response = runTurn(
                    session = session,
                    chatMode = chatMode,
                    activeAgent = activeAgent,
                    selectedAgents = selectedAgents,
                    userInput = prompt!!,
                    verbose = false
                )
                session.addUser(prompt!!)
                session.addAssistant(response)
                transcript.writeMarkdown(session, headerLines)
                transcript.writeRawText(session)
                echo(response)
            }

            promptFile != null -> {
                if (!promptFile!!.exists()) {
                    throw IllegalArgumentException("Prompt file not found: $promptFile")
                }
                val prompts = promptFile!!.readLines().map { it.trimEnd() }.filter { it.isNotBlank() }
                val outputs = mutableListOf<String>()
                for (line in prompts) {
                    val response = runTurn(
                        session = session,
                        chatMode = chatMode,
                        activeAgent = activeAgent,
                        selectedAgents = selectedAgents,
                        userInput = line,
                        verbose = false
                    )
                    session.addUser(line)
                    session.addAssistant(response)
                    outputs += response
                }
                transcript.writeMarkdown(session, headerLines)
                transcript.writeRawText(session)
                echo(outputs.joinToString("\n\n"))
            }

            else -> {
                runInteractiveLoop(
                    session = session,
                    chatModeInitial = chatMode,
                    activeAgentInitial = activeAgent,
                    selectedAgentsInitial = selectedAgents,
                    allAgents = allAgents,
                    transcript = transcript,
                    headerLines = headerLines
                )
            }
        }
    }

    private fun parseAgentsOption(raw: String?, defaultAgents: List<AgentConfig>): List<AgentConfig> {
        if (raw.isNullOrBlank()) return defaultAgents
        val requested = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (requested.isEmpty()) return defaultAgents

        val resolved = mutableListOf<AgentConfig>()
        val missing = mutableListOf<String>()

        requested.forEach { name ->
            val agent = agentRegistry.getAgent(name)
            if (agent == null) {
                missing += name
            } else {
                // Preserve user-given order; avoid duplicates.
                if (resolved.none { it.name == agent.name }) {
                    resolved += agent
                }
            }
        }

        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("Unknown agent(s): ${missing.joinToString(", ")}")
        }
        return resolved
    }

    private fun resolveActiveAgent(
        chatMode: ChatMode,
        requestedActiveAgent: String?,
        selectedAgents: List<AgentConfig>,
        allAgents: Map<String, AgentConfig>
    ): AgentConfig? {
        return when (chatMode) {
            ChatMode.SINGLE -> {
                when {
                    !requestedActiveAgent.isNullOrBlank() -> {
                        allAgents[requestedActiveAgent]
                            ?: throw IllegalArgumentException("Unknown agent: $requestedActiveAgent")
                    }
                    else -> selectedAgents.firstOrNull()
                }
            }
            ChatMode.COMPARE, ChatMode.AUTO -> null
        }
    }

    private suspend fun runTurn(
        session: ChatSession,
        chatMode: ChatMode,
        activeAgent: AgentConfig?,
        selectedAgents: List<AgentConfig>,
        userInput: String,
        verbose: Boolean
    ): String = coroutineScope {
        val effectivePrompt = session.buildPrompt(userInput)
        when (chatMode) {
            ChatMode.SINGLE -> {
                val target = activeAgent ?: selectedAgents.firstOrNull()
                    ?: throw IllegalStateException("No agent selected")
                val result = if (retry) {
                    agentExecutor.executeWithRetry(target, effectivePrompt, target.retryPolicy)
                } else {
                    agentExecutor.executeAgent(target, effectivePrompt)
                }
                if (!result.isSuccess) {
                    throw IllegalStateException("Agent '${target.name}' failed: ${result.error}")
                }
                result.output.orEmpty()
            }

            ChatMode.COMPARE, ChatMode.AUTO -> {
                val tasks = selectedAgents.map { agent ->
                    async {
                        val result = if (retry) {
                            agentExecutor.executeWithRetry(agent, effectivePrompt, agent.retryPolicy)
                        } else {
                            agentExecutor.executeAgent(agent, effectivePrompt)
                        }
                        agent.name to result
                    }
                }
                val results = tasks.awaitAll().map { (_, result) -> result }
                val aggregated = resultAggregator.aggregate(results)
                if (aggregated.successCount == 0) {
                    val errors = aggregated.results.joinToString("\n") {
                        "- ${it.agentName}: ${it.error ?: "unknown error"}"
                    }
                    throw IllegalStateException("All agents failed.\n$errors")
                }

                if (chatMode == ChatMode.COMPARE || showAll) {
                    return@coroutineScope aggregated.aggregatedOutput
                }

                val bestName = aggregated.analysis?.bestAgent
                val best = bestName?.let { name -> aggregated.results.firstOrNull { it.agentName == name && it.isSuccess } }
                if (best != null && best.output != null) {
                    val consensus = aggregated.analysis?.consensusScore?.let { (it * 100).toInt() }
                    val header = if (consensus != null) "best=$bestName consensus=${consensus}%" else "best=$bestName"
                    if (verbose) {
                        return@coroutineScope "[$header]\n\n${best.output}"
                    }
                    return@coroutineScope best.output
                }

                // Fallback: show successful outputs
                aggregated.aggregatedOutput
            }
        }
    }

    private suspend fun runInteractiveLoop(
        session: ChatSession,
        chatModeInitial: ChatMode,
        activeAgentInitial: AgentConfig?,
        selectedAgentsInitial: List<AgentConfig>,
        allAgents: Map<String, AgentConfig>,
        transcript: ChatTranscriptWriter,
        headerLines: List<String>
    ) {
        var chatMode = chatModeInitial
        var selectedAgents = selectedAgentsInitial
        var activeAgent: AgentConfig? = activeAgentInitial

        terminal.println()
        terminal.println(bold("◎ Cotor Interactive"))
        terminal.println(dim("Type ':help' for commands, ':exit' to quit."))
        terminal.println(dim("Transcript: ${transcript.ensureDir()}"))
        terminal.println()

        var endedByEof = false

        while (true) {
            terminal.print(bold(cyan("you> ")) )
            val line = readLine()
            if (line == null) {
                endedByEof = true
                break
            }
            val input = line.trimEnd()
            if (input.isBlank()) continue

            if (input.startsWith(":")) {
                val cmd = input.removePrefix(":").trim()
                when {
                    cmd.equals("exit", true) || cmd.equals("quit", true) -> break
                    cmd.equals("help", true) -> printHelp()
                    cmd.equals("agents", true) -> printAgents(allAgents)
                    cmd.equals("clear", true) -> {
                        session.clear()
                        terminal.println(dim("Cleared session history."))
                    }
                    cmd.startsWith("mode ", true) -> {
                        val value = cmd.substringAfter("mode ").trim()
                        chatMode = ChatMode.parse(value)
                        activeAgent = if (chatMode == ChatMode.SINGLE) (activeAgent ?: selectedAgents.firstOrNull()) else null
                        terminal.println(dim("Mode set to $chatMode"))
                    }
                    cmd.startsWith("use ", true) -> {
                        val name = cmd.substringAfter("use ").trim()
                        val resolved = allAgents[name]
                            ?: run {
                                terminal.println(red("Unknown agent: $name"))
                                null
                            }
                        if (resolved != null) {
                            activeAgent = resolved
                            chatMode = ChatMode.SINGLE
                            terminal.println(dim("Using agent '${resolved.name}' (mode=SINGLE)"))
                        }
                    }
                    cmd.startsWith("include ", true) -> {
                        val list = cmd.substringAfter("include ").trim()
                        val next = parseAgentsOption(list, allAgents.values.toList())
                        selectedAgents = next
                        if (chatMode == ChatMode.SINGLE) {
                            activeAgent = activeAgent?.let { current -> next.firstOrNull { it.name == current.name } } ?: next.firstOrNull()
                        }
                        terminal.println(dim("Agents set: ${selectedAgents.joinToString(", ") { it.name }}"))
                    }
                    cmd.equals("save", true) -> {
                        transcript.writeMarkdown(session, headerLines + "Mode: $chatMode")
                        transcript.writeRawText(session)
                        terminal.println(green("Saved transcript to ${transcript.ensureDir()}"))
                    }
                    else -> terminal.println(dim("Unknown command. Try ':help'"))
                }
                continue
            }

            try {
                // Only add to session after we have a response; this keeps history consistent when a turn errors.
                val response = runTurn(
                    session = session,
                    chatMode = chatMode,
                    activeAgent = activeAgent,
                    selectedAgents = selectedAgents,
                    userInput = input,
                    verbose = true
                )
                session.addUser(input)
                session.addAssistant(response)

                terminal.println(bold(green("cotor>")))
                terminal.println(response.trimEnd())
                terminal.println()

                // Persist after each successful turn.
                transcript.writeMarkdown(session, headerLines + "Mode: $chatMode")
                transcript.writeRawText(session)
            } catch (e: Exception) {
                terminal.println(red("Error: ${e.message ?: "unknown error"}"))
                terminal.println(dim("Hint: use ':agents' to list, ':mode auto|compare|single', ':use <name>'"))
            }
        }

        transcript.writeMarkdown(session, headerLines + "Mode: $chatMode")
        transcript.writeRawText(session)
        if (endedByEof) {
            terminal.println(dim("Input stream closed (EOF). Exiting interactive mode."))
        }
        terminal.println(dim("Bye. Saved transcript to ${transcript.ensureDir()}"))
    }

    private fun printHelp() {
        terminal.println()
        terminal.println(bold("Commands"))
        terminal.println("  :help                 Show this help")
        terminal.println("  :agents               List configured agents")
        terminal.println("  :mode auto|compare|single   Set chat mode")
        terminal.println("  :use <agent>           Use a single agent (sets mode=single)")
        terminal.println("  :include a,b,c         Set agent set for compare/auto")
        terminal.println("  :clear                Clear session history")
        terminal.println("  :save                 Save transcript now")
        terminal.println("  :exit                 Quit")
        terminal.println()
    }

    private fun printAgents(allAgents: Map<String, AgentConfig>) {
        terminal.println()
        terminal.println(bold("Configured Agents (${allAgents.size})"))
        allAgents.values.sortedBy { it.name }.forEach { a ->
            val tags = if (a.tags.isEmpty()) "" else dim(" tags=" + a.tags.joinToString(","))
            terminal.println("  - ${cyan(a.name)}${tags}")
        }
        terminal.println()
    }

    private fun defaultSaveDir(): java.nio.file.Path {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return Path(".cotor").resolve("interactive").resolve(ts)
    }
}
