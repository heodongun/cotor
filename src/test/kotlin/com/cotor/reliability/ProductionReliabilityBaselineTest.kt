package com.cotor.reliability

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

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
            "Dockerfile should start app-server as the default command"
        )
    }

    @Test
    fun `docker publish workflow tracks master branch`() {
        val workflow = Files.readString(Path.of(".github/workflows/docker-image.yml"))

        assertTrue(workflow.contains("- master"), "Docker publish workflow should trigger on pushes to master")
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
