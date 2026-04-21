package com.cotor.runtime.actions

import com.cotor.policy.PolicyDocument
import com.cotor.policy.PolicyEffect
import com.cotor.policy.PolicyEngine
import com.cotor.policy.PolicyRule
import com.cotor.policy.PolicyScopeLevel
import com.cotor.policy.PolicyStore
import com.cotor.provenance.ProvenanceService
import com.cotor.provenance.ProvenanceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class ActionExecutionServiceTest : FunSpec({
    test("denied actions are recorded and block execution") {
        val appHome = Files.createTempDirectory("action-execution-test")
        val policyStore = PolicyStore { appHome }
        policyStore.saveDocument(
            PolicyDocument(
                name = "default",
                defaultEffect = PolicyEffect.ALLOW,
                rules = listOf(
                    PolicyRule(
                        description = "deny git publishes",
                        scopeLevel = PolicyScopeLevel.GLOBAL,
                        effect = PolicyEffect.DENY,
                        actionKinds = listOf(ActionKind.GIT_PUBLISH)
                    )
                )
            )
        )
        val actionStore = ActionStore { appHome }
        val service = ActionExecutionService(
            actionStore = actionStore,
            provenanceService = ProvenanceService(ProvenanceStore { appHome }),
            interceptors = listOf(PolicyEngine(policyStore))
        )

        val error = runCatching {
            kotlinx.coroutines.runBlocking {
                service.run(
                    request = ActionRequest(
                        kind = ActionKind.GIT_PUBLISH,
                        label = "git.publish:test-branch"
                    )
                ) {
                    "should-not-run"
                }
            }
        }.exceptionOrNull()

        error shouldNotBe null
        (error is ActionDeniedException) shouldBe true
        val snapshot = actionStore.load("standalone")
        snapshot shouldNotBe null
        snapshot!!.records.single().status shouldBe ActionStatus.DENIED
    }
})
