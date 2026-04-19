package com.cotor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class CodexAppServerManagerTest : FunSpec({
    test("prepareManagedLaunch redirects stdout and stderr to files") {
        val manager = CodexAppServerManager()
        val executable = Files.createTempDirectory("codex-manager-test-bin").resolve("codex")
        Files.writeString(executable, "#!/bin/sh\nexit 0\n")
        executable.toFile().setExecutable(true)
        val workingDirectory = Files.createTempDirectory("codex-manager-test-workdir")
        val config = BackendConnectionConfig(
            kind = ExecutionBackendKind.CODEX_APP_SERVER,
            command = executable.toString(),
            args = listOf("serve", "--port", "{port}"),
            workingDirectory = workingDirectory.toString()
        )

        val prepared = manager.prepareManagedLaunch(
            command = listOf(executable.toString(), "serve", "--port", "8787"),
            executable = executable,
            config = config
        )

        prepared.builder.directory().toPath() shouldBe workingDirectory
        prepared.builder.redirectOutput().type() shouldBe ProcessBuilder.Redirect.Type.WRITE
        prepared.builder.redirectError().type() shouldBe ProcessBuilder.Redirect.Type.WRITE
        prepared.builder.redirectOutput().file().parentFile.toPath() shouldBe prepared.logDir
        prepared.builder.redirectError().file().parentFile.toPath() shouldBe prepared.logDir
    }
})
