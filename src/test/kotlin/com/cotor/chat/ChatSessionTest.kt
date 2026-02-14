package com.cotor.chat

import io.kotest.core.spec.style.FunSpec
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
})

