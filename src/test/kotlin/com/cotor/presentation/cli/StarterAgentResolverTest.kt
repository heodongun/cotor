package com.cotor.presentation.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Paths

class StarterAgentResolverTest : FunSpec({
    test("prefers codex only when codex is ready") {
        val starter = resolveStarterAgentSpec(
            hasCommand = { it == "codex" || it == "gemini" },
            codexReady = { true },
            hasOpenAiApiKey = false
        )

        starter.name shouldBe "codex"
        starter.pluginClass shouldBe "com.cotor.data.plugin.CodexPlugin"
    }

    test("falls back to gemini when codex binary exists but login is not ready") {
        val starter = resolveStarterAgentSpec(
            hasCommand = { it == "codex" || it == "gemini" },
            codexReady = { false },
            geminiReady = { true },
            hasOpenAiApiKey = false
        )

        starter.name shouldBe "gemini"
        starter.pluginClass shouldBe "com.cotor.data.plugin.GeminiPlugin"
    }

    test("falls back to echo when gemini is installed but not configured") {
        val starter = resolveStarterAgentSpec(
            hasCommand = { it == "gemini" },
            codexReady = { false },
            geminiReady = { false },
            claudeReady = { false },
            hasOpenAiApiKey = false
        )

        starter.name shouldBe "example-agent"
        starter.pluginClass shouldBe "com.cotor.data.plugin.EchoPlugin"
    }

    test("falls back to echo when no authenticated or configured AI path exists") {
        val starter = resolveStarterAgentSpec(
            hasCommand = { false },
            codexReady = { false },
            hasOpenAiApiKey = false
        )

        starter.name shouldBe "example-agent"
        starter.pluginClass shouldBe "com.cotor.data.plugin.EchoPlugin"
    }

    test("real-ai starter readiness ignores codex when it is not ready") {
        canUseRealAiStarter(
            hasCommand = { it == "codex" },
            codexReady = { false },
            geminiReady = { false },
            claudeReady = { false },
            hasOpenAiApiKey = false
        ) shouldBe false
    }

    test("gemini readiness requires credentials or saved auth state") {
        val home = Files.createTempDirectory("cotor-starter-gemini-home")
        isGeminiReadyForStarter(
            hasCommand = { it == "gemini" },
            environment = emptyMap(),
            userHome = home
        ) shouldBe false

        home.resolve(".gemini").toFile().mkdirs()
        home.resolve(".gemini").resolve("oauth_creds.json").toFile().writeText("{}")

        isGeminiReadyForStarter(
            hasCommand = { it == "gemini" },
            environment = emptyMap(),
            userHome = home
        ) shouldBe true
    }

    test("claude readiness accepts env key and persisted auth markers") {
        val home = Files.createTempDirectory("cotor-starter-claude-home")
        isClaudeReadyForStarter(
            hasCommand = { it == "claude" },
            environment = emptyMap(),
            userHome = home
        ) shouldBe false

        isClaudeReadyForStarter(
            hasCommand = { it == "claude" },
            environment = mapOf("ANTHROPIC_API_KEY" to "test-key"),
            userHome = home
        ) shouldBe true

        val persistedHome = Files.createTempDirectory("cotor-starter-claude-persisted")
        persistedHome.resolve(".claude").toFile().mkdirs()
        persistedHome.resolve(".claude").resolve("settings.json").toFile().writeText("{}")

        isClaudeReadyForStarter(
            hasCommand = { it == "claude" },
            environment = emptyMap(),
            userHome = persistedHome
        ) shouldBe true
    }

    test("starter allowed directories include common user-local bins") {
        val yaml = starterAllowedDirectoriesYaml()

        yaml.shouldContain("/usr/local/bin")
        yaml.shouldContain("/opt/homebrew/bin")
        yaml.shouldContain(".local/bin")
        yaml.shouldContain("/bin")
    }

    test("packaged interactive config defaults to home config path instead of cwd") {
        val tempCwd = Files.createTempDirectory("cotor-packaged-cwd")
        val tempHome = Files.createTempDirectory("cotor-packaged-home")
        val environment = mapOf(
            "COTOR_INSTALL_KIND" to "packaged",
            "HOME" to tempHome.toString()
        )
        val resolved = resolveInteractiveConfigPath(
            requestedPath = Paths.get("cotor.yaml"),
            environment = environment,
            cwd = tempCwd
        )

        resolved shouldBe defaultInteractiveConfigPath(environment)
    }

    test("packaged interactive save dir follows the home-backed config path") {
        val tempHome = Files.createTempDirectory("cotor-packaged-home-save")
        val environment = mapOf(
            "COTOR_INSTALL_KIND" to "packaged",
            "HOME" to tempHome.toString()
        )

        defaultInteractiveSaveDir(defaultInteractiveConfigPath(environment), environment) shouldBe
            tempHome.resolve(".cotor").resolve("interactive").resolve("default")
    }
})
