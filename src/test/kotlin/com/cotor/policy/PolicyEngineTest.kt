package com.cotor.policy

import com.cotor.runtime.actions.ActionKind
import com.cotor.runtime.actions.ActionRequest
import com.cotor.runtime.actions.ActionScope
import com.cotor.runtime.actions.ActionSubject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class PolicyEngineTest : FunSpec({
    test("more specific issue scope overrides permissive global scope") {
        val appHome = Files.createTempDirectory("policy-engine-test")
        val store = PolicyStore { appHome }
        store.saveDocument(
            PolicyDocument(
                name = "default",
                defaultEffect = PolicyEffect.ALLOW,
                rules = listOf(
                    PolicyRule(
                        description = "deny github merge on blocked issue",
                        scopeLevel = PolicyScopeLevel.ISSUE,
                        scopeId = "issue-1",
                        effect = PolicyEffect.DENY,
                        actionKinds = listOf(ActionKind.GITHUB_MERGE)
                    )
                )
            )
        )
        val engine = PolicyEngine(store)

        val decision = engine.evaluate(
            ActionRequest(
                kind = ActionKind.GITHUB_MERGE,
                label = "github.merge:12",
                scope = ActionScope.ISSUE,
                subject = ActionSubject(issueId = "issue-1")
            )
        )

        decision.effect shouldBe PolicyEffect.DENY
        decision.explanation.matchedRuleIds.size shouldBe 1
    }
})

