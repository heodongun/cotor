package com.cotor.presentation.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.Path

class DesktopLifecycleCommandTest : FunSpec({
    test("install runs desktop install script on macOS") {
        val root = Path("/tmp/cotor-install-test")
        val calls = mutableListOf<Pair<java.nio.file.Path, String>>()
        val command = InstallCommand(
            scriptRunner = { projectRoot, scriptName ->
                calls += projectRoot to scriptName
                DesktopScriptResult(exitCode = 0, output = "installed\n")
            },
            projectRootProvider = { root },
            osNameProvider = { "Mac OS X" }
        )

        val result = command.test("")

        result.statusCode shouldBe 0
        result.output shouldContain "installed"
        calls shouldBe listOf(root to "install-desktop-app.sh")
    }

    test("update runs desktop update script on macOS") {
        val root = Path("/tmp/cotor-update-test")
        val calls = mutableListOf<Pair<java.nio.file.Path, String>>()
        val command = UpdateCommand(
            scriptRunner = { projectRoot, scriptName ->
                calls += projectRoot to scriptName
                DesktopScriptResult(exitCode = 0, output = "updated\n")
            },
            projectRootProvider = { root },
            osNameProvider = { "Mac OS X" }
        )

        val result = command.test("")

        result.statusCode shouldBe 0
        result.output shouldContain "updated"
        calls shouldBe listOf(root to "update-desktop-app.sh")
    }

    test("delete runs desktop delete script on macOS") {
        val root = Path("/tmp/cotor-delete-test")
        val calls = mutableListOf<Pair<java.nio.file.Path, String>>()
        val command = DeleteCommand(
            scriptRunner = { projectRoot, scriptName ->
                calls += projectRoot to scriptName
                DesktopScriptResult(exitCode = 0, output = "deleted\n")
            },
            projectRootProvider = { root },
            osNameProvider = { "Mac OS X" }
        )

        val result = command.test("")

        result.statusCode shouldBe 0
        result.output shouldContain "deleted"
        calls shouldBe listOf(root to "delete-desktop-app.sh")
    }

    test("desktop lifecycle commands fail outside macOS") {
        val command = InstallCommand(
            scriptRunner = { _, _ -> error("should not run") },
            projectRootProvider = { Path("/tmp/cotor-non-mac") },
            osNameProvider = { "Linux" }
        )

        val result = command.test("")

        result.statusCode shouldBe 1
        result.output shouldContain "supports macOS only"
    }
})
