package com.cotor.chat

/**
 * File overview for ChatSessionTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around chat session test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ChatSessionTest : FunSpec({
    test("buildPrompt without context returns raw input") {
        val session = ChatSession(includeContext = false, maxHistoryMessages = 10)
        session.addUser("hi")
        session.addAssistant("hello")

        session.buildPrompt("ping") shouldBe "ping"
    }

    test("buildPrompt with context includes recent history") {
        val session = ChatSession(includeContext = true, maxHistoryMessages = 2)
        session.addUser("u1")
        session.addAssistant("a1")
        session.addUser("u2")
        session.addAssistant("a2")
        session.addUser("u3")

        val prompt = session.buildPrompt("next")
        prompt.shouldContain("Conversation so far:")
        prompt.shouldContain("User: u3")
        prompt.shouldContain("Assistant: a2")
        prompt.shouldContain("User: next")
        prompt.shouldContain("Assistant:")
        // Oldest messages outside the maxHistory window should not appear
        prompt.shouldNotContain("User: u1")
        prompt.shouldNotContain("Assistant: a1")
    }

    test("buildPrompt includes bootstrap and memory contexts") {
        val session = ChatSession(includeContext = true, maxHistoryMessages = 5)
        val prompt = session.buildPrompt(
            currentUserInput = "status?",
            bootstrapContext = "AGENTS: keep code clean",
            memoryContext = listOf("지난번에는 retry를 켰다")
        )

        prompt.shouldContain("Workspace bootstrap context:")
        prompt.shouldContain("AGENTS: keep code clean")
        prompt.shouldContain("Relevant long-term memory:")
        prompt.shouldContain("- 지난번에는 retry를 켰다")
    }

    test("compactHistory preserves head and tail with summary in middle") {
        val session = ChatSession(includeContext = true, maxHistoryMessages = 100)
        repeat(20) { i ->
            session.addUser("u$i")
            session.addAssistant("a$i")
        }

        session.compactHistory(keepHead = 2, keepTail = 4)
        val snapshot = session.snapshot()

        snapshot.shouldHaveSize(7)
        snapshot.first().content shouldBe "u0"
        snapshot[1].content shouldBe "a0"
        snapshot[2].content.shouldContain("[Summary of earlier conversation]")
        snapshot.takeLast(4).map { it.content } shouldBe listOf("u18", "a18", "u19", "a19")
    }

    test("buildPrompt prunes oversized older assistant outputs") {
        val session = ChatSession(includeContext = true, maxHistoryMessages = 10, toolOutputPruneChars = 20)
        session.addUser("show details")
        session.addAssistant("x".repeat(100))
        session.addUser("ok and now?")
        session.addAssistant("short")

        val prompt = session.buildPrompt("next")
        prompt.shouldContain("cache-aware-pruned assistant output")
    }

    test("buildPrompt stays bounded when bootstrap context is larger than the prompt budget") {
        val session = ChatSession(includeContext = true, maxHistoryMessages = 50, maxPromptChars = 600)
        repeat(20) { index ->
            session.addUser("user-message-$index " + "u".repeat(120))
            session.addAssistant("assistant-message-$index " + "a".repeat(140))
        }

        val prompt = session.buildPrompt(
            currentUserInput = "status?",
            bootstrapContext = "B".repeat(5_000),
            memoryContext = listOf("M".repeat(1_000), "N".repeat(1_000))
        )

        (prompt.length <= 600) shouldBe true
        prompt.shouldContain("User: status?")
        prompt.shouldContain("Assistant:")
    }
})
