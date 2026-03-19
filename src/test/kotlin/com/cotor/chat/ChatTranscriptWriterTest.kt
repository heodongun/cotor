package com.cotor.chat

/**
 * File overview for ChatTranscriptWriterTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around chat transcript writer test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText

class ChatTranscriptWriterTest : FunSpec({
    test("writeJsonl and loadSessionMessages round-trip chat history") {
        val dir = Files.createTempDirectory("cotor-chat-test")
        val writer = ChatTranscriptWriter(dir)
        val session = ChatSession(includeContext = true, maxHistoryMessages = 10)

        session.addUser("hello")
        session.addAssistant("world")
        writer.writeJsonl(session)

        val loaded = writer.loadSessionMessages()
        loaded.shouldHaveSize(2)
        loaded[0].role shouldBe ChatRole.USER
        loaded[0].content shouldBe "hello"
        loaded[1].role shouldBe ChatRole.ASSISTANT
        loaded[1].content shouldBe "world"
    }

    test("clearJsonl removes persisted history") {
        val dir = Files.createTempDirectory("cotor-chat-test-clear")
        val writer = ChatTranscriptWriter(dir)
        val session = ChatSession(includeContext = true, maxHistoryMessages = 10)
        session.addUser("keep?")
        writer.writeJsonl(session)

        writer.clearJsonl()

        writer.loadSessionMessages() shouldBe emptyList()
    }

    test("flushMemoryIfNeeded writes MEMORY and compacts session") {
        val dir = Files.createTempDirectory("cotor-chat-memory")
        val writer = ChatTranscriptWriter(dir)
        val session = ChatSession(includeContext = true, maxHistoryMessages = 200)

        repeat(45) { i ->
            session.addUser("u$i")
            session.addAssistant("a$i")
        }

        writer.flushMemoryIfNeeded(session, flushThreshold = 50, keepTail = 10)

        val memoryFile = dir.resolve("MEMORY.md")
        memoryFile.exists() shouldBe true
        memoryFile.readText().shouldContain("Memory Flush")
        (session.snapshot().size < 90) shouldBe true
    }

    test("searchMemory returns relevant bullets") {
        val dir = Files.createTempDirectory("cotor-chat-search")
        val writer = ChatTranscriptWriter(dir)
        val session = ChatSession(includeContext = true, maxHistoryMessages = 100)

        repeat(30) { i ->
            session.addUser("retry policy test $i")
            session.addAssistant("consensus score was high")
        }
        writer.flushMemoryIfNeeded(session, flushThreshold = 40, keepTail = 10)

        val found = writer.searchMemory("retry consensus", limit = 2)
        found.shouldHaveSize(2)
    }
})
