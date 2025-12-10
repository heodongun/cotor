package com.cotor.presentation.cli

import com.cotor.data.config.ConfigRepository
import com.cotor.error.ErrorMessages
import com.cotor.error.UserFriendlyError
import com.cotor.presentation.DiagramGenerator
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path
import kotlin.io.path.exists

class ExplainCommand : CliktCommand(
    name = "explain",
    help = "Explain the execution plan of a pipeline"
), KoinComponent {
    private val configRepository: ConfigRepository by inject()

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    private val pipelineName by argument("pipeline", help = "Name of pipeline to explain").optional()

    override fun run() = runBlocking {
        try {
            if (pipelineName == null) {
                echo("Usage: cotor explain <pipeline> [--config <path>]")
                echo()
                echo("Run 'cotor explain --help' for more information.")
                return@runBlocking
            }

            if (!configPath.exists()) {
                throw ErrorMessages.configNotFound(configPath.toString())
            }

            val config = configRepository.loadConfig(configPath)
            val pipeline = config.pipelines.find { it.name == pipelineName!! }
                ?: throw ErrorMessages.pipelineNotFound(
                    pipelineName!!,
                    config.pipelines.map { it.name }
                )

            val diagram = DiagramGenerator.generate(pipeline)
            echo(diagram)

        } catch (e: UserFriendlyError) {
            echo(e.message, err = true)
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
            e.printStackTrace()
        }
    }
}
