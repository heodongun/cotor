package com.cotor.policy

import com.cotor.runtime.actions.ActionKind
import com.cotor.runtime.actions.ActionRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RiskApprovalInterceptorTest : FunSpec({
    test("github merge requires approval by risk score") {
        val interceptor = RiskApprovalInterceptor()
        val requirement = interceptor.evaluate(
            ActionRequest(
                kind = ActionKind.GITHUB_MERGE,
                label = "github.merge:12",
                networkTarget = "github.com"
            )
        )

        requirement.required shouldBe true
        (requirement.score.total >= 80) shouldBe true
    }

    test("low risk http request stays below threshold") {
        val interceptor = RiskApprovalInterceptor()
        val requirement = interceptor.evaluate(
            ActionRequest(
                kind = ActionKind.HTTP_REQUEST,
                label = "http.request:status",
                networkTarget = "localhost"
            )
        )

        requirement.required shouldBe false
    }
})
