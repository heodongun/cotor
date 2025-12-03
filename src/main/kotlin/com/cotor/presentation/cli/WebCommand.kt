package com.cotor.presentation.cli

import com.cotor.presentation.web.WebServer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

/**
 * Launch the browser-based pipeline editor.
 */
class WebCommand : CliktCommand(
    name = "web",
    help = "Launch the Cotor web/노코드 편집기"
) {
    private val port by option("--port", "-p", help = "Port to run the web server").int().default(8080)
    private val openBrowser by option("--open", help = "자동으로 브라우저를 엽니다").flag(default = false)

    override fun run() {
        WebServer().start(port = port, openBrowser = openBrowser)
    }
}
