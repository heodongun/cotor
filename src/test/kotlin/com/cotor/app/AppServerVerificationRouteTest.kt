package com.cotor.app

import com.cotor.verification.VerificationBundle
import com.cotor.verification.VerificationContract
import com.cotor.verification.VerificationObservation
import com.cotor.verification.VerificationOutcome
import com.cotor.verification.VerificationOutcomeStatus
import com.cotor.verification.VerificationSignal
import com.cotor.verification.VerificationSignalStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk

class AppServerVerificationRouteTest : FunSpec({
    test("verification route returns an issue bundle") {
        val desktopService = mockk<DesktopAppService>(relaxed = true)
        val tuiSessionService = mockk<DesktopTuiSessionService>(relaxed = true)
        coEvery { desktopService.verificationBundle("issue-1") } returns VerificationBundle(
            issueId = "issue-1",
            issueTitle = "Issue",
            contract = VerificationContract(issueId = "issue-1"),
            observations = listOf(
                VerificationObservation(
                    source = "github",
                    signal = VerificationSignal(
                        key = "github-checks",
                        status = VerificationSignalStatus.PASS,
                        detail = "ci=COMPLETED/SUCCESS"
                    )
                )
            ),
            outcome = VerificationOutcome(
                issueId = "issue-1",
                status = VerificationOutcomeStatus.PASS,
                summary = "ok",
                passedSignals = listOf(
                    VerificationSignal(
                        key = "github-checks",
                        status = VerificationSignalStatus.PASS,
                        detail = "ci=COMPLETED/SUCCESS"
                    )
                )
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/verification/issues/issue-1") {
                header("Authorization", "Bearer secret-token")
            }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText().contains("github-checks") shouldBe true
        }
    }
})
