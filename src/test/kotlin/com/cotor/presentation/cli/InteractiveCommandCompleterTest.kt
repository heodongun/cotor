package com.cotor.presentation.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll

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
})
