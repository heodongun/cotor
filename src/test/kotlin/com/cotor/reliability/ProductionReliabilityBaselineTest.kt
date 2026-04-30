package com.cotor.reliability

/**
 * File overview for ProductionReliabilityBaselineTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around production reliability baseline test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ProductionReliabilityBaselineTest {

    @Test
    fun `docker image runs long-lived app server with health probe`() {
        val dockerfile = Files.readString(Path.of("Dockerfile"))

        assertTrue(dockerfile.contains("EXPOSE 8787"), "Dockerfile should expose app-server port 8787")
        assertTrue(
            dockerfile.contains("HEALTHCHECK") && dockerfile.contains("http://127.0.0.1:8787/health"),
            "Dockerfile should define a healthcheck against /health"
        )
        assertTrue(
            dockerfile.contains("CMD [\"app-server\", \"--host\", \"0.0.0.0\", \"--port\", \"8787\"]"),
            "Dockerfile should bind app-server to all container interfaces for published ports"
        )
    }

    @Test
    fun `release workflow tracks master branch and publishes artifacts`() {
        val workflow = Files.readString(Path.of(".github/workflows/release.yml"))

        assertTrue(workflow.contains("- master"), "Release workflow should trigger on pushes to master")
        assertTrue(workflow.contains("build/release/cotor-"), "Release workflow should publish the shaded jar artifact")
        assertTrue(workflow.contains("shasum -a 256 \"build/release/cotor-"), "Release workflow should generate the shaded jar checksum")
        assertTrue(workflow.contains("build/release/Cotor-"), "Release workflow should publish the desktop DMG artifact")
    }

    @Test
    fun `app server exposes explicit health and readiness endpoints`() {
        val appServerSource = Files.readString(Path.of("src/main/kotlin/com/cotor/app/AppServer.kt"))

        assertTrue(appServerSource.contains("get(\"/health\")"), "App server should define /health endpoint")
        assertTrue(appServerSource.contains("get(\"/ready\")"), "App server should define /ready endpoint")
        assertTrue(
            appServerSource.contains("HealthResponse(ok = true, service = \"cotor-app-server\")"),
            "Health and readiness endpoints should report readiness payload"
        )
    }
}
