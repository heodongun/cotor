package com.cotor.presentation.cli

/**
 * File overview for InteractiveCommand.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around interactive command so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.chat.ChatMode
import com.cotor.chat.ChatSession
import com.cotor.chat.ChatTranscriptWriter
import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentConfig
import com.cotor.model.AgentResult
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.PrintStream
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Chat with the master agent in an interactive (TUI-like) loop.
 *
 * This intentionally avoids YAML pipelines; it dispatches directly to configured agents.
 */
class InteractiveCommand :
    CliktCommand(
        name = "interactive",
        help = "Chat with the master agent via a terminal UI (no YAML pipeline required)"
    ),
    KoinComponent {
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

    private val bootstrapMaxChars by option(
        "--bootstrap-max-chars",
        help = "Max chars loaded from workspace bootstrap docs (AGENTS/README)"
    ).int().default(20_000)

    private val memorySearchLimit by option(
        "--memory-search-limit",
        help = "How many MEMORY.md entries to inject per turn"
    ).int().default(3)

    private val showAll by option(
        "--show-all",
        help = "In auto mode, show all agent outputs instead of only the selected best output"
    ).flag(default = false)

    private val retry by option(
        "--retry",
        help = "Enable agent-level retries using each agent's configured retryPolicy"
    ).flag(default = false)

    private val startupLogo = listOf(
        " ██████╗ ██████╗ ████████╗ ██████╗ ██████╗",
        "██╔════╝██╔═══██╗╚══██╔══╝██╔═══██╗██╔══██╗",
        "██║     ██║   ██║   ██║   ██║   ██║██████╔╝",
        "██║     ██║   ██║   ██║   ██║   ██║██╔══██╗",
        "╚██████╗╚██████╔╝   ██║   ╚██████╔╝██║  ██║",
        " ╚═════╝ ╚═════╝    ╚═╝    ╚═════╝ ╚═╝  ╚═╝"
    )

    override fun run() = runBlocking {
        val resolvedConfigPath = resolveInteractiveConfigPath(configPath)
        var bootstrapLoadExact = false

        if (resolvedConfigPath != configPath) {
            terminal.println(dim("Using interactive config: $resolvedConfigPath"))
        }

        if (!resolvedConfigPath.exists()) {
            terminal.println(yellow("⚠ cotor.yaml not found at: $resolvedConfigPath"))
            terminal.println(dim("Creating a starter config automatically for interactive mode..."))
            writeStarterConfig(resolvedConfigPath)
            bootstrapLoadExact = true
            terminal.println(green("✓ Created starter config: $resolvedConfigPath"))
            terminal.println()
        }

        var config = loadInteractiveConfig(resolvedConfigPath, bootstrapLoadExact)

        if (shouldRefreshStarterConfig(config)) {
            terminal.println(yellow("⚠ 현재 설정이 Echo starter(example-agent)라 대화형 답변이 제한됩니다."))
            terminal.println(dim("   감지된 AI CLI 기반 starter로 자동 교체합니다..."))
            writeStarterConfig(resolvedConfigPath)
            config = loadInteractiveConfig(resolvedConfigPath, exact = true)
            terminal.println(green("✓ starter config를 AI 우선 설정으로 갱신했습니다."))
            terminal.println()
        }

        if (config.agents.isEmpty()) {
            terminal.println(red("No agents configured. Add agents to your config first (cotor init)."))
            return@runBlocking
        }

        config.agents.forEach { agentRegistry.registerAgent(it) }
        val allAgents = config.agents.associateBy { it.name }

        val selectedAgents = parseAgentsOption(agents, allAgents.values.toList())
        val chatMode = resolveInitialChatMode(mode)
        val activeAgent = resolveActiveAgent(chatMode, agent, selectedAgents, allAgents)

        val outputDir = (saveDir ?: defaultSaveDir(resolvedConfigPath)).also { it.createDirectories() }
        val transcript = ChatTranscriptWriter(outputDir)
        val sessionLogger = InteractiveSessionLogger(outputDir)
        val bootstrapContext = loadBootstrapContext(bootstrapMaxChars)
        val session = ChatSession(
            includeContext = !noContext,
            maxHistoryMessages = maxHistory,
            initialMessages = transcript.loadSessionMessages()
        )

        val headerLines = buildList {
            add("Config: $resolvedConfigPath")
            add("Mode: $chatMode")
            add("Agents: ${selectedAgents.joinToString(", ") { it.name }}")
            if (activeAgent != null) add("ActiveAgent: ${activeAgent.name}")
        }

        sessionLogger.logSessionStart(resolvedConfigPath, chatMode, selectedAgents, activeAgent)
        var sessionEndReason = "completed"

        try {
            when {
                prompt != null -> {
                    val outcome = executeLoggedTurn(
                        session = session,
                        chatMode = chatMode,
                        activeAgent = activeAgent,
                        selectedAgents = selectedAgents,
                        userInput = prompt!!,
                        verbose = false,
                        bootstrapContext = bootstrapContext,
                        memoryContext = transcript.searchMemory(prompt!!, memorySearchLimit),
                        sessionLogger = sessionLogger
                    )
                    session.addUser(prompt!!)
                    session.addAssistant(outcome.response)
                    transcript.writeMarkdown(session, headerLines)
                    transcript.writeRawText(session)
                    transcript.writeJsonl(session)
                    transcript.flushMemoryIfNeeded(session)
                    echo(outcome.response)
                    sessionEndReason = "prompt_completed"
                }

                promptFile != null -> {
                    if (!promptFile!!.exists()) {
                        throw IllegalArgumentException("Prompt file not found: $promptFile")
                    }
                    val prompts = promptFile!!.readLines().map { it.trimEnd() }.filter { it.isNotBlank() }
                    val outputs = mutableListOf<String>()
                    for (line in prompts) {
                        val outcome = executeLoggedTurn(
                            session = session,
                            chatMode = chatMode,
                            activeAgent = activeAgent,
                            selectedAgents = selectedAgents,
                            userInput = line,
                            verbose = false,
                            bootstrapContext = bootstrapContext,
                            memoryContext = transcript.searchMemory(line, memorySearchLimit),
                            sessionLogger = sessionLogger
                        )
                        session.addUser(line)
                        session.addAssistant(outcome.response)
                        outputs += outcome.response
                    }
                    transcript.writeMarkdown(session, headerLines)
                    transcript.writeRawText(session)
                    transcript.writeJsonl(session)
                    transcript.flushMemoryIfNeeded(session)
                    echo(outputs.joinToString("\n\n"))
                    sessionEndReason = "prompt_file_completed"
                }

                else -> {
                    sessionEndReason = runInteractiveLoop(
                        session = session,
                        chatModeInitial = chatMode,
                        activeAgentInitial = activeAgent,
                        selectedAgentsInitial = selectedAgents,
                        allAgents = allAgents,
                        transcript = transcript,
                        sessionLogger = sessionLogger,
                        headerLines = headerLines,
                        bootstrapContext = bootstrapContext
                    )
                }
            }
        } catch (e: Exception) {
            sessionEndReason = "failed: ${e.message ?: e::class.java.simpleName}"
            throw e
        } finally {
            sessionLogger.logSessionEnd(sessionEndReason)
        }
    }

    private fun resolveInitialChatMode(requestedMode: String?): ChatMode {
        return requestedMode?.let(ChatMode::parse) ?: ChatMode.SINGLE
    }

    private suspend fun executeLoggedTurn(
        session: ChatSession,
        chatMode: ChatMode,
        activeAgent: AgentConfig?,
        selectedAgents: List<AgentConfig>,
        userInput: String,
        verbose: Boolean,
        bootstrapContext: String,
        memoryContext: List<String>,
        sessionLogger: InteractiveSessionLogger
    ): InteractiveTurnOutcome {
        val turnContext = sessionLogger.startTurn(
            chatMode = chatMode,
            selectedAgents = selectedAgents,
            activeAgent = activeAgent,
            userInput = userInput
        )

        return try {
            val outcome = runTurnWithSpinner(
                session = session,
                chatMode = chatMode,
                activeAgent = activeAgent,
                selectedAgents = selectedAgents,
                userInput = userInput,
                verbose = verbose,
                bootstrapContext = bootstrapContext,
                memoryContext = memoryContext
            )
            sessionLogger.logTurnSuccess(turnContext, outcome)
            outcome
        } catch (e: Exception) {
            sessionLogger.logTurnFailure(turnContext, e, extractAgentSummaries(e))
            throw e
        }
    }

    private fun extractAgentSummaries(error: Throwable): List<InteractiveAgentSummary> {
        return when (error) {
            is InteractiveTurnException -> error.agentSummaries
            else -> emptyList()
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
                    else -> preferredSingleAgent(selectedAgents)
                }
            }
            ChatMode.COMPARE, ChatMode.AUTO -> null
        }
    }

    private fun preferredSingleAgent(selectedAgents: List<AgentConfig>): AgentConfig? {
        return selectedAgents.firstOrNull { it.name.equals("codex", ignoreCase = true) }
            ?: selectedAgents.firstOrNull { it.tags.contains("starter") }
            ?: selectedAgents.firstOrNull()
    }

    private suspend fun runTurnWithSpinner(
        session: ChatSession,
        chatMode: ChatMode,
        activeAgent: AgentConfig?,
        selectedAgents: List<AgentConfig>,
        userInput: String,
        verbose: Boolean,
        bootstrapContext: String,
        memoryContext: List<String>
    ): InteractiveTurnOutcome = coroutineScope {
        // The native desktop shell already renders its own terminal surface and
        // polls incrementally, so the CLI spinner just floods the PTY with redraw
        // frames and makes the embedded TUI look broken even when input works.
        if (System.getenv("COTOR_DESKTOP_TUI") == "1") {
            return@coroutineScope runTurn(
                session = session,
                chatMode = chatMode,
                activeAgent = activeAgent,
                selectedAgents = selectedAgents,
                userInput = userInput,
                verbose = verbose,
                bootstrapContext = bootstrapContext,
                memoryContext = memoryContext
            )
        }

        val spinner = launch {
            val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
            var i = 0
            while (isActive) {
                terminal.print("\r${dim("thinking") } ${frames[i % frames.size]}")
                i++
                delay(90)
            }
        }

        try {
            runTurn(
                session = session,
                chatMode = chatMode,
                activeAgent = activeAgent,
                selectedAgents = selectedAgents,
                userInput = userInput,
                verbose = verbose,
                bootstrapContext = bootstrapContext,
                memoryContext = memoryContext
            )
        } finally {
            spinner.cancel()
            terminal.print("\r\u001B[2K")
        }
    }

    private suspend fun runTurn(
        session: ChatSession,
        chatMode: ChatMode,
        activeAgent: AgentConfig?,
        selectedAgents: List<AgentConfig>,
        userInput: String,
        verbose: Boolean,
        bootstrapContext: String,
        memoryContext: List<String>
    ): InteractiveTurnOutcome = coroutineScope {
        val effectivePrompt = session.buildPrompt(
            currentUserInput = userInput,
            bootstrapContext = bootstrapContext,
            memoryContext = memoryContext
        )
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
                    throw InteractiveTurnException(
                        "Agent '${target.name}' failed: ${result.error}",
                        agentSummaries = listOf(result.toInteractiveSummary())
                    )
                }
                InteractiveTurnOutcome(
                    response = result.output.orEmpty(),
                    agentSummaries = listOf(result.toInteractiveSummary())
                )
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
                    throw InteractiveTurnException(
                        "All agents failed.\n$errors",
                        agentSummaries = aggregated.results.map { it.toInteractiveSummary() }
                    )
                }

                val response = when {
                    chatMode == ChatMode.COMPARE || showAll -> aggregated.aggregatedOutput
                    else -> {
                        val bestName = aggregated.analysis?.bestAgent
                        val best = bestName?.let { name -> aggregated.results.firstOrNull { it.agentName == name && it.isSuccess } }
                        if (best != null && best.output != null) {
                            val consensus = aggregated.analysis?.consensusScore?.let { (it * 100).toInt() }
                            val header = if (consensus != null) "best=$bestName consensus=$consensus%" else "best=$bestName"
                            if (verbose) {
                                "[$header]\n\n${best.output}"
                            } else {
                                best.output
                            }
                        } else {
                            aggregated.aggregatedOutput
                        }
                    }
                }

                InteractiveTurnOutcome(
                    response = response,
                    agentSummaries = aggregated.results.map { it.toInteractiveSummary() }
                )
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
        sessionLogger: InteractiveSessionLogger,
        headerLines: List<String>,
        bootstrapContext: String
    ): String {
        var chatMode = chatModeInitial
        var selectedAgents = selectedAgentsInitial
        var activeAgent: AgentConfig? = activeAgentInitial

        terminal.println()
        startupLogo.forEach { terminal.println(cyan(bold(it))) }
        terminal.println()
        terminal.println(bold("◎ Cotor Interactive"))
        terminal.println(dim("Type ':help' for commands, ':exit' to quit."))
        terminal.println(dim("Mode: $chatMode"))
        if (chatMode == ChatMode.SINGLE) {
            terminal.println(dim("Active agent: ${activeAgent?.name ?: "-"}"))
        } else {
            terminal.println(dim("Agents: ${selectedAgents.joinToString(", ") { it.name }}"))
        }
        terminal.println(dim("Transcript: ${transcript.ensureDir()}"))
        terminal.println(dim("Debug log: ${sessionLogger.filePath()}"))
        if (session.snapshot().isNotEmpty()) {
            terminal.println(dim("Loaded ${session.snapshot().size} messages from session.jsonl"))
        }
        terminal.println()

        var endedByEof = false

        val lineReader = buildLineReader(allAgents.keys.toList())

        while (true) {
            val line = try {
                lineReader?.readLine("you> ") ?: run {
                    printDesktopPrompt()
                    readLine()
                }
            } catch (_: UserInterruptException) {
                terminal.println()
                continue
            } catch (_: EndOfFileException) {
                null
            }

            if (line == null) {
                endedByEof = true
                break
            }
            val input = normalizeInteractiveInput(line)
            if (input.isBlank()) continue

            if (input.startsWith(":")) {
                val cmd = input.removePrefix(":").trim()
                when {
                    cmd.equals("exit", true) || cmd.equals("quit", true) -> break
                    cmd.equals("help", true) -> printHelp()
                    cmd.equals("agents", true) -> printAgents(allAgents)
                    cmd.equals("clear", true) -> {
                        session.clear()
                        transcript.clearJsonl()
                        terminal.println(dim("Cleared session history."))
                    }
                    cmd.startsWith("mode ", true) -> {
                        val value = cmd.substringAfter("mode ").trim()
                        chatMode = ChatMode.parse(value)
                        activeAgent = if (chatMode == ChatMode.SINGLE) (activeAgent ?: preferredSingleAgent(selectedAgents)) else null
                        terminal.println(dim("Mode set to $chatMode"))
                    }
                    cmd.startsWith("use ", true) || cmd.startsWith("model ", true) -> {
                        val keyword = if (cmd.startsWith("model ", true)) "model " else "use "
                        val name = cmd.substringAfter(keyword).trim()
                        val resolved = allAgents[name]
                            ?: run {
                                terminal.println(red("Unknown agent/model: $name"))
                                null
                            }
                        if (resolved != null) {
                            activeAgent = resolved
                            chatMode = ChatMode.SINGLE
                            terminal.println(dim("Using model '${resolved.name}' (mode=SINGLE)"))
                        }
                    }
                    cmd.startsWith("include ", true) -> {
                        val list = cmd.substringAfter("include ").trim()
                        val next = parseAgentsOption(list, allAgents.values.toList())
                        selectedAgents = next
                        if (chatMode == ChatMode.SINGLE) {
                            activeAgent = activeAgent?.let { current -> next.firstOrNull { it.name == current.name } }
                                ?: preferredSingleAgent(next)
                        }
                        terminal.println(dim("Agents set: ${selectedAgents.joinToString(", ") { it.name }}"))
                    }
                    cmd.equals("save", true) -> {
                        transcript.writeMarkdown(session, headerLines + "Mode: $chatMode")
                        transcript.writeRawText(session)
                        transcript.writeJsonl(session)
                        transcript.flushMemoryIfNeeded(session)
                        terminal.println(green("Saved transcript to ${transcript.ensureDir()}"))
                    }
                    else -> terminal.println(dim("Unknown command. Try ':help'"))
                }
                continue
            }

            try {
                // Only add to session after we have a response; this keeps history consistent when a turn errors.
                val outcome = executeLoggedTurn(
                    session = session,
                    chatMode = chatMode,
                    activeAgent = activeAgent,
                    selectedAgents = selectedAgents,
                    userInput = input,
                    verbose = true,
                    bootstrapContext = bootstrapContext,
                    memoryContext = transcript.searchMemory(input, memorySearchLimit),
                    sessionLogger = sessionLogger
                )
                session.addUser(input)
                session.addAssistant(outcome.response)

                terminal.println(bold(green("cotor>")))
                terminal.println(outcome.response.trimEnd())
                terminal.println()

                // Persist after each successful turn.
                transcript.writeMarkdown(session, headerLines + "Mode: $chatMode")
                transcript.writeRawText(session)
                transcript.writeJsonl(session)
                transcript.flushMemoryIfNeeded(session)
            } catch (e: Exception) {
                terminal.println(red("Error: ${e.message ?: "unknown error"}"))
                terminal.println(dim("Hint: use ':agents' to list, ':mode auto|compare|single', ':model <name>'"))
                terminal.println(dim("Details: ${sessionLogger.filePath()}"))
            }
        }

        transcript.writeMarkdown(session, headerLines + "Mode: $chatMode")
        transcript.writeRawText(session)
        transcript.writeJsonl(session)
        transcript.flushMemoryIfNeeded(session)
        if (endedByEof) {
            terminal.println(dim("Input stream closed (EOF). Exiting interactive mode."))
            return "eof"
        }
        terminal.println(dim("Bye. Saved transcript to ${transcript.ensureDir()}"))
        return "user_exit"
    }

    private fun printDesktopPrompt() {
        if (System.getenv("COTOR_DESKTOP_TUI") == "1") {
            // Keep the embedded PTY prompt plain so the terminal emulator never has
            // to reconcile ANSI-styled prompt fragments with raw line input.
            val out = PrintStream(System.out, true, Charsets.UTF_8)
            out.print("you> ")
            out.flush()
        } else {
            terminal.print(bold(cyan("you> ")))
        }
    }

    private fun normalizeInteractiveInput(line: String): String {
        return normalizeInteractiveInput(line, isDesktopTui = System.getenv("COTOR_DESKTOP_TUI") == "1")
    }

    private fun buildLineReader(agentNames: List<String>) = runCatching {
        // The desktop app already provides its own PTY surface. JLine prompt redraws
        // and cursor-control sequences look noisy there, so the embedded terminal
        // falls back to the plain line reader path for cleaner, more predictable I/O.
        if (System.getenv("COTOR_DESKTOP_TUI") == "1") {
            return@runCatching null
        }
        val jlineTerminal = TerminalBuilder.builder()
            .system(true)
            .build()
        LineReaderBuilder.builder()
            .terminal(jlineTerminal)
            .completer(InteractiveCommandCompleter { agentNames })
            .build()
    }.getOrNull()

    private fun printHelp() {
        terminal.println()
        terminal.println(bold("Commands"))
        terminal.println("  :help                 Show this help")
        terminal.println("  :agents               List configured agents")
        terminal.println("  :mode auto|compare|single   Set chat mode")
        terminal.println("  :use <agent>           Use a single agent (sets mode=single)")
        terminal.println("  :model <name>          Alias of :use (Codex/Gemini/Claude etc.)")
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
            terminal.println("  - ${cyan(a.name)}$tags")
        }
        terminal.println()
    }

    private fun loadBootstrapContext(maxChars: Int): String {
        val candidates = listOf(
            Path("AGENTS.md"),
            Path("README.md"),
            Path("README.ko.md"),
            Path("docs/README.md")
        )

        val merged = buildString {
            candidates.filter { it.exists() }.forEach { file ->
                appendLine("## $file")
                appendLine(file.readText().trim())
                appendLine()
            }
        }

        if (merged.isBlank()) return ""
        return merged.take(maxChars)
    }

    private fun defaultSaveDir(resolvedConfigPath: java.nio.file.Path): java.nio.file.Path {
        return defaultInteractiveSaveDir(resolvedConfigPath)
    }

    private suspend fun loadInteractiveConfig(path: java.nio.file.Path, exact: Boolean): com.cotor.model.CotorConfig {
        return if (exact) {
            configRepository.loadConfigExact(path)
        } else {
            configRepository.loadConfig(path)
        }
    }

    companion object {
        private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
        private val PROMPT_PREFIX_REGEX = Regex("""^\s*(?:you>\s*)+""")

        internal fun normalizeInteractiveInput(line: String, isDesktopTui: Boolean): String {
            val withoutAnsi = ANSI_ESCAPE_REGEX.replace(line, "")
                .replace("\u0000", "")
                .replace("\r", "")
                .trimEnd()

            if (!isDesktopTui) {
                return withoutAnsi
            }

            // PTY-backed desktop sessions can occasionally hand us the rendered prompt
            // prefix alongside the actual user input. Strip it defensively so command
            // lines like `:help` are never routed to an AI agent as plain text.
            return PROMPT_PREFIX_REGEX.replace(withoutAnsi, "").trimStart()
        }
    }

    private fun shouldRefreshStarterConfig(config: com.cotor.model.CotorConfig): Boolean {
        if (config.agents.size != 1) return false
        val only = config.agents.first()

        val canUseRealAi = canUseRealAiStarter()

        // Upgrade legacy echo starter when real AI is available.
        if (only.name.equals("example-agent", ignoreCase = true) && only.pluginClass.endsWith("EchoPlugin")) {
            return canUseRealAi
        }

        // Prefer codex over any starter agent once codex is actually ready.
        if (only.tags.contains("starter") && !only.name.equals("codex", ignoreCase = true) && isCodexReadyForStarter()) {
            return true
        }

        return false
    }

    private fun writeStarterConfig(path: java.nio.file.Path) {
        val starter = resolveStarterAgent()
        path.parent?.createDirectories()

        val parameterSection = if (starter.parameterBlock.isBlank()) {
            ""
        } else {
            """
    parameters:
${starter.parameterBlock.prependIndent("      ")}
""".trimEnd()
        }

        val defaultConfig = """
version: "1.0"

agents:
  - name: ${starter.name}
    pluginClass: ${starter.pluginClass}
    timeout: 60000
$parameterSection
    tags:
      - starter

pipelines:
  - name: example-pipeline
    description: "Example sequential pipeline"
    executionMode: SEQUENTIAL
    stages:
      - id: step1
        agent:
          name: ${starter.name}
        input: "Say hello in one short sentence."

security:
  useWhitelist: true
  allowedExecutables:
    - ${starter.executable}
  allowedDirectories:
${starterAllowedDirectoriesYaml()}

logging:
  level: INFO
  file: cotor.log
  format: json

performance:
  maxConcurrentAgents: 10
  coroutinePoolSize: 8
        """.trimIndent()

        path.writeText(defaultConfig)

        if (starter.pluginClass.endsWith("EchoPlugin")) {
            terminal.println(yellow("⚠ 준비된 AI starter를 찾지 못해 Echo starter를 생성했습니다."))
            terminal.println(dim("   codex login 또는 AI CLI 인증/환경 변수를 설정한 뒤 다시 실행하면 starter config를 갱신합니다."))
        } else {
            terminal.println(dim("Starter agent selected: ${starter.name} (${starter.pluginClass.substringAfterLast('.')})"))
        }
    }

    private fun resolveStarterAgent(): StarterAgentSpec = resolveStarterAgentSpec()

    private fun AgentResult.toInteractiveSummary(): InteractiveAgentSummary {
        return InteractiveAgentSummary(
            agentName = agentName,
            success = isSuccess,
            durationMs = duration,
            error = error
        )
    }
}
