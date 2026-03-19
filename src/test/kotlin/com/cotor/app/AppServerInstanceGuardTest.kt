package com.cotor.app

/**
 * File overview for AppServerInstanceGuardTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around app server instance guard test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class AppServerInstanceGuardTest : FunSpec({
    test("desktop app-server lock allows only one live process per app home") {
        val appHome = Files.createTempDirectory("desktop-app-server-lock-home")
        val first = DesktopAppServerInstanceGuard { appHome }
        val second = DesktopAppServerInstanceGuard { appHome }

        val firstRecord = first.acquire(host = "127.0.0.1", port = 8787)
        firstRecord.appHome shouldBe appHome
        Files.exists(firstRecord.metadataPath) shouldBe true

        shouldThrow<IllegalStateException> {
            second.acquire(host = "127.0.0.1", port = 50109)
        }

        first.release()

        val third = DesktopAppServerInstanceGuard { appHome }
        val secondRecord = third.acquire(host = "127.0.0.1", port = 8787)
        secondRecord.lockPath shouldBe firstRecord.lockPath
        third.release()
    }
})
