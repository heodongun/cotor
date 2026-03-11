package com.cotor.presentation.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class AgentCommandTest : FunSpec({
    val createdRoots = CopyOnWriteArrayList<java.nio.file.Path>()

    test("agent add with --local writes preset yaml under .cotor/agents") {
        val root = Path("build/tmp/agent-add-${System.currentTimeMillis()}")
        createdRoots.add(root)
        root.createDirectories()
        val config = root.resolve("cotor.yaml")
        config.writeText("version: \"1.0\"\nagents: []\n")

        val result = AgentCommand().test("add gemini --config $config --local --yes")

        result.statusCode shouldBe 0
        val added = root.resolve(".cotor/agents/gemini.yaml")
        added.exists() shouldBe true
        added.readText() shouldContain "pluginClass: com.cotor.data.plugin.GeminiPlugin"
        added.readText() shouldContain "model: \"gemini-3.0-flash\""
    }

    test("agent add writes globally by default") {
        val root = Path("build/tmp/agent-add-global-${System.currentTimeMillis()}")
        createdRoots.add(root)
        root.createDirectories()
        val fakeHome = root.resolve("home")
        fakeHome.createDirectories()
        val config = root.resolve("cotor.yaml")
        config.writeText("version: \"1.0\"\nagents: []\n")

        val result = AgentCommand { fakeHome }.test("add copilot --config $config --yes")

        result.statusCode shouldBe 0
        val added = fakeHome.resolve(".cotor/agents/copilot.yaml")
        added.exists() shouldBe true
        added.readText() shouldContain "pluginClass: com.cotor.data.plugin.CopilotPlugin"
    }

    test("agent list shows merged agents") {
        val root = Path("build/tmp/agent-list-${System.currentTimeMillis()}")
        createdRoots.add(root)
        root.createDirectories()
        val config = root.resolve("cotor.yaml")
        config.writeText(
            """
            version: "1.0"
            agents:
              - name: base-agent
                pluginClass: com.cotor.data.plugin.EchoPlugin
            """.trimIndent()
        )
        val overrideDir = root.resolve(".cotor/agents")
        overrideDir.createDirectories()
        overrideDir.resolve("gemini.yaml").writeText(
            """
            agents:
              - name: gemini
                pluginClass: com.cotor.data.plugin.GeminiPlugin
                timeout: 60000
            """.trimIndent()
        )

        val result = AgentCommand().test("list --config $config")

        result.statusCode shouldBe 0
        result.output shouldContain "base-agent"
        result.output shouldContain "gemini"
    }

    test("agent add qwen writes command plugin parameters") {
        val root = Path("build/tmp/agent-qwen-${System.currentTimeMillis()}")
        createdRoots.add(root)
        root.createDirectories()
        val config = root.resolve("cotor.yaml")
        config.writeText("version: \"1.0\"\nagents: []\n")

        val result = AgentCommand().test("add qwen --config $config --local --yes")

        result.statusCode shouldBe 0
        val added = root.resolve(".cotor/agents/qwen.yaml")
        added.exists() shouldBe true
        added.readText() shouldContain "pluginClass: com.cotor.data.plugin.CommandPlugin"
        added.readText() shouldContain "model: \"qwen3-coder\""
        added.readText() shouldContain "argvJson:"
    }


    test("agent add qa writes QA verification plugin without model parameters") {
        val root = Path("build/tmp/agent-qa-${System.currentTimeMillis()}")
        createdRoots.add(root)
        root.createDirectories()
        val config = root.resolve("cotor.yaml")
        config.writeText("version: \"1.0\"\nagents: []\n")

        val result = AgentCommand().test("add qa --config $config --local --yes")

        result.statusCode shouldBe 0
        val added = root.resolve(".cotor/agents/qa.yaml")
        added.exists() shouldBe true
        added.readText() shouldContain "pluginClass: com.cotor.data.plugin.QaVerificationPlugin"
        added.readText().contains("parameters:") shouldBe false
    }

    test("agent add codex uses updated default model") {
        val root = Path("build/tmp/agent-codex-${System.currentTimeMillis()}")
        createdRoots.add(root)
        root.createDirectories()
        val config = root.resolve("cotor.yaml")
        config.writeText("version: \"1.0\"\nagents: []\n")

        val result = AgentCommand().test("add codex --config $config --local --yes")

        result.statusCode shouldBe 0
        val added = root.resolve(".cotor/agents/codex.yaml")
        added.exists() shouldBe true
        added.readText() shouldContain "model: \"gpt-5.3-codex-spark\""
    }

    afterSpec {
        createdRoots.forEach { root ->
            if (Files.exists(root)) {
                Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
})
