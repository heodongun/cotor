package com.cotor.data.config

import com.cotor.model.YamlParsingException
import io.kotest.assertions.throwables.shouldThrowUnit
import io.kotest.core.spec.style.StringSpec

class YamlParserTest : StringSpec({
    val parser = YamlParser()

    "throws YamlParsingException on incorrect indentation" {
        val badIndentYaml = """
            agents:
              - name: "echo"
               pluginClass: "com.cotor.data.plugin.EchoPlugin"
        """.trimIndent()
        val path = "bad-indent.yaml"

        shouldThrowUnit<YamlParsingException> {
            parser.parse(badIndentYaml, path)
        }
    }

    "throws YamlParsingException on type error for nested property" {
        val typeErrorYaml = """
            agents:
              - name: "echo"
                pluginClass: "com.cotor.data.plugin.EchoPlugin"
                retryPolicy:
                  maxRetries: "three"
        """.trimIndent()
        val path = "type-error.yaml"

        shouldThrowUnit<YamlParsingException> {
            parser.parse(typeErrorYaml, path)
        }
    }
})
