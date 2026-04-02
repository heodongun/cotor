package com.cotor.presentation.cli

/**
 * File overview for InteractiveCommandCompleterTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around interactive command completer test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.jline.reader.Candidate
import org.jline.reader.ParsedLine

class InteractiveCommandCompleterTest : FunSpec({
    val completer = InteractiveCommandCompleter { listOf("codex", "gemini", "claude") }

    test("completes command prefixes") {
        completer.suggest(":ag") shouldContain ":agents"
        completer.suggest(":mo") shouldContain ":mode"
    }

    test("completes mode values") {
        completer.suggest(":mode c") shouldContain ":mode compare"
    }

    test("completes agent name for :use") {
        completer.suggest(":use co") shouldContain ":use codex"
    }

    test("completes include tokens by comma-separated segment") {
        completer.suggest(":include co,ge") shouldContain ":include co,gemini"
    }

    test("lists all mode options when argument is empty") {
        completer.suggest(":mode ") shouldContainAll listOf(":mode auto", ":mode compare", ":mode single")
    }

    test("jline completion returns only argument token for :model") {
        val parsedLine = object : ParsedLine {
            override fun word(): String = "co"
            override fun wordCursor(): Int = 2
            override fun wordIndex(): Int = 1
            override fun words(): MutableList<String> = mutableListOf(":model", "co")
            override fun line(): String = ":model co"
            override fun cursor(): Int = 9
        }
        val candidates = mutableListOf<Candidate>()

        completer.complete(reader = null, line = parsedLine, candidates = candidates)

        candidates.map { it.value() } shouldBe listOf("codex")
    }
})
