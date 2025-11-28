package com.cotor.presentation.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Doctor command: checks environment readiness and gives quick tips
 */
class DoctorCommand : CliktCommand(
    name = "doctor",
    help = "Check environment, prerequisites, and examples"
) {
    private val terminal = Terminal()
    private val projectRoot = Path(".").toAbsolutePath().normalize()

    override fun run() {
        terminal.println(bold("🩺 Cotor Doctor"))
        terminal.println(dim("환경 점검을 수행합니다. OK 항목이 하나라도 실패해도 명령은 계속됩니다."))
        terminal.println()

        val checks = listOf(
            checkJava(),
            checkJar(),
            checkConfig(),
            checkExamples(),
            checkBinary("claude"),
            checkBinary("gemini"),
            checkBinary("cotor")
        )

        checks.forEach { (label, ok, hint) ->
            val icon = if (ok) green("✓") else yellow("⚠")
            terminal.println("$icon $label")
            if (!hint.isNullOrBlank()) {
                terminal.println(dim("   $hint"))
            }
        }

        terminal.println()
        terminal.println(dim("팁:"))
        terminal.println(dim("  - 자동완성: cotor completion zsh|bash|fish > /tmp/cotor && source /tmp/cotor"))
        terminal.println(dim("  - 샘플 실행: examples/run-examples.sh"))
        terminal.println(dim("  - Claude 연동: ./shell/install-claude-integration.sh"))
    }

    private fun checkJava(): Triple<String, Boolean, String?> {
        val version = System.getProperty("java.version") ?: "unknown"
        val major = version.substringBefore(".").toIntOrNull() ?: 0
        val ok = major >= 17
        val hint = if (!ok) "Java 17 이상이 필요합니다. 현재: $version" else "Java $version"
        return Triple("Java 버전 확인", ok, hint)
    }

    private fun checkJar(): Triple<String, Boolean, String?> {
        val jarPath = projectRoot.resolve("build/libs/cotor-1.0.0-all.jar")
        val ok = jarPath.exists()
        val hint = if (ok) "빌드 결과 발견: ${jarPath.fileName}" else "shadowJar 실행 필요: ./gradlew shadowJar"
        return Triple("CLI JAR 존재 여부", ok, hint)
    }

    private fun checkConfig(): Triple<String, Boolean, String?> {
        val path = projectRoot.resolve("cotor.yaml")
        val ok = path.exists()
        val hint = if (ok) "구성 파일 발견: cotor.yaml" else "구성이 없습니다. cotor init 또는 cotor init --interactive 실행"
        return Triple("cotor.yaml 확인", ok, hint)
    }

    private fun checkExamples(): Triple<String, Boolean, String?> {
        val required = listOf(
            "examples/single-agent.yaml",
            "examples/parallel-compare.yaml",
            "examples/decision-loop.yaml",
            "examples/run-examples.sh"
        )
        val missing = required.filterNot { projectRoot.resolve(it).exists() }
        val ok = missing.isEmpty()
        val hint = if (ok) "예제 준비 완료" else "누락: ${missing.joinToString(", ")}"
        return Triple("예제 번들 확인", ok, hint)
    }

    private fun checkBinary(name: String): Triple<String, Boolean, String?> {
        val ok = isCommandAvailable(name)
        val label = "$name 명령 확인"
        val hint = if (ok) "$name 사용 가능" else "$name 가 PATH에 없습니다 (필요시 설치)"
        return Triple(label, ok, hint)
    }

    private fun isCommandAvailable(name: String): Boolean {
        val cmd = if (isWindows()) listOf("where", name) else listOf("which", name)
        return try {
            ProcessBuilder(cmd).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
