package com.cotor.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeText

class DesktopStateStoreLockTest : FunSpec({
    test("save succeeds when stale lock metadata exists from a previous holder") {
        val appHome = Files.createTempDirectory("desktop-state-lock-stale-home")
        val store = DesktopStateStore { appHome }
        appHome.resolve("state.lock.json")
            .writeText("""{"pid":999,"lockedAt":1,"appHome":"stale"}""")

        runBlocking {
            store.save(
                DesktopAppState(
                    companies = listOf(
                        Company(
                            id = "company-1",
                            name = "Stale Lock Co",
                            rootPath = "/tmp/stale-lock",
                            repositoryId = "repo-1",
                            defaultBaseBranch = "master",
                            createdAt = 1L,
                            updatedAt = 1L
                        )
                    )
                )
            )
        }

        appHome.resolve("state.json").exists() shouldBe true
        appHome.resolve("state.lock.json").exists() shouldBe false
    }

    test("save fails fast with diagnostics when another process holds state.lock") {
        val appHome = Files.createTempDirectory("desktop-state-lock-timeout-home")
        val store = DesktopStateStore { appHome }
        val lockPath = appHome.resolve("state.lock")
        val metadataPath = appHome.resolve("state.lock.json")
        Files.createDirectories(appHome)
        metadataPath.writeText("""{"pid":4242,"lockedAt":1,"appHome":"external-holder"}""")

        val process = ProcessBuilder(
            "/usr/bin/python3",
            "-c",
            """
import fcntl, pathlib, sys, time
path = pathlib.Path(sys.argv[1])
path.parent.mkdir(parents=True, exist_ok=True)
with open(path, "w") as handle:
    fcntl.lockf(handle, fcntl.LOCK_EX)
    print("locked", flush=True)
    time.sleep(5)
""".trimIndent(),
            lockPath.toString()
        ).start()

        try {
            process.inputStream.bufferedReader().readLine() shouldBe "locked"
            val error = shouldThrow<IllegalStateException> {
                runBlocking {
                    store.save(DesktopAppState())
                }
            }
            error.message.orEmpty() shouldContain "state.lock acquisition timed out"
            error.message.orEmpty() shouldContain "holder="
            error.message.orEmpty() shouldContain "4242"
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }
})
